package com.agentlab.stage8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import com.agentlab.config.OpenApiConfig;
import com.agentlab.stage8.KnowledgeBase.DocBrief;
import com.agentlab.stage8.KnowledgeBase.DocDetail;
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
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stage 8 —— RAG 知识库（L1 朴素 RAG，支持多知识库）。
 *
 * <h2>接口分四类</h2>
 * <ol>
 *   <li><b>库管理</b>：{@code /stage8/kb...} —— 建库、列出、查单个、改名称备注、删库。
 *       只动元数据，不碰向量。</li>
 *   <li><b>库内操作（不经过大模型）</b>：入库、纯向量检索、统计、清空、落盘。
 *       这是排查 RAG 问题的第一现场 —— <b>不掺模型，结果完全可复现</b>。</li>
 *   <li><b>文档级增删改查</b>：{@code /kb/{kbId}/docs...} —— 粒度从「整库」降到「一篇」。
 *       这一层是补出来的：原先想更新一篇文档，只能 {@code clear()} 整库再重灌。</li>
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
 * <p>也有两处<b>没法</b>用「双路径模板」照搬，各自用了不同的办法，值得留意：
 * <ul>
 *   <li>{@code DELETE /stage8/kb}（清空）—— 它的老形式本来就是「没有多余路径段」的，
 *       再挂一个 {@code /kb/{kbId}} 会被「删库」占住，所以改用 {@code ?kbId=}：</li>
 *   <li>{@code /stage8/chat} —— 签名先于多知识库存在，于是保留 {@code ?kbId=}
 *       并另开一条路径写法 {@code /kb/{kbId}/chat}。</li>
 * </ul>
 * 共同点是：<b>新入口可以做加法，老签名的语义一个字都不动。</b>
 *
 * <h2>⚠️ 保留字：库 id 不能叫 {@code docs} / {@code search} / {@code stats} …</h2>
 * 这些名字在 {@code /stage8/kb/} 下已经是固定路径段。若允许拿它们当库 id，
 * 那个库就会「建得出来、却谁也访问不到」—— 请求会被字面量路径优先截走。
 * 所以 {@code create} 会直接拒绝（见 {@code KnowledgeBaseRegistry.RESERVED_IDS}）。
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

    @GetMapping("/kb/{kbId}")
    @Operation(summary = "查看单个知识库",
            description = "按 kbId 查这一个库：名称、备注、创建时间、目录、文档数、片段数。"
                    + "和 GET /stage8/kb（列出全部）的分工是「只关心一个库」时不必自己去数组里翻。"
                    + "注意 kbId 若是 search / stats / docs 这类保留字，请求会被那批固定接口接走 —— "
                    + "所以建库时这些名字是禁止的（见「创建知识库」的说明）。")
    public KbDetail detail(@PathVariable String kbId) {
        KnowledgeBase kb = registry.get(kbId);
        Stats snapshot = kb.stats();
        return new KbDetail(kb.id(), snapshot.name(), snapshot.description(), snapshot.createdAt(),
                kb.dir().toString(), snapshot.documents(), snapshot.chunks(), snapshot.storeFileExists());
    }

    @PutMapping("/kb/{kbId}")
    @Operation(summary = "更新知识库的名称 / 备注",
            description = "补的是 CRUD 里缺的那个 U —— 这个接口出现之前，库名建完就改不了了。"
                    + "name 与 description 都是「<b>不传就不改</b>」：只改其中一个时另一个可以整个省略，"
                    + "不必先读出来再原样写回（那正是并发下最容易丢更新的写法）。"
                    + "想清空备注请显式传空串。id 不能改 —— 它同时是磁盘目录名与账本主键。")
    public KbDetail update(@PathVariable String kbId, @RequestBody UpdateKbRequest request) {
        registry.update(kbId, request.name(), request.description());
        return detail(kbId);
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
                    + "不传 kbId 时写入默认库。返回每个片段在原文中的序号与总块数，用于判断切块粒度。"
                    + "<p><b>默认是追加（append）</b>：同一篇内容灌两次会得到两份。"
                    + "想覆盖请传 <code>mode=upsert</code> —— 它按 <code>source</code> 定位、先删后入，"
                    + "所以<b>必须给一个唯一的 source</b>，否则会连同其它 source 相同的文档一起删掉。"
                    + "更精确的「只改这一篇」请用 PUT /stage8/kb/{kbId}/docs/{docId}。")
    public IngestRecord ingest(@PathVariable(required = false) String kbId,
                               @Parameter(description = "append（追加，默认）或 upsert（按 source 覆盖）",
                                       example = "append")
                               @RequestParam(required = false, defaultValue = "append") String mode,
                               @Valid @RequestBody IngestRequest request) {
        if (!"append".equalsIgnoreCase(mode) && !"upsert".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("mode 只支持 append（追加，默认）或 upsert（按 source 覆盖），"
                    + "收到：" + mode);
        }
        KnowledgeBase kb = registry.get(kbId);
        String source = request.source() == null || request.source().isBlank() ? "api" : request.source();
        return "upsert".equalsIgnoreCase(mode)
                ? kb.upsertBySource(request.title(), request.content(), source)
                : kb.ingest(request.title(), request.content(), source);
    }

    @PostMapping({"/kb/upload", "/kb/{kbId}/upload"})
    @Operation(summary = "上传 .md 文件入库（切片 → 向量化 → 存入内存）",
            description = "比 /ingest 多两件事：<b>收文件</b>，以及<b>把切片结果还给你</b>。"
                    + "链路：文件 →（按 UTF-8 读，剥 BOM）→ 推标题 → 标题拼进正文 → 分词切块 "
                    + "→ 逐块嵌入 → 写进该库自己的向量库。"
                    + "<p>响应里的 <code>uploaded[].previews</code> 是每个片段的前 120 字 —— "
                    + "这是唯一能看到「原文被切成了什么样」的地方：VectorStore 接口没有 get，"
                    + "片段正文入库后就取不回来了。调 <code>agentlab.rag.chunk-size</code> 时对着它看最直观。"
                    + "<p>标题取法：正文第一个 <code># 一级标题</code> 优先，否则用文件名（去扩展名）。"
                    + "<p>source 固定取文件名，所以 <code>mode=upsert</code> 的语义是「同名文件覆盖那一篇」——"
                    + "重复上传同一份资料不会越堆越多。<b>默认 append</b>，两次上传就是两份。"
                    + "<p>只接受 .md / .markdown / .txt；其它后缀、空文件会出现在 <code>skipped</code> 里，"
                    + "不影响同批次其它文件入库。")
    public UploadResult upload(@PathVariable(required = false) String kbId,
                               @Parameter(description = "append（追加，默认）或 upsert（按文件名覆盖）",
                                       example = "append")
                               @RequestParam(required = false, defaultValue = "append") String mode,
                               @Parameter(description = "表单字段名固定为 files，可一次选多个")
                               @RequestParam("files") MultipartFile[] files) {
        if (!"append".equalsIgnoreCase(mode) && !"upsert".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("mode 只支持 append（追加，默认）或 upsert（按文件名覆盖），"
                    + "收到：" + mode);
        }
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("没有收到文件。这是 multipart/form-data 接口，"
                    + "表单字段名必须是 files。");
        }

        KnowledgeBase kb = registry.get(kbId);
        boolean upsert = "upsert".equalsIgnoreCase(mode);
        long startedAt = System.currentTimeMillis();

        List<UploadedDoc> uploaded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (MultipartFile file : files) {
            String filename = safeFilename(file.getOriginalFilename());

            if (file.isEmpty()) {
                skipped.add(filename + " —— 空文件");
                continue;
            }
            if (!markdownLike(filename)) {
                skipped.add(filename + " —— 只接受 .md / .markdown / .txt，这个是 " + extensionOf(filename));
                continue;
            }
            String content = readUtf8(file);
            if (content.isBlank()) {
                skipped.add(filename + " —— 去掉空白后没有任何内容");
                continue;
            }

            KnowledgeBase.DocIngest out = kb.ingestDetailed(titleOf(filename, content), content, filename, upsert);

            List<Document> chunks = out.chunks();
            List<ChunkPreview> previews = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                previews.add(previewOf(chunks.get(i), i));
            }
            uploaded.add(new UploadedDoc(out.record().id(), out.record().title(), filename,
                    content.length(), chunks.size(), out.record().ingestedAt(), previews));
        }

        Stats stats = kb.stats();
        return new UploadResult(stats.id(), files.length, uploaded.size(), skipped.size(),
                uploaded.stream().mapToInt(UploadedDoc::chunks).sum(),
                stats.dimensions(), System.currentTimeMillis() - startedAt,
                uploaded, skipped, stats);
    }

    /** 只取文件名部分 —— 客户端可以把 {@code ../../evil.md} 塞进 originalFilename。 */
    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "未命名.md";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static boolean markdownLike(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt");
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "无后缀" : filename.substring(dot);
    }

    /**
     * 标题取法：正文第一个非空行若是一级标题就用它，否则用文件名。
     *
     * <p>两者都合理，但优先级不能反 —— 文件的第一个 {@code #} 标题是作者写给人看的，
     * 而文件名常被改成 {@code 新建文档(1).md} 这种。检索结果里显示「员工手册」
     * 比显示「新建文档」有用得多。
     */
    private static String titleOf(String filename, String content) {
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceFirst("^#+\\s*", "").trim();
                if (!heading.isEmpty()) {
                    return heading;
                }
            }
            break;   // 只看第一个有内容的行
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 按 UTF-8 读，并剥掉 BOM。
     *
     * <p>BOM 不是洁癖问题：Windows 记事本存出来的 UTF-8 文件开头带 {@code \uFEFF}，
     * 不解掉的话第一行变成 {@code "\uFEFF# 员工手册"}，
     * 既不匹配上面的一级标题规则，也会跟着标题一起被嵌进向量。
     */
    private static String readUtf8(MultipartFile file) {
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败：" + file.getOriginalFilename(), e);
        }
    }

    /** 片段预览正文保留的字符数。够看出「在哪断的」，又不至于让响应体爆掉。 */
    private static final int PREVIEW_CHARS = 120;

    private static ChunkPreview previewOf(Document chunk, int fallbackIndex) {
        String text = chunk.getText() == null ? "" : chunk.getText();
        // 序号取 metadata 里的，不是列表下标 —— chunk() 会跳过空白块，两者可能不等
        Object raw = chunk.getMetadata().get(KnowledgeBase.META_CHUNK_INDEX);
        int index = raw instanceof Number n ? n.intValue() : fallbackIndex;
        return new ChunkPreview(index, text.length(),
                text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) + "…" : text);
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
    @Operation(summary = "清空知识库的内容（保留库）",
            description = "把库腾空，但库本身（id / 名称 / 目录）都还在，可以立刻重新入库。"
                    + "<p>这个路径同时支持两种写法："
                    + "<code>DELETE /stage8/kb</code> 清空<b>默认库</b>（单库时代的老接口，行为一如既往），"
                    + "<code>DELETE /stage8/kb?kbId=kb1</code> 清空<b>指定库</b>。"
                    + "<p>为什么要让老接口也能带 kbId：其它接口用路径段表达库标识"
                    + "（<code>/kb/{kbId}/ingest</code>），而清空这条路的老形式是 "
                    + "<code>DELETE /kb</code> —— 它<b>没有多余的路径段可放库标识</b>。"
                    + "如果只认 <code>/kb/{kbId}/clear</code>，客户端里那份「拼 baseUrl + /kb」的代码"
                    + "就只能整个重写。用 query 参数是为了不动已有调用：不传参数的请求行为完全不变。"
                    + "<p>连库一起删用 DELETE /stage8/kb/{kbId}。")
    public Map<String, Object> clearByQuery(
            @Parameter(description = "知识库 id，不传则清空默认库", example = "kb1")
            @RequestParam(required = false) String kbId) {
        return doClear(kbId);
    }

    @DeleteMapping("/kb/{kbId}/clear")
    @Operation(summary = "清空指定知识库的内容（保留库，路径写法）",
            description = "与 <code>DELETE /stage8/kb?kbId={kbId}</code> 完全等价，只是把库标识放进路径。"
                    + "两种写法都留着，是因为「走路径还是走 query」在不同客户端里各有顺手之处，"
                    + "而多支持一个入口的代价只有十行。"
                    + "连库一起删用 DELETE /stage8/kb/{kbId}。")
    public Map<String, Object> clear(@PathVariable String kbId) {
        return doClear(kbId);
    }

    /** 清空的公共实现：query 写法与路径写法共用。 */
    private Map<String, Object> doClear(String kbId) {
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
    // 2.5 文档级增删改查（先拿 docId，再精确操作「一篇」）
    // ==================================================================
    //
    // 为什么需要这一层：上面那节的操作粒度是「整库」——
    // 想改一篇文档，只能 clear() 整库再重灌。
    // 这一节把粒度降到「一篇」，同时把「按来源批量」也补齐。
    //
    // 两种定位方式的分工：
    //   · docId  —— 精确到一篇（PUT / DELETE 单个），从下面的 /docs 列表里取
    //   · source —— 按来源批量（DELETE 一批，或 upsert 覆盖）

    @GetMapping({"/kb/docs", "/kb/{kbId}/docs"})
    @Operation(summary = "列出知识库里的全部文档",
            description = "每项含 docId / 标题 / 来源 / 块数 / 入库时间。"
                    + "<b>docId 是后续查 / 改 / 删这一篇的唯一凭据</b>，先从这里拿。"
                    + "它是不透明字符串（别手工拼、也别假设格式）—— "
                    + "真实项目里换成 MySQL 的业务主键即可，调用方拿到的用法完全一样。")
    public List<DocBrief> listDocs(@PathVariable(required = false) String kbId) {
        return registry.get(kbId).docs();
    }

    @GetMapping({"/kb/docs/{docId}", "/kb/{kbId}/docs/{docId}"})
    @Operation(summary = "查看一篇文档",
            description = "返回元信息 + 它被切成了哪几块（块 id 与序号）。"
                    + "<b>返回里没有片段正文</b> —— 不是漏写，是 {@code VectorStore} 接口"
                    + "根本没有 get(id)，它只有 add / delete / similaritySearch。"
                    + "「向量库是检索引擎，不是能随便读写的数据库」这件事，"
                    + "这个接口的字段就是最直接的体现；要回显原文只能自己另存一份"
                    + "（L2 要补的 MySQL 正文表）。"
                    + "临时想看某篇的正文，可以用检索接口：把标题当 query 搜本库。")
    public DocDetail docDetail(@PathVariable(required = false) String kbId,
                               @Parameter(description = "文档 id，取自 GET /stage8/kb/{kbId}/docs")
                               @PathVariable String docId) {
        return registry.get(kbId).doc(docId);
    }

    @PutMapping({"/kb/docs/{docId}", "/kb/{kbId}/docs/{docId}"})
    @Operation(summary = "覆盖更新一篇文档（docId 保持不变）",
            description = "删掉这篇的全部旧片段，用新正文重新切块入库，<b>docId 不变</b> —— "
                    + "所以对调用方是幂等的：拿同一个 docId 反复 PUT，库里始终只有一篇。"
                    + "<p>title 可以不传（沿用原标题）；<b>content 必传</b>："
                    + "标题是拼进正文一起嵌入的，而正文原文并不由向量库保存，"
                    + "所以「只改标题不重灌正文」在当前实现里做不到。")
    public IngestRecord updateDoc(@PathVariable(required = false) String kbId,
                                  @Parameter(description = "文档 id，取自 GET /stage8/kb/{kbId}/docs")
                                  @PathVariable String docId,
                                  @Valid @RequestBody UpdateDocRequest request) {
        return registry.get(kbId).replaceDoc(docId, request.title(), request.content());
    }

    @DeleteMapping({"/kb/docs/{docId}", "/kb/{kbId}/docs/{docId}"})
    @Operation(summary = "删除一篇文档",
            description = "只删这一篇（连同它的片段），库里其它文档原样不动。"
                    + "整库腾空请用 DELETE /stage8/kb/{kbId}/clear，"
                    + "连库一起删用 DELETE /stage8/kb/{kbId} —— 三个删除接口的粒度依次变大。")
    public Map<String, Object> deleteDoc(@PathVariable(required = false) String kbId,
                                         @Parameter(description = "文档 id，取自 GET /stage8/kb/{kbId}/docs")
                                         @PathVariable String docId) {
        KnowledgeBase kb = registry.get(kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", kb.id());
        result.put("docId", docId);
        result.put("removedChunks", kb.deleteDoc(docId));
        return result;
    }

    @DeleteMapping({"/kb/docs", "/kb/{kbId}/docs"})
    @Operation(summary = "按来源删除文档（一批）",
            description = "删掉某个 source 下的全部文档与片段。"
                    + "典型用途：内置示例语料（source 就是文件名）、"
                    + "或「某批外部推送的数据整个作废」。"
                    + "<b>source 必传</b> —— 不传就等于清空整库，那是另一个接口的语义，"
                    + "不应该在这里悄悄发生。")
    public Map<String, Object> deleteDocsBySource(@PathVariable(required = false) String kbId,
                                                  @Parameter(description = "来源标识，例：01-员工手册.md",
                                                          example = "01-员工手册.md")
                                                  @RequestParam String source) {
        KnowledgeBase kb = registry.get(kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", kb.id());
        result.put("source", source);
        result.put("removedChunks", kb.deleteBySource(source));
        return result;
    }

    // ==================================================================
    // 3. 带检索的问答（经过大模型）
    // ==================================================================

    @GetMapping("/chat")
    @Operation(summary = "RAG 问答（已挂 QuestionAnswerAdvisor）",
            description = "链路：提问 → 该库的向量检索 topK → 把片段拼进 Prompt → DeepSeek 生成。"
                    + "kbId 不传则用默认库。控制台日志里能看到完整 Prompt，"
                    + "也就是「模型到底拿到了什么资料」。"
                    + "<p>库标识走 <code>?kbId=</code>；等价的路径写法是 "
                    + "<code>GET /stage8/kb/{kbId}/chat</code>。"
                    + "这里之所以兼有 query 形式，是因为它的签名<b>先于多知识库存在</b> —— "
                    + "改签名会让所有既有调用方一起失效，而多加一个入口不影响任何人。")
    public String chat(
            @Parameter(description = "用户提问", example = "追光科技的年假是怎么规定的？")
            @RequestParam String message,
            @Parameter(description = "知识库 id，不传用默认库", example = "kb1")
            @RequestParam(required = false) String kbId) {
        return answer(kbId, message);
    }

    @GetMapping("/kb/{kbId}/chat")
    @Operation(summary = "RAG 问答（库标识走路径）",
            description = "与 <code>GET /stage8/chat?kbId={kbId}</code> 完全等价，"
                    + "只是把库标识放进路径 —— 与 /kb/{kbId}/search、/kb/{kbId}/stats 风格统一。")
    public String chatInBase(
            @Parameter(description = "知识库 id", example = "kb1")
            @PathVariable String kbId,
            @Parameter(description = "用户提问", example = "追光科技的年假是怎么规定的？")
            @RequestParam String message) {
        return answer(kbId, message);
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
        return doCompare(kbId, message);
    }

    @GetMapping("/kb/{kbId}/chat/compare")
    @Operation(summary = "对照实验（库标识走路径）",
            description = "与 <code>GET /stage8/chat/compare?kbId={kbId}</code> 完全等价，"
                    + "把库标识放进路径。")
    public CompareResult compareInBase(
            @Parameter(description = "知识库 id", example = "kb1")
            @PathVariable String kbId,
            @Parameter(description = "用户提问", example = "值班补贴多少钱一天？")
            @RequestParam String message) {
        return doCompare(kbId, message);
    }

    /** 问答的公共实现：query 写法与路径写法共用。 */
    private String answer(String kbId, String message) {
        return ragClientFor(registry.get(kbId)).prompt().user(message).call().content();
    }

    /** 对照实验的公共实现：query 写法与路径写法共用。 */
    private CompareResult doCompare(String kbId, String message) {
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

    /**
     * 更新知识库请求。两个字段都是「不传就不改」——
     * 只改其中一个时另一个可以整个省略，不必「先读出来再原样写回」。
     */
    public record UpdateKbRequest(
            @Schema(description = "新的显示名；不传或空白表示保持原名", example = "知识库1（已改名）")
            String name,

            @Schema(description = "新的备注；不传表示保持原备注，传空串则清空", example = "只放 A 线资料")
            String description) {
    }

    /** 覆盖更新一篇文档的请求。 */
    public record UpdateDocRequest(
            @Schema(description = "新标题；不传或空白表示沿用原标题",
                    example = "差旅报销补充说明（2026 修订）")
            String title,

            @NotBlank(message = "content 不能为空")
            @Schema(description = "新的正文。<b>必传</b> —— 正文原文不由向量库保存，无法只改标题",
                    example = "出差住宿标准：一线城市 700 元/晚。")
            String content) {
    }

    /** 单个知识库的详情（切块与检索参数不在这里，那些看 /stats）。 */
    public record KbDetail(String id, String name, String description, String createdAt,
                           String dir, int documents, int chunks, boolean storeFileExists) {
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

    /**
     * 上传入库的结果 —— 一次把「切片 / 向量化 / 存入」三步的产物都摊开。
     *
     * @param filesReceived 收到的文件数（含被跳过的）
     * @param filesIngested 真正入成功了几篇
     * @param filesSkipped  被跳过的数量（原因见 {@code skipped}，逐条说明）
     * @param chunksAdded   本批新增的片段总数
     * @param dimensions    向量维度（本地 bge-small-zh-v1.5 是 512）
     * @param costMillis    本批总耗时，含读取 + 切块 + 嵌入 + 落盘
     * @param stats         入库后的库现状，用来看「一共多少篇 / 多少块」
     */
    public record UploadResult(String knowledgeBase, int filesReceived, int filesIngested, int filesSkipped,
                               int chunksAdded, int dimensions, long costMillis,
                               List<UploadedDoc> uploaded, List<String> skipped, Stats stats) {
    }

    /** 一篇上传文档的切片明细。 */
    public record UploadedDoc(String docId, String title, String source, int chars, int chunks,
                              String ingestedAt, List<ChunkPreview> previews) {
    }

    /** 一个切片的预览：它在原文里的序号、字符数，以及开头那 120 个字。 */
    public record ChunkPreview(int index, int chars, String text) {
    }

    /** 一次回答 + 耗时。 */
    public record Answer(String text, long costMillis) {
    }

    /** 对照实验结果。 */
    public record CompareResult(String knowledgeBase, String question, int hitCount, List<Hit> retrieved,
                                Answer withoutRag, Answer withRag) {
    }
}
