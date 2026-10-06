package com.agentlab.stage8;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.agentlab.stage8.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import io.micrometer.observation.ObservationRegistry;

/**
 * 一个知识库（单个库的完整能力：切块 / 嵌入 / 存储 / 检索 / 落盘）。
 *
 * <h2>它和「多知识库」的关系</h2>
 * 这个类<b>只管一个库</b>，不知道自己有多少个兄弟。多个库的编排
 * （建库、查库、删库、按 id 找实例）全部交给
 * {@link KnowledgeBaseRegistry}。这样切分的好处是：单库的所有状态
 * （向量、清单、目录）都封闭在一个对象里，<b>库与库之间天然没有共享可变状态</b>，
 * 也就不会出现「A 库的清理把 B 库的账本改了」这种串库事故。
 *
 * <h2>每个库 = 一个目录 + 一份向量 + 一份清单</h2>
 * <pre>
 *   &lt;store-root&gt;/
 *   ├── kb-index.tsv          ← 全部库的元数据（注册表维护）
 *   ├── default/
 *   │   ├── store.json        ← 向量本体（SimpleVectorStore 序列化的整个 Map）
 *   │   └── manifest.tsv      ← 账本：哪几篇文档、各切成几块、块 id 是什么
 *   └── kb1/
 *       ├── store.json
 *       └── manifest.tsv
 * </pre>
 * 物理隔离（一库一文件）而不是「一个文件里按 kbId 过滤」，是刻意的取舍，
 * 详见 {@link KnowledgeBaseRegistry} 的类注释。
 *
 * <h2>懒加载：为什么不在启动时把所有库都读进内存</h2>
 * {@link SimpleVectorStore} 是<b>纯内存</b>实现，一个库的向量就对应一份常驻 Map。
 * 如果启动时把 20 个库全 load 进来，就是 20 份常驻内存 + 20 次文件 I/O，
 * 而其中 19 个可能这一整天都没人用。
 * 所以这里用「元数据先恢复、向量等第一次访问再读盘」（{@link #ensureLoaded()}）——
 * 代价是第一次访问某个库时会多几百毫秒，收益是启动快且内存与「实际活跃的库数」成正比。
 *
 * <h2>持久化：清单才是权威，向量文件只是缓存</h2>
 * 这块踩过一次坑，而且是<b>真实的设计缺陷</b>：
 * <pre>
 *   清单  ——每次入库/清空都写盘（它是「唯一可信的账本」）
 *   向量  ——原本只在手动 save 时才写盘
 * </pre>
 * 两者于是处在<b>不同的「代际」</b>：重启后把旧代际的向量灌进内存，
 * 而清单里的 id 一个都对不上 —— 结果 {@code clear()} 按清单 id 去删，
 * 删的是不存在的 id，旧向量永远留着变成<b>孤儿</b>，
 * 表现就是「清空之后还能检索到内容」。
 *
 * <p>两条修正缺一不可：
 * <ol>
 *   <li><b>清单即为真相</b>：清单为空就不加载向量文件（孤儿向量一律不认）；</li>
 *   <li><b>变更即落盘</b>：{@link #ingest} 与 {@link #clear} 之后立刻把
 *       清单和向量一起写盘，让二者永远同一代际。</li>
 * </ol>
 * 通用教训：<b>「谁是权威」必须在设计时讲清楚</b>。
 * 向量库是可以被重建的缓存，清单才是账本 —— 一旦反过来让缓存当真相，
 * 就会出现这种删不掉、也说不清的残留状态。
 *
 * <h2>并发</h2>
 * 公开方法全部 {@code synchronized}（实例锁）。理由是「库」这个粒度的操作天然互斥：
 * 入库要同时改向量和清单，检索要读同一份内存 Map。
 * 锁的粒度是<b>单个库</b>而不是全局 —— 这正是物理隔离带来的另一个好处：
 * 往 kb1 灌文档不会阻塞对 kb2 的检索。
 */
public class KnowledgeBase {

