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

    private final KnowledgeBaseMeta meta;

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
     * 把一段纯文本切块并写入本库。
     *
     * @param title   文档标题，会写进每个片段的 metadata（检索后用于「认出处」）
     * @param content 正文
     * @param source  来源标识，内置示例传文件名，接口调用传 "api"
     */
    public synchronized IngestRecord ingest(String title, String content, String source) {
        ensureLoaded();
        Objects.requireNonNull(content, "content 不能为 null");
        String safeTitle = (title == null || title.isBlank()) ? "未命名文档" : title.trim();

        // 标题拼进正文再切块：让每个片段都带上「我属于哪篇」的语义，
        // 否则一块正文里可能完全没出现文档主题词，向量就会漂。
        String fullText = safeTitle + "\n\n" + content;

        List<Document> chunks = chunk(fullText, safeTitle, source);
        if (chunks.isEmpty()) {
            log.warn("{} 「{}」切块后为空（原文 {} 字），已跳过入库",
                    tag(), safeTitle, content.length());
            return new IngestRecord(UUID.randomUUID().toString(), safeTitle, source,
                    List.of(), LocalDateTime.now().format(TS));
        }

        store.add(chunks);

        IngestRecord record = new IngestRecord(
                chunks.get(0).getId(),
                safeTitle,
                source,
                chunks.stream().map(Document::getId).toList(),
                LocalDateTime.now().format(TS));
        // 用首块 id 当文档 key 是够用的近似；真实项目里应该用业务主键
        // （比如 MySQL 的 document_id），这里是 L1，先不引业务表。
        manifest.put(record.id(), record);
        persist();

        log.info("{} 入库完成：「{}」{} 字 → {} 块（来源 {}）",
                tag(), safeTitle, content.length(), chunks.size(), source);
        return record;
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
            // 幂等：同一份示例语料重复载入会先删掉旧块，避免向量库被灌成 N 份
            removeBySource(filename);

            DocumentReader reader = new TextReader(resource);
            String text = reader.get().stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));
            results.add(ingest(filename.replaceFirst("\\.md$", ""), text, filename));
        }
        return results;
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
                        .map(r -> new DocBrief(r.title(), r.source(), r.chunkIds().size(), r.ingestedAt()))
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

    /** 按来源删除已入库的片段 —— 重复载入同一份语料时保证幂等。 */
    private void removeBySource(String source) {
        List<String> stale = manifest.values().stream()
                .filter(r -> source.equals(r.source()))
                .flatMap(r -> r.chunkIds().stream())
                .toList();
        if (stale.isEmpty()) {
            return;
        }
        store.delete(stale);
        manifest.values().removeIf(r -> source.equals(r.source()));
        log.info("{} 来源 {} 已存在，先清掉旧的 {} 个片段再重新入库", tag(), source, stale.size());
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

    /** 清单里一篇文档的摘要。 */
    public record DocBrief(String title, String source, int chunks, String ingestedAt) {
    }
}
