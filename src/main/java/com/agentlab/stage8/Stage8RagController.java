package com.agentlab.stage8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.agentlab.config.OpenApiConfig;
import com.agentlab.stage8.KnowledgeBase.Hit;
import com.agentlab.stage8.KnowledgeBase.IngestRecord;
import com.agentlab.stage8.KnowledgeBase.Stats;
import com.agentlab.stage8.KnowledgeBaseRegistry.BaseBrief;
import com.agentlab.stage8.config.RagAdvisors;
import com.agentlab.stage8.config.RagProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 8 —— RAG 知识库（L1 朴素 RAG，支持多知识库）。
 *
 * <h2>接口分三类</h2>
 * <ol>
 *   <li><b>库管理</b>：{@code GET/POST/DELETE /stage8/kb...} —— 建库、列出、删库。
 *       这类接口只动元数据，不碰向量。</li>
 *   <li><b>库内操作（不经过大模型）</b>：入库、纯向量检索、统计、清空、落盘。
 *       这是排查 RAG 问题的第一现场 —— <b>不掺模型，结果完全可复现</b>。</li>
 *   <li><b>生成类（经过大模型）</b>：{@code /stage8/chat}。
 *       在检索之上叠加 Prompt 增强与生成。</li>
 * </ol>
 *
 * <h2>路径规则：带 {@code {kbId}} 是新写法，不带是「默认库」的简写</h2>
 * <pre>
 *   新：POST /stage8/kb/kb1/ingest          ← 明确指定往 kb1 里灌
 *   旧：POST /stage8/kb/ingest              ← 等价于 /stage8/kb/default/ingest
 * </pre>
 * 两套路径指向同一段代码（方法上的 {@code @PostMapping} 同时挂了两个模板，
 * {@code @PathVariable(required = false)} 拿到 null 时落到默认库）。
 * 之所以保留旧路径：单库时代的 curl / 脚本 / 文档都不必改，
 * <b>而「升级一次就作废用户所有既有命令」是很差劲的体验</b>。
 *
 * <h2>推荐体验路径：从「单库」走到「多库」</h2>
 * <pre>
 *   ① 先证明「模型原本不知道」：
 *      curl "http://localhost:8090/stage8/chat?message=追光科技的年假是怎么规定的？"
 *
 *   ② 默认库一键载入内置语料（resources/rag/*.md，3 篇虚构企业文档）：
 *      curl -X POST "http://localhost:8090/stage8/kb/ingest-sample"
 *
 *   ③ 看检索层命中了什么（没有模型参与，可复现）：
 *      curl "http://localhost:8090/stage8/kb/search?query=年假有几天"
 *
 *   ④ 新建两个库，验证「互不干扰」：
 *      curl -X POST "http://localhost:8090/stage8/kb" -H "Content-Type: application/json" \
 *           -d '{"id":"kb1","name":"知识库1"}'
 *      curl -X POST "http://localhost:8090/stage8/kb" -H "Content-Type: application/json" \
 *           -d '{"id":"kb2","name":"知识库2"}'
 *      curl -X POST "http://localhost:8090/stage8/kb/kb1/ingest" -H "Content-Type: application/json" \
 *           -d '{"title":"AAA 项目说明","content":"AAA 项目的验收标准是绿灯率 97%。"}'
 *      curl "http://localhost:8090/stage8/kb/kb2/search?query=AAA 验收标准"   → 空（kb2 里没这东西）
 *      curl "http://localhost:8090/stage8/kb/kb1/search?query=AAA 验收标准"   → 命中
 *
 *   ⑤ 各自提问，看回答只依据自己那个库：
 *      curl "http://localhost:8090/stage8/chat?kbId=kb1&message=AAA 项目的验收标准是什么？"
 * </pre>
 *
 * <h2>⚠️ 提示：curl 里不要出现未编码的中文</h2>
 * 本机（Windows + Git Bash）把命令行参数交给原生 {@code curl.exe} 时会按 ANSI(GBK) 转换，
 * 中文会变成非法 UTF-8 被服务端 400 拒掉。查询串请先百分号编码再拼进 URL，
 * 请求体请写成 UTF-8 文件用 {@code --data-binary @file} 发。
 */