    // ================== metadata 契约 ==================
    // 写成常量而不是散落的字符串字面量：检索回来之后，
    // 这些 key 是你在结果里「认出来源」的唯一线索。
    /** 片段所属文档标题。 */
    public static final String META_TITLE = "title";
    /** 片段来源（内置示例是文件名，接口入库是 "api"）。 */
    public static final String META_SOURCE = "source";
    /** 该片段在原文中的序号，从 0 开始 —— 定位「问题出在哪个切口」。 */
    public static final String META_CHUNK_INDEX = "chunkIndex";
    /** 该文档被切成了多少块 —— 判断切块粒度是否合理。 */
    public static final String META_CHUNK_TOTAL = "chunkTotal";
    /** 入库时间。 */
    public static final String META_INGESTED_AT = "ingestedAt";

    /** 每个库目录内的固定文件名。 */
    public static final String STORE_FILE = "store.json";
    public static final String MANIFEST_FILE = "manifest.tsv";

    /** 内置示例语料的 classpath 位置。 */
    private static final String SAMPLE_PATTERN = "classpath:rag/*.md";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private KnowledgeBaseMeta meta;

    /** 本库的数据目录：&lt;store-root&gt;/&lt;id&gt;/。 */
    private final Path dir;

    private final SimpleVectorStore store;
    private final EmbeddingModel embeddingModel;
    private final RagProperties props;
    private final ResourcePatternResolver resourceResolver;

    /** 切块器：参数全部来自配置，改配置就能重跑一遍「同一份原文切出多少块」。 */
    private final TokenTextSplitter splitter;

    /**
     * 清单：记录「哪篇文档入库过、切了多少块、块 id 是什么」。
     * <p>用 {@link LinkedHashMap} 保持入库顺序，方便对照输出。
     */
    private final Map<String, IngestRecord> manifest = new LinkedHashMap<>();

    /** 是否已从磁盘读过一次。防止「已加载 → 又被 load 覆盖掉内存中的新变更」。 */
    private boolean loaded;

    KnowledgeBase(KnowledgeBaseMeta meta,
                  Path dir,
                  EmbeddingModel embeddingModel,
                  RagProperties props,
                  ResourcePatternResolver resourceResolver,
                  ObservationRegistry observationRegistry) {
        this.meta = meta;
        this.dir = dir;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.resourceResolver = resourceResolver;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(props.getChunkSize())
                .withMinChunkSizeChars(props.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(props.getMinChunkLengthToEmbed())
                .withKeepSeparator(true)
                .build();
        // 显式传 observationRegistry：不传会退化成 NOOP，
        // 那样 Stage 4 学到的 Micrometer 指标就看不到向量检索这段耗时了。
        this.store = SimpleVectorStore.builder(embeddingModel)
                .observationRegistry(observationRegistry)
                .build();
    }

    // ==================================================================
    // 元数据 / 加载
    // ==================================================================

    public KnowledgeBaseMeta meta() {
        return meta;
    }

    /**
     * 就地换掉本库的元数据（改名 / 改备注），由 {@code KnowledgeBaseRegistry.update} 调用。
     *
     * <p>为什么不「把实例丢掉、下次访问重新构造」：{@link SimpleVectorStore} 是内存实现，
     * 实例一丢，那些还没落盘的向量就没了。改个名字把数据搞丢显然不可接受 ——
     * <b>元数据和数据是两回事，改元数据不该触碰数据</b>。
     */
    synchronized void refreshMeta(KnowledgeBaseMeta newMeta) {
        this.meta = newMeta;
    }

    public String id() {
        return meta.id();
    }

    public Path dir() {
        return dir;
    }

    public Path storeFile() {
        return dir.resolve(STORE_FILE);
    }

    public Path manifestFile() {
        return dir.resolve(MANIFEST_FILE);
    }

    /**
     * 懒加载：只在第一次被访问时读盘，之后就是一个纯内存的库。
     *
     * <p>关键点是 {@code loaded} 标志 —— {@code SimpleVectorStore.load(File)}
     * 语义是<b>整体替换</b>内存里的 Map。如果允许重复 load，
     * 一次「入库之后再有人调 load」就会把刚入库的内容悄悄抹掉。
     */
    synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path manifestPath = manifestFile();
        if (!Files.isRegularFile(manifestPath)) {
            log.info("{} 未发现历史清单 {}，知识库从空开始。"
                    + "调用 POST /stage8/kb/{}/ingest-sample 可一键载入内置示例语料。",
                    tag(), manifestPath, meta.id());
            return;
        }

        try {
            for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
                IngestRecord record = parseManifestLine(line);
                if (record != null) {
                    manifest.put(record.id(), record);
                }
            }
        } catch (IOException e) {
            log.warn("{} 清单读取失败（{}），按空知识库继续。", tag(), e.getMessage());
            return;
        }

