package com.agentlab.stage8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentlab.config.OpenApiConfig;
import com.agentlab.stage8.KnowledgeBaseService.Hit;
import com.agentlab.stage8.KnowledgeBaseService.IngestRecord;
import com.agentlab.stage8.KnowledgeBaseService.Stats;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 8 —— RAG 知识库（L1 朴素 RAG）。
 *
 * <h2>接口分两类，请按这个顺序体验</h2>
 * <ol>
 *   <li><b>检索类（不经过大模型）</b>：{@code /stage8/kb/**}。
 *       入库、纯向量检索、统计。这类接口是排查 RAG 问题的第一现场 ——
 *       <b>不掺模型，结果完全可复现</b>。</li>
 *   <li><b>生成类（经过大模型）</b>：{@code /stage8/chat}。
 *       在检索之上叠加 Prompt 增强与生成。</li>
 * </ol>
 *
 * <h2>推荐体验路径</h2>
 * <pre>
 *   ① 先证明「模型原本不知道」：
 *      curl "http://localhost:8090/stage8/chat?message=追光科技的年假是怎么规定的？"
 *      → 模型只会编，或说不知道（这些条款是虚构的）
 *
 *   ② 一键载入内置示例语料（resources/rag/*.md，3 篇虚构企业文档）：
 *      curl -X POST "http://localhost:8090/stage8/kb/ingest-sample"
 *
 *   ③ 看检索层命中了什么（没有模型参与，可复现）：
 *      curl "http://localhost:8090/stage8/kb/search?query=年假有几天"
 *
 *   ④ 再问同一个问题，看回答如何变成「有依据」：
 *      curl "http://localhost:8090/stage8/chat?message=追光科技的年假是怎么规定的？"
 *
 *   ⑤ 一次调用看清差别（无 RAG vs 有 RAG 并排）：
 *      curl "http://localhost:8090/stage8/chat/compare?message=值班补贴多少钱一天？"
 * </pre>
 *
 * <h2>⚠️ 示例语料里藏了一处「故意矛盾」，别当 bug</h2>
 * 《员工手册》写「工作日值班 200 元/天」，《运维值班与故障响应规范》写 300 元/天。
 * 这是刻意设计的：RAG 的价值不只是「答得出来」，还包括
 * <b>把知识库自身的矛盾暴露出来</b>。提示词里明确要求「遇到矛盾必须指出并分别列出」，
 * 所以 {@code /stage8/chat} 问值班补贴时，正确的表现是「指出两份文档不一致」，
 * 而不是随便挑一个数字回答。顺便也演示了「同一问题召回多个来源」时的处理方式。
 */
@RestController
@RequestMapping("/stage8")
@Tag(name = OpenApiConfig.TAG_STAGE8)
public class Stage8RagController {

    /** 不挂 RAG 的客户端：对照组。 */
    private final ChatClient plainClient;

    /** 挂了 QuestionAnswerAdvisor 的客户端：实验组。 */
    private final ChatClient ragClient;

    private final KnowledgeBaseService knowledgeBase;

    public Stage8RagController(ChatModel chatModel,
                              QuestionAnswerAdvisor questionAnswerAdvisor,
                              KnowledgeBaseService knowledgeBase) {
        this.knowledgeBase = knowledgeBase;

        // 对照组：明确告诉模型「不要编」，这样它答不出来时的表现更干净，
        // 对比才有说服力（否则可能靠先验知识蒙对）。
        this.plainClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个企业知识助手。
                        如果问题涉及某个具体公司的内部制度、报价、流程，而你没有依据，
                        就直接回答「我没有这方面的资料」，不要编造具体数字或条款。
                        """)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        // 实验组：RAG Advisor 的 order 是 -100，排在日志 Advisor（默认 0）外面，
        // 所以控制台里打印出来的 Prompt 是「已经被注入检索片段」的那一版 ——
        // 想确认「检索到的东西到底有没有进 Prompt」，看日志最直接。
        this.ragClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个严谨的企业知识助手，回答必须完全依据提示词中提供的「检索到的资料」。
                        资料里没有的，就明确说知识库中没有相关信息。
                        """)
                .defaultAdvisors(questionAnswerAdvisor, new SimpleLoggerAdvisor())
                .build();
    }

    // ==================================================================
    // 1. 知识库管理（不经过大模型）
    // ==================================================================

    @PostMapping("/kb/ingest")
    @Operation(summary = "把一段文本切块并写入知识库",
            description = "链路：原文 →（标题拼进正文）→ TokenTextSplitter 切块 → 逐块嵌入 → SimpleVectorStore。"
                    + "返回每个片段在原文中的序号与总块数，用于判断切块粒度是否合理。")
    public IngestRecord ingest(@Valid @RequestBody IngestRequest request) {
        return knowledgeBase.ingest(request.title(), request.content(),
                request.source() == null || request.source().isBlank() ? "api" : request.source());
    }

    @PostMapping("/kb/ingest-sample")
    @Operation(summary = "载入内置示例语料（resources/rag/*.md）",
            description = "3 篇虚构企业文档：员工手册、产品与定价、运维值班规范。"
                    + "可重复调用 —— 同一来源会先清理旧片段，保证幂等。"
                    + "之所以用虚构语料：模型对它完全零先验，RAG 生效与否一眼可辨。")
    public List<IngestRecord> ingestSample() {
        return knowledgeBase.ingestSampleDocs();
    }

    @GetMapping("/kb/search")
    @Operation(summary = "纯向量检索（不调用大模型）",
            description = "RAG 调优的第一现场：如果这里没召回正确片段，再怎么改提示词都没用。"
                    + "返回每条命中的相似度、来源文档、块序号与原文。"
                    + "不传 topK / threshold 时用配置里的默认值（agentlab.rag.*）。")
    public List<Hit> search(
            @Parameter(description = "查询语句，例：年假有几天", example = "年假有几天")
            @RequestParam String query,
            @Parameter(description = "返回条数，不传用配置默认值")
            @RequestParam(required = false) Integer topK,
            @Parameter(description = "相似度下限 [-1,1]，不传用配置默认值；传 0 相当于不过滤")
            @RequestParam(required = false) Double threshold) {
        return knowledgeBase.searchAsHits(query, topK, threshold);
    }

    @GetMapping("/kb/stats")
    @Operation(summary = "知识库现状",
            description = "文档数、片段数、向量维度、当前切块与检索参数、落盘文件状态，"
                    + "以及已入库文档清单。注意「清单」是应用自己维护的 —— "
                    + "VectorStore 接口只有 add/delete/similaritySearch，没有 count/list。")
    public Stats stats() {
        return knowledgeBase.stats();
    }

    @DeleteMapping("/kb")
    @Operation(summary = "清空知识库",
            description = "删除全部向量与清单。想重新观察「无 RAG → 有 RAG」的对比时先调这个。")
    public Map<String, Object> clear() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removedChunks", knowledgeBase.clear());
        return result;
    }

    @PostMapping("/kb/save")
    @Operation(summary = "手动把向量库落盘（正常流程已自动落盘）",
            description = "SimpleVectorStore 是纯内存实现，进程一停向量就没了。"
                    + "入库与清空之后应用都会自动落盘（清单 + 向量一起写，保证两者代际一致），"
                    + "所以这个接口主要用于主动确认「磁盘上那份到底是什么」——"
                    + "比如想检查落盘文件大小、或者落盘失败时手动重试。")
    public Map<String, Object> save() {
        return knowledgeBase.saveToDisk();
    }

    @PostMapping("/kb/load")
    @Operation(summary = "从磁盘载入向量库",
            description = "配合上一个接口使用。清单（manifest）会在启动时自动恢复，"
                    + "所以这里只需把向量本体 load 回来。")
    public Map<String, Object> load() {
        return knowledgeBase.loadFromDisk();
    }

    // ==================================================================
    // 2. 带检索的问答（经过大模型）
    // ==================================================================

    @GetMapping("/chat")
    @Operation(summary = "RAG 问答（已挂 QuestionAnswerAdvisor）",
            description = "链路：提问 → 向量检索 topK → 把片段拼进 Prompt → DeepSeek 生成。"
                    + "控制台日志里能看到完整 Prompt，也就是「模型到底拿到了什么资料」。")
    public String chat(
            @Parameter(description = "用户提问", example = "追光科技的年假是怎么规定的？")
            @RequestParam String message) {
        return ragClient.prompt().user(message).call().content();
    }

    @GetMapping("/chat/compare")
    @Operation(summary = "对照实验：无 RAG vs 有 RAG",
            description = "同一个问题问两次，并排返回两版回答，同时附上「检索层命中了什么」。"
                    + "这是最能说明 RAG 价值的一个接口："
                    + "① 没有知识库时模型只能编；② 有知识库时回答里出现了不可能编出来的具体条款。"
                    + "注意 also 返回耗时 —— RAG 要额外付一次嵌入 + 一次检索的代价。")
    public CompareResult compare(
            @Parameter(description = "用户提问", example = "值班补贴多少钱一天？")
            @RequestParam String message) {
        List<Hit> hits = knowledgeBase.searchAsHits(message, null, null);

        long t0 = System.currentTimeMillis();
        String withoutRag = plainClient.prompt().user(message).call().content();
        long withoutCost = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        String withRag = ragClient.prompt().user(message).call().content();
        long withCost = System.currentTimeMillis() - t1;

        return new CompareResult(message, hits.size(), hits,
                new Answer(withoutRag, withoutCost),
                new Answer(withRag, withCost));
    }

    // ==================================================================
    // 请求 / 响应体
    // ==================================================================

    /** 入库请求。 */
    public record IngestRequest(
            @NotBlank(message = "title 不能为空")
            @Schema(description = "文档标题", example = "差旅报销补充说明")
            String title,

            @NotBlank(message = "content 不能为空")
            @Schema(description = "正文", example = "出差住宿标准：一线城市 600 元/晚。")
            String content,

            @Schema(description = "来源标识，不传则为 api")
            String source) {
    }

    /** 一次回答 + 耗时。 */
    public record Answer(String text, long costMillis) {
    }

    /** 对照实验结果。 */
    public record CompareResult(String question, int hitCount, List<Hit> retrieved,
                                Answer withoutRag, Answer withRag) {
    }
}