@RestController
@RequestMapping("/stage8")
@Tag(name = OpenApiConfig.TAG_STAGE8)
@ConditionalOnProperty(prefix = "agentlab.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Stage8RagController {

    /** 实验组的 system prompt：把「只依据资料作答」再强调一遍，与中文模板叠加。 */
    private static final String RAG_SYSTEM_PROMPT = """
            你是一个严谨的企业知识助手，回答必须完全依据提示词中提供的「检索到的资料」。
            资料里没有的，就明确说知识库中没有相关信息。
            """;

    /** 对照组的 system prompt：明确禁止编造，让「答不出来」的表现更干净。 */
    private static final String PLAIN_SYSTEM_PROMPT = """
            你是一个企业知识助手。
            如果问题涉及某个具体公司的内部制度、报价、流程，而你没有依据，
            就直接回答「我没有这方面的资料」，不要编造具体数字或条款。
            """;

    private final ChatModel chatModel;
    private final KnowledgeBaseRegistry registry;
    private final RagProperties props;

    /** 不挂 RAG 的客户端：对照组。全局只有一个，因为它和知识库无关。 */
    private final ChatClient plainClient;

    public Stage8RagController(ChatModel chatModel, KnowledgeBaseRegistry registry, RagProperties props) {
        this.chatModel = chatModel;
        this.registry = registry;
        this.props = props;
        this.plainClient = ChatClient.builder(chatModel)
                .defaultSystem(PLAIN_SYSTEM_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    // ==================================================================
    // 1. 知识库管理（只动元数据，不碰向量）
    // ==================================================================

    @GetMapping("/kb")
    @Operation(summary = "列出全部知识库",
            description = "返回每个库的 id / 名称 / 备注 / 创建时间，以及「它在内存里加载了吗、有几篇文档几块」。"
                    + "注意 loaded=false 时文档数为 -1：向量的加载是懒的（第一次被访问才读盘），"
                    + "列个表不该把 20 个库全读进内存。")
    public Map<String, Object> list() {
        List<BaseBrief> bases = registry.list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", registry.root().toString());
        result.put("count", bases.size());
        result.put("defaultId", KnowledgeBaseRegistry.DEFAULT_ID);
        result.put("knowledgeBases", bases);
        return result;
    }

    @PostMapping("/kb")
    @Operation(summary = "创建知识库",
            description = "id 只允许字母/数字/下划线/连字符（它同时是磁盘目录名），中文请写在 name 里。"
                    + "新建的库在 <store-root>/<id>/ 下拥有独立目录，与其它库物理隔离。")
    public BaseBrief create(@Valid @RequestBody CreateKbRequest request) {
        KnowledgeBase created = registry.create(request.id(), request.name(), request.description());
        return new BaseBrief(created.id(), created.meta().name(), created.meta().description(),
                created.meta().createdAt(), true, 0, 0);
    }

    @DeleteMapping("/kb/{kbId}")
    @Operation(summary = "删除知识库（连同它的目录）",
            description = "注意与下面的「清空」区分：<b>删除是连库带数据一起没了</b>，"
                    + "清空只是把库腾空、库还在。默认库不允许删除（只能清空），"
                    + "因为不带 kbId 的老接口都落在它上面。")
    public Map<String, Object> delete(@PathVariable String kbId) {
        boolean removed = registry.delete(kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", kbId);
        result.put("deleted", removed);
        return result;
    }

    // ==================================================================
    // 2. 库内操作（不经过大模型）
    // ==================================================================

    @PostMapping({"/kb/ingest", "/kb/{kbId}/ingest"})
    @Operation(summary = "把一段文本切块并写入知识库",
            description = "链路：原文 →（标题拼进正文）→ TokenTextSplitter 切块 → 逐块嵌入 → 该库自己的向量库。"
                    + "不传 kbId 时写入默认库。返回每个片段在原文中的序号与总块数，用于判断切块粒度。")
    public IngestRecord ingest(@PathVariable(required = false) String kbId,
                               @Valid @RequestBody IngestRequest request) {
        KnowledgeBase kb = registry.get(kbId);
        return kb.ingest(request.title(), request.content(),
                request.source() == null || request.source().isBlank() ? "api" : request.source());
    }

    @PostMapping({"/kb/ingest-sample", "/kb/{kbId}/ingest-sample"})
    @Operation(summary = "载入内置示例语料（resources/rag/*.md）",
            description = "3 篇虚构企业文档：员工手册、产品与定价、运维值班规范。"
                    + "可重复调用 —— 同一来源会先清理旧片段，保证幂等。"
                    + "之所以用虚构语料：模型对它完全零先验，RAG 生效与否一眼可辨。"
                    + "把它分别灌进 kb1 和 kb2，就能观察到「两个库各自独立、互不影响」。")
    public List<IngestRecord> ingestSample(@PathVariable(required = false) String kbId) {
        return registry.get(kbId).ingestSampleDocs();
    }

    @GetMapping({"/kb/search", "/kb/{kbId}/search"})
    @Operation(summary = "纯向量检索（不调用大模型）",
            description = "RAG 调优的第一现场：如果这里没召回正确片段，再怎么改提示词都没用。"
                    + "返回每条命中的相似度、来源文档、块序号与原文。"
                    + "不传 topK / threshold 时用配置里的默认值（agentlab.rag.*）。")
    public List<Hit> search(
            @PathVariable(required = false) String kbId,
            @Parameter(description = "查询语句，例：年假有几天", example = "年假有几天")
            @RequestParam String query,
            @Parameter(description = "返回条数，不传用配置默认值")
            @RequestParam(required = false) Integer topK,
            @Parameter(description = "相似度下限 [-1,1]，不传用配置默认值；传 0 相当于不过滤")
            @RequestParam(required = false) Double threshold) {
        return registry.get(kbId).searchAsHits(query, topK, threshold);
    }

    @GetMapping({"/kb/stats", "/kb/{kbId}/stats"})
    @Operation(summary = "知识库现状",
            description = "库 id / 名称 / 文档数 / 片段数 / 向量维度 / 当前切块与检索参数 / 落盘文件状态，"
                    + "以及已入库文档清单。注意「清单」是应用自己维护的 —— "
                    + "VectorStore 接口只有 add/delete/similaritySearch，没有 count/list。")
    public Stats stats(@PathVariable(required = false) String kbId) {
        return registry.get(kbId).stats();
    }

    @DeleteMapping("/kb")
    @Operation(summary = "清空【默认知识库】（兼容单库时代的老接口）",
            description = "删除默认库的全部向量与清单，但保留库本身。"
                    + "指定别的库请用 DELETE /stage8/kb/{kbId}/clear；"
                    + "想连库一起删掉用 DELETE /stage8/kb/{kbId}。")
    public Map<String, Object> clearDefault() {
        return clear(null);
    }

    @DeleteMapping("/kb/{kbId}/clear")
    @Operation(summary = "清空指定知识库的内容（保留库）",
            description = "把库腾空，但 id / 名称 / 目录都还在，可以立刻重新入库。"
                    + "想连库一起删掉用 DELETE /stage8/kb/{kbId}。")
    public Map<String, Object> clear(@PathVariable(required = false) String kbId) {
        KnowledgeBase kb = registry.get(kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", kb.id());
        result.put("removedChunks", kb.clear());
        return result;
    }

    @PostMapping({"/kb/save", "/kb/{kbId}/save"})
    @Operation(summary = "手动把向量库落盘（正常流程已自动落盘）",
            description = "SimpleVectorStore 是纯内存实现，进程一停向量就没了。"
                    + "入库与清空之后应用都会自动落盘（清单 + 向量一起写，保证两者代际一致），"
                    + "所以这个接口主要用于主动确认「磁盘上那份到底是什么」。")
    public Map<String, Object> save(@PathVariable(required = false) String kbId) {
        return registry.get(kbId).saveToDisk();
    }

    @PostMapping({"/kb/load", "/kb/{kbId}/load"})
    @Operation(summary = "从磁盘载入向量库",
            description = "配合上一个接口使用。清单在库第一次被访问时会自动恢复，"
                    + "所以这里只需把向量本体 load 回来。")
    public Map<String, Object> load(@PathVariable(required = false) String kbId) {
        return registry.get(kbId).loadFromDisk();
    }

    // ==================================================================
    // 3. 带检索的问答（经过大模型）
    // ==================================================================

    @GetMapping("/chat")
    @Operation(summary = "RAG 问答（已挂 QuestionAnswerAdvisor）",
            description = "链路：提问 → 该库的向量检索 topK → 把片段拼进 Prompt → DeepSeek 生成。"
                    + "kbId 不传则用默认库。控制台日志里能看到完整 Prompt，"
                    + "也就是「模型到底拿到了什么资料」。")
    public String chat(
            @Parameter(description = "用户提问", example = "追光科技的年假是怎么规定的？")
            @RequestParam String message,
            @Parameter(description = "知识库 id，不传用默认库", example = "kb1")
            @RequestParam(required = false) String kbId) {
        return ragClientFor(registry.get(kbId)).prompt().user(message).call().content();
    }

    @GetMapping("/chat/compare")
    @Operation(summary = "对照实验：无 RAG vs 有 RAG",
            description = "同一个问题问两次，并排返回两版回答，同时附上「指定库里检索到了什么」。"
                    + "这是最能说明 RAG 价值的一个接口："
                    + "① 没有知识库时模型只能编；② 有知识库时回答里出现了不可能编出来的具体条款。"
                    + "换个 kbId 再调一次，就能看到「同一个问题在不同知识库里得到不同答案」——"
                    + "这正是多知识库的意义。")
    public CompareResult compare(
            @Parameter(description = "用户提问", example = "值班补贴多少钱一天？")
            @RequestParam String message,
            @Parameter(description = "知识库 id，不传用默认库", example = "kb1")
            @RequestParam(required = false) String kbId) {
        KnowledgeBase kb = registry.get(kbId);
        List<Hit> hits = kb.searchAsHits(message, null, null);

        long t0 = System.currentTimeMillis();
        String withoutRag = plainClient.prompt().user(message).call().content();
        long withoutCost = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        String withRag = ragClientFor(kb).prompt().user(message).call().content();
        long withCost = System.currentTimeMillis() - t1;

        return new CompareResult(kb.id(), message, hits.size(), hits,
                new Answer(withoutRag, withoutCost),
                new Answer(withRag, withCost));
    }

    /**
     * 为指定知识库现造一个挂了 RAG Advisor 的 ChatClient。
     *
     * <p>为什么<b>不缓存</b>：{@code QuestionAnswerAdvisor} 与 store 是一对一绑定的，
     * 一旦某个库被清空或删除重建，缓存里那个 Advisor 还指着旧 store ——
     * 于是你会得到一个「答案来自已经删掉的库」的诡异现象。
     * 而这两样东西都是纯内存轻量对象（构造过程零 I/O），
     * 相对一次「嵌入 + 检索 + 大模型生成」（几百毫秒到几秒）的开销完全可以忽略。
     * <b>当缓存的失效逻辑比重新构造还复杂时，就不要缓存。</b>
     */
    private ChatClient ragClientFor(KnowledgeBase kb) {
        return ChatClient.builder(chatModel)
                .defaultSystem(RAG_SYSTEM_PROMPT)
                .defaultAdvisors(RagAdvisors.forStore(kb.store(), props), new SimpleLoggerAdvisor())
                .build();
    }

    // ==================================================================
    // 异常映射：把「库不存在」变成 404，而不是 500
    // ==================================================================

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNotFound(NoSuchElementException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", "knowledge_base_not_found");
        result.put("message", e.getMessage());
        result.put("hint", "GET /stage8/kb 可以列出全部知识库");
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", "invalid_request");
        result.put("message", e.getMessage());
        return result;
    }

    // ==================================================================
    // 请求 / 响应体
    // ==================================================================

    /** 创建知识库请求。 */
    public record CreateKbRequest(
            @NotBlank(message = "id 不能为空")
            @Schema(description = "库标识，只允许字母/数字/下划线/连字符（同时是磁盘目录名）",
                    example = "kb1")
            String id,

            @Schema(description = "显示名，可以是中文", example = "知识库1")
            String name,

            @Schema(description = "备注，纯给人看", example = "只放产品线 A 的资料")
            String description) {
    }

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
    public record CompareResult(String knowledgeBase, String question, int hitCount, List<Hit> retrieved,
                                Answer withoutRag, Answer withRag) {
    }
}