        Path storePath = storeFile();
        if (manifest.isEmpty()) {
            // 清单是权威：清单里没有的东西一律不认。
            // 硬把向量文件读进来会得到一批「没有账本的孤儿向量」——
            // 它们能检索到、却永远删不掉（clear 是按清单 id 删的）。
            if (Files.isRegularFile(storePath) && storePath.toFile().length() > 10) {
                log.warn("{} 清单为空但向量文件 {} 有内容（{} bytes），"
                                + "已忽略以避免产生孤儿向量。重新入库即可对齐。",
                        tag(), storePath, storePath.toFile().length());
            } else {
                log.info("{} 清单为空，知识库从空开始。", tag());
            }
            return;
        }

        log.info("{} 已从磁盘恢复清单：{} 篇文档 / {} 个片段（{}）",
                tag(), manifest.size(), totalChunks(), manifestPath);

        if (Files.isRegularFile(storePath)) {
            store.load(storePath.toFile());
            log.info("{} 向量已从 {} 载入完成", tag(), storePath);
        } else {
            log.warn("{} 清单有 {} 篇文档，但向量文件 {} 不存在 —— 检索结果会是空的。"
                            + "重新执行一次入库即可对齐。",
                    tag(), manifest.size(), storePath);
        }
    }

    // ==================================================================
    // 入库
    // ==================================================================

    /**
     * 把一段纯文本切块并写入本库（<b>追加</b>语义）。
     *
     * <p>想把某一篇改掉，请用 {@link #replaceDoc} 或 {@link #upsertBySource} ——
     * 把同一篇内容再 {@code ingest} 一次<b>不会覆盖，而是又存了一份</b>。
     * 这是刻意的取舍：接口入库的 {@code source} 默认是 {@code "api"}，
     * 若默认按 source 覆盖，连续入库三篇不同文档就会互相把对方删掉 ——
     * <b>「默认行为」必须对最常见的用法是安全的</b>，而不是对最省事的那次调用安全。
     *
     * @param title   文档标题，会写进每个片段的 metadata（检索后用于「认出处」）
     * @param content 正文
     * @param source  来源标识，内置示例传文件名，接口调用默认 {@code "api"}
     */
    public synchronized IngestRecord ingest(String title, String content, String source) {
        ensureLoaded();
        Objects.requireNonNull(content, "content 不能为 null");
        String safeTitle = normalizeTitle(title);

        IngestRecord record = storeChunks(UUID.randomUUID().toString(), safeTitle, content, source);
        persist();

        log.info("{} 入库完成：「{}」{} 字 → {} 块（来源 {}）",
                tag(), safeTitle, content.length(), record.chunkIds().size(), source);
        return record;
    }

    /**
     * 切块 + 嵌入 + 写清单 —— 三个入口（{@code ingest} / {@code replaceDoc} /
     * {@code upsertBySource}）共用的核心动作。
     *
     * <p>抽出来的关键理由是 <b>docId 由调用方决定</b>：
     * 「覆盖更新」要求 docId 保持不变（同一篇文档换了内容），
     * 只有「新增」才需要新 id。若这里自己生成，覆盖更新就保不住旧 id 了。
     *
     * <p>本方法<b>不落盘</b> —— 落盘时机由调用方决定，
     * 因为「先删旧块再入新块」这种复合操作中途落盘是浪费。
     */
    private IngestRecord storeChunks(String docId, String title, String content, String source) {
        // 标题拼进正文再切块：让每个片段都带上「我属于哪篇」的语义，
        // 否则一块正文里可能完全没出现文档主题词，向量就会漂。
        List<Document> chunks = chunk(title + "\n\n" + content, title, source);
        String now = LocalDateTime.now().format(TS);

        if (chunks.isEmpty()) {
            log.warn("{} 「{}」切块后为空（原文 {} 字），已跳过入库",
                    tag(), title, content.length());
            return new IngestRecord(docId, title, source, List.of(), now);
        }

        store.add(chunks);
        IngestRecord record = new IngestRecord(docId, title, source,
                chunks.stream().map(Document::getId).toList(), now);
        manifest.put(record.id(), record);
        return record;
    }

    private static String normalizeTitle(String title) {
        return (title == null || title.isBlank()) ? "未命名文档" : title.trim();
    }

    /**
     * 载入 classpath 下的内置示例语料（{@code resources/rag/*.md}）。
     *
     * <p>用 {@link TextReader} 而不是自己读流：它会把资源标识写进 metadata，
     * 是 Spring AI 里读取「整个文档」的标准入口
     * （PDF/Word 换 {@code TikaDocumentReader}，拿到的都是同一种
     * {@code List<Document>}，后面的链路完全不用改）。
     */
    public synchronized List<IngestRecord> ingestSampleDocs() {
        ensureLoaded();
        Resource[] resources;
        try {
            resources = resourceResolver.getResources(SAMPLE_PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("读取内置语料失败：" + SAMPLE_PATTERN, e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("classpath 下没有找到示例语料：" + SAMPLE_PATTERN);
        }

        List<IngestRecord> results = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            // 幂等：同一份示例语料重复载入会先删掉旧块，避免向量库被灌成 N 份。
            // 这里用不落盘的版本：紧跟着的 ingest() 自己会落盘，
            // 循环里反复写整份向量文件纯属浪费。
            dropBySource(filename);

            DocumentReader reader = new TextReader(resource);
            String text = reader.get().stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));
            results.add(ingest(filename.replaceFirst("\\.md$", ""), text, filename));
        }
        return results;
    }

    // ==================================================================
    // 文档级增删改查（按 docId 精确定位到「一篇」）
    // ==================================================================
    //
    // 这一节回答的是「库里有哪几篇、我想改其中一篇怎么办」——
    // 上一个版本只有「整库清空」这一种删除粒度，想更新一篇只能全库重来。
    //
    // 定位方式是 docId（不透明字符串，从 docs() 拿），而不是 title 或 source：
    //   · title 可以重复（两篇都叫「会议纪要」很正常）
    //   · source 是「批量分组」语义，接口入库默认都是 "api"，用它定位会一删一大片
    // 所以三者各有分工：docId 精确到一篇，source 用于按来源批量操作。

    /** 列出本库全部文档（元信息，不含片段正文）。 */
    public synchronized List<DocBrief> docs() {
        ensureLoaded();
        return manifest.values().stream()
                .map(r -> new DocBrief(r.id(), r.title(), r.source(), r.chunkIds().size(), r.ingestedAt()))
                .toList();
    }

    /**
     * 查看一篇文档的详情：元信息 + 它被切成了哪几块。
     *
     * <h3>为什么这里拿不到片段正文</h3>
     * 不是偷懒 —— 是 {@code VectorStore} 接口<b>根本没有 get(id)</b>。
     * 它只有 {@code add / delete(List) / delete(Filter) / similaritySearch} 四个方法，
     * 想取出内容就必须给一个 query 去「搜」。
     * 换句话说：<b>向量库是相似度检索引擎，不是可以随便读写的数据库</b>。
     *
     * <p>要回显原文，正路只有一条：<b>自己再把原文存一份</b>
     * （MySQL 存正文 + 向量库存向量，用同一个 documentId 关联）——
     * 这正是 L2 要补的第一件事。这里先如实暴露这个限制。
     */
    public synchronized DocDetail doc(String docId) {
        ensureLoaded();
        IngestRecord record = requireDoc(docId);
        List<ChunkRef> refs = new ArrayList<>(record.chunkIds().size());
        for (int i = 0; i < record.chunkIds().size(); i++) {
            refs.add(new ChunkRef(record.chunkIds().get(i), i));
        }
        return new DocDetail(record.id(), record.title(), record.source(),
                record.ingestedAt(), refs.size(), refs);
    }

    /**
     * 覆盖更新一篇文档：删掉它的全部旧片段，用新正文重新切块入库，
     * <b>docId 保持不变</b> —— 这样「更新」对调用方是幂等的：
     * 拿同一个 docId 反复 PUT，库里始终只有一篇。
     *
     * <p>{@code newTitle} 传 null / 空白表示沿用原标题。
     * <b>正文必须重新传</b>：标题是拼进正文一起嵌入的，
     * 而正文原文并不由向量库保存（见 {@link #doc} 的说明），
     * 所以「只改标题」在当前实现里做不到 —— 这也是上面那条限制的直接后果。
     *
     * @throws NoSuchElementException docId 在本库中不存在
     */
    public synchronized IngestRecord replaceDoc(String docId, String newTitle, String content) {
        ensureLoaded();
        Objects.requireNonNull(content, "content 不能为 null");
        IngestRecord old = requireDoc(docId);
        String title = (newTitle == null || newTitle.isBlank())
                ? old.title() : newTitle.trim();

        // 先删旧块，再入新块。顺序不能反：先入的话，旧块会变成清单里没有的
        // 「孤儿向量」—— 能检索到、却永远删不掉（和类注释里那个代际不一致的坑同源）。
        if (!old.chunkIds().isEmpty()) {
            store.delete(old.chunkIds());
        }
        manifest.remove(docId);

        IngestRecord record = storeChunks(docId, title, content, old.source());
        persist();

        log.info("{} 文档已更新：「{}」（{} 块 → {} 块，docId 不变 {}）",
                tag(), title, old.chunkIds().size(), record.chunkIds().size(), docId);
        return record;
    }

    /** 删除一篇文档（连同它的全部片段），返回删掉的片段数。 */
    public synchronized int deleteDoc(String docId) {
        ensureLoaded();
        IngestRecord record = requireDoc(docId);
        if (!record.chunkIds().isEmpty()) {
            store.delete(record.chunkIds());
        }
        manifest.remove(docId);
        persist();

        log.info("{} 文档已删除：「{}」（{} 块，来源 {}）",
                tag(), record.title(), record.chunkIds().size(), record.source());
        return record.chunkIds().size();
    }

    /**
     * 按来源删除 —— 用于「这一批资料整个不要了」。
     * <p>和 {@link #deleteDoc} 的分工：docId 删一篇，source 删一批。
     */
    public synchronized int deleteBySource(String source) {
        ensureLoaded();
        int removed = dropBySource(source);
        if (removed > 0) {
            persist();
            log.info("{} 已按来源删除：{}（{} 个片段）", tag(), source, removed);
        }
        return removed;
    }

    /**
     * 按来源覆盖入库：同 {@code source} 视为同一篇，先删旧的再入新的。
     *
     * <p>与 {@link #replaceDoc} 的差别只在「怎么定位到要更新谁」：
     * 这里用 source，适合「外部系统定期推送，每次带同一个来源标识」的场景；
     * 上面的写法则适合「先查清单、拿到 docId 再改」的交互式场景。
     *
     * <p>⚠️ 调用方必须保证 source 是唯一的。
     * 若沿用默认的 {@code "api"}，每次 upsert 都会把之前所有 {@code api} 来源的
     * 文档一起删掉 —— 所以接口层把它做成了显式开关而不是默认行为。
     */
    public synchronized IngestRecord upsertBySource(String title, String content, String source) {
        ensureLoaded();
        Objects.requireNonNull(content, "content 不能为 null");
        String safeTitle = normalizeTitle(title);

        int removed = dropBySource(source);
        IngestRecord record = storeChunks(UUID.randomUUID().toString(), safeTitle, content, source);
        persist();

        log.info("{} 覆盖入库：「{}」（先清掉同来源的 {} 块，新入库 {} 块，来源 {}）",
                tag(), safeTitle, removed, record.chunkIds().size(), source);
        return record;
    }

    /** 按 docId 找文档，找不到就抛出带「怎么办」的异常（接口层会映射成 404）。 */
    private IngestRecord requireDoc(String docId) {
        IngestRecord record = docId == null ? null : manifest.get(docId.trim());
        if (record == null) {
            throw new NoSuchElementException("文档不存在：" + docId
                    + "（知识库 " + meta.id() + " 现有 " + manifest.size()
                    + " 篇，可用 GET /stage8/kb/" + meta.id() + "/docs 查看）");
        }
        return record;
    }

    // ==================================================================
    // 检索
    // ==================================================================

    /**
     * 纯向量检索 —— <b>不经过大模型</b>。这是最该先看的接口：
     * 它把「检索质量」和「模型发挥」这两件事解耦了。
     *
     * <p>如果这个接口返回的片段里根本没有答案，那问题一定在入库或检索侧，
     * 再怎么调提示词也没用 —— 这是排查 RAG「答非所问」时的第一步。
     */
    public synchronized List<Document> search(String query, Integer topK, Double similarityThreshold) {
        ensureLoaded();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK == null ? props.getTopK() : topK)
                .similarityThreshold(similarityThreshold == null
                        ? props.getSimilarityThreshold() : similarityThreshold)
                .build();
        // 注意：没有命中时 SimpleVectorStore 返回 null，而不是空列表。
        List<Document> hits = store.similaritySearch(request);
        return hits == null ? List.of() : hits;
    }

    /**
     * 把检索结果转成「可打印」的视图。
     * <p>相似度从 {@link Document#getScore()} 取 —— {@code SimpleVectorStore}
     * 同时还会往 metadata 里塞一个 {@code distance = 1 - score}，
     * 两个方向都有人用，容易混，这里统一只暴露 score。
     */
    public synchronized List<Hit> searchAsHits(String query, Integer topK, Double similarityThreshold) {
        return search(query, topK, similarityThreshold).stream()
                .map(d -> new Hit(
                        d.getId(),
                        String.valueOf(d.getMetadata().getOrDefault(META_TITLE, "?")),
                        String.valueOf(d.getMetadata().getOrDefault(META_SOURCE, "?")),
                        d.getMetadata().get(META_CHUNK_INDEX) instanceof Number n ? n.intValue() : -1,
                        d.getScore(),
                        d.getText()))
                .toList();
    }

    /**
     * 供 {@code QuestionAnswerAdvisor} 使用的向量库句柄。
     * <p>返回前先 {@code ensureLoaded()}，避免「第一次问答时库还没读盘、检索恒为空」。
     */
    public synchronized SimpleVectorStore store() {
        ensureLoaded();
        return store;
    }

    // ==================================================================
    // 统计 / 清理 / 落盘
    // ==================================================================

    public synchronized Stats stats() {
        ensureLoaded();
        return new Stats(
                meta.id(),
                meta.name(),
                meta.description(),
                meta.createdAt(),
                manifest.size(),
                totalChunks(),
                embeddingModel.dimensions(),
                props.getModelDir(),
                props.getTopK(),
                props.getSimilarityThreshold(),
                props.getChunkSize(),
                props.getMinChunkSizeChars(),
                props.getMinChunkLengthToEmbed(),
                storeFile().toString(),
                Files.isRegularFile(storeFile()),
                manifest.values().stream()
                        .map(r -> new DocBrief(r.id(), r.title(), r.source,
                                r.chunkIds().size(), r.ingestedAt()))
                        .toList());
    }

    /** 清空本库的向量与清单（保留库本身）。 */
    public synchronized int clear() {
        ensureLoaded();
        List<String> ids = manifest.values().stream()
                .flatMap(r -> r.chunkIds().stream())
                .toList();
        if (!ids.isEmpty()) {
            store.delete(ids);
        }
        int removed = ids.size();
        manifest.clear();
        persist();
        log.info("{} 知识库已清空，共删除 {} 个片段", tag(), removed);
        return removed;
    }

    /**
     * 把向量库落盘。正常流程不需要手动调 —— {@link #ingest} / {@link #clear}
     * 已经会自动落盘（见类注释里那个「代际不一致」的坑）。
     * 保留这个显式入口，是为了让你能主动确认「磁盘上那份到底是什么」。
     */
    public synchronized Map<String, Object> saveToDisk() {
        ensureLoaded();
        File file = storeFile().toFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建目录：" + parent.getAbsolutePath());
        }
        store.save(file);
        persistManifest();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", meta.id());
        result.put("storePath", file.getAbsolutePath());
        result.put("sizeBytes", file.length());
        result.put("documents", manifest.size());
        result.put("chunks", totalChunks());
        log.info("{} 向量库已落盘：{}（{} bytes，{} 篇 / {} 块）",
                tag(), file.getAbsolutePath(), file.length(), manifest.size(), totalChunks());
        return result;
    }

    /** 从磁盘载入向量库（清单口径以内存中的为准）。 */
    public synchronized Map<String, Object> loadFromDisk() {
        ensureLoaded();
        File file = storeFile().toFile();
        if (!file.isFile()) {
            throw new IllegalStateException("向量库文件不存在：" + file.getAbsolutePath()
                    + "，请先调用 /stage8/kb/" + meta.id() + "/save");
        }
        store.load(file);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBase", meta.id());
        result.put("storePath", file.getAbsolutePath());
        result.put("documents", manifest.size());
        result.put("chunks", totalChunks());
        log.info("{} 向量库已从 {} 载入（清单口径 {} 篇 / {} 块）",
                tag(), file.getAbsolutePath(), manifest.size(), totalChunks());
        return result;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /** 日志前缀：多库混在一起时，没有这个根本分不清是哪条日志。 */
    private String tag() {
        return "Stage 8[" + meta.id() + "] ·";
    }

    private List<Document> chunk(String text, String title, String source) {
        List<Document> raw = splitter.apply(List.of(new Document(text)));
        List<Document> chunks = new ArrayList<>(raw.size());
        int total = raw.size();
        for (int i = 0; i < total; i++) {
            String chunkText = raw.get(i).getText();
            if (chunkText == null || chunkText.isBlank()) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(META_TITLE, title);
            meta.put(META_SOURCE, source);
            meta.put(META_CHUNK_INDEX, i);
            meta.put(META_CHUNK_TOTAL, total);
            meta.put(META_INGESTED_AT, LocalDateTime.now().format(TS));
            chunks.add(Document.builder().text(chunkText).metadata(meta).build());
        }
        return chunks;
    }

    /**
     * 按来源删除片段（<b>不落盘</b>），返回删掉的片段数。
     *
     * <p>落盘时机留给调用方：{@link #ingestSampleDocs} 在循环里反复调用它，
     * 由循环中的 {@link #ingest} 顺带落盘；对外的 {@link #deleteBySource}
     * 与 {@link #upsertBySource} 则自己负责写盘。
     * 「删哪些」在这里决定，「什么时候写」由调用方决定 —— 职责不分开就会重复写文件。
     */
    private int dropBySource(String source) {
        List<String> stale = manifest.values().stream()
                .filter(r -> source.equals(r.source()))
                .flatMap(r -> r.chunkIds().stream())
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }
        store.delete(stale);
        manifest.values().removeIf(r -> source.equals(r.source()));
        log.info("{} 来源 {} 已存在，先清掉旧的 {} 个片段", tag(), source, stale.size());
        return stale.size();
    }

    private int totalChunks() {
        return manifest.values().stream().mapToInt(r -> r.chunkIds().size()).sum();
    }

    /**
     * 变更后一次性写盘（向量 + 清单），保证两者永远同一代际。
     *
     * <p>这里用「写入量换一致性」：向量文件约 166KB / 11 块，每次入库都重写一遍。
     * 对千级片段的规模完全没问题；真到几十万块时，正确做法就不是
     * 「反复全量写文件」了，而是换成数据库或专业向量库
     * （它们本来就负责持久化，不需要你在应用层操心）。
     */
    private void persist() {
        File storeFile = storeFile().toFile();
        File parent = storeFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try {
            store.save(storeFile);
        } catch (RuntimeException e) {
            log.warn("{} 向量落盘失败（不影响本次检索）：{}", tag(), e.getMessage());
        }
        persistManifest();
    }

    private void persistManifest() {
        Path path = manifestFile();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<String> lines = manifest.values().stream()
                    .map(KnowledgeBase::toManifestLine)
                    .toList();
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 清单写失败不该让业务失败：向量已经进库了，清单只是加速恢复的辅助。
            log.warn("{} 清单落盘失败（不影响本次检索）：{}", tag(), e.getMessage());
        }
    }

    /**
     * 清单一行 = 一篇文档。格式（制表符分隔）：
     * <pre>
     *   id \t title \t source \t ingestedAt \t chunkId1,chunkId2,...
     * </pre>
     * 制表符与换行在标题里会被替换成空格 —— 否则一行会被拆成两行、清单直接读崩。
     * 这类「分隔符转义」是自定格式时必须处理的细节，也是它相比 JSON 的代价。
     */
    private static String toManifestLine(IngestRecord r) {
        return String.join("\t",
                sanitize(r.id()),
                sanitize(r.title()),
                sanitize(r.source()),
                sanitize(r.ingestedAt()),
                String.join(",", r.chunkIds()));
    }

    private static IngestRecord parseManifestLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 5) {
            return null;
        }
        List<String> chunkIds = parts[4].isBlank()
                ? List.of()
                : List.of(parts[4].split(","));
        return new IngestRecord(parts[0], parts[1], parts[2], chunkIds, parts[3]);
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ==================================================================
    // 对外返回的视图对象
    // ==================================================================

    /** 一条入库记录 = 一篇源文档。 */
    public record IngestRecord(String id, String title, String source,
                               List<String> chunkIds, String ingestedAt) {
    }

    /** 一条检索命中。 */
    public record Hit(String id, String title, String source, int chunkIndex,
                      Double score, String text) {
    }

    /** 知识库现状。 */
    public record Stats(String id, String name, String description, String createdAt,
                        int documents, int chunks, int dimensions, String modelDir,
                        int topK, double similarityThreshold,
                        int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
                        String storePath, boolean storeFileExists, List<DocBrief> catalog) {
    }

    /**
     * 清单里一篇文档的摘要。
     *
     * @param docId 后续「查 / 改 / 删这一篇」的唯一凭据（不透明字符串，直接从本对象取）
     */
    public record DocBrief(String docId, String title, String source, int chunks, String ingestedAt) {
    }

    /**
     * 一篇文档的详情。
     *
     * <p>{@code chunks} 是片段数，{@code chunkRefs} 是每块的 id 与序号 ——
     * 序号用来对照「问题落在第几个切口」，是调切块参数时最直接的观测点。
     * <b>这里没有片段正文</b>，原因见 {@link KnowledgeBase#doc} 的说明。
     */
    public record DocDetail(String docId, String title, String source, String ingestedAt,
                            int chunks, List<ChunkRef> chunkRefs) {
    }

    /** 一个片段的引用：id + 它在本篇文档中的序号。 */
    public record ChunkRef(String id, int index) {
    }
}
