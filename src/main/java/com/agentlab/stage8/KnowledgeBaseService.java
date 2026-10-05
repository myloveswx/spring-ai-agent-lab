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
import java.util.stream.Collectors;

import com.agentlab.stage8.config.RagProperties;
import jakarta.annotation.PostConstruct;
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
import org.springframework.stereotype.Service;

/**
 * Stage 8 —— 知识库服务：把「切块 + 嵌入 + 存储 + 检索」这四件事收在一处。
 *
 * <h2>为什么要有这一层，而不是让 Controller 直接调 VectorStore</h2>
 * 因为 RAG 的「入库」和「检索」是两条<b>不对称</b>的链路：
 * <pre>
 *   入库（低频、可慢）：原文 ──切块──▶ chunks ──嵌入──▶ 带 metadata 的向量 → VectorStore
 *   检索（高频、要快）：query ──嵌入──▶ 向量 ──相似度──▶ topK Document
 * </pre>
 * 入库时多花的每一点心思（怎么切、带什么 metadata）都会在检索时兑现；
 * 而检索侧能调的东西其实很少（topK、阈值、要不要过滤）。
 * 把两层分开，你才能明确「这次效果变好，是我改了入库还是改了检索」。
 *
 * <h2>一个容易被忽略的坑：向量库不管「清单」</h2>
 * {@code VectorStore} 接口只有 {@code add / delete / similaritySearch} 四个方法，
 * <b>没有 count()、没有 list()、没有按 source 分组统计</b>。
 * 也就是说：向量存进去了，但「我一共存了哪几篇、每篇几块」这件事，
 * 向量库不负责回答 —— 必须自己维护一份清单（本类的 manifest 文件就是干这个的）。
 *
 * <p>这不是 Spring AI 的设计缺陷，而是所有向量数据库的共性：
 * 它们定位是「相似度检索引擎」，不是「业务数据库」。
 * 真实项目里的标准做法是「MySQL 存业务元数据 + 向量库存向量」，
 * 用同一个 documentId 关联 —— 这也是把 L1 升级到 L2 时最该先做的一件事。
 *
 * <h2>清单为什么用 TSV 而不是 JSON</h2>
 * 这里踩过一次坑：本来想注入 {@code ObjectMapper} 来读写清单，
 * 结果 <b>Spring Boot 4 用的是 Jackson 3（包名 {@code tools.jackson}），
 * 容器里根本没有 {@code com.fasterxml.jackson.databind.ObjectMapper} 这个 Bean</b>
 * （Jackson 2 只是被 springdoc 之类顺带带进来的库，不是容器管理的 Bean），
 * 启动时直接 `required a bean of type 'ObjectMapper' that could not be found`。
 *
 * <p>于是改用最朴素的 TSV：一份「每行一篇文档」的纯文本。
 * 清单是本应用内部的中间产物、格式完全自控，用不着序列化框架；
 * 少一个依赖就少一类版本兼容问题 —— 这也是 L1 阶段该有的取舍。
 * 想要 JSON 的话，正确的注入类型是 {@code tools.jackson.databind.ObjectMapper}。
 *
 * <h2>持久化：清单才是权威，向量文件只是缓存</h2>
 * 这块也踩过一次坑，而且是<b>真实的设计缺陷</b>，不是测试写法问题：
 * <pre>
 *   清单  ——每次入库/清空都写盘（因为它是「唯一可信的账本」）
 *   向量  ——原本只在手动调用 /kb/save 时才写盘
 * </pre>
 * 两者于是处在<b>不同的「代际」</b>：进程重启后，清单记的是第 N 代，
 * 向量文件里躺着第 N-1 代。启动时把旧代际的向量灌进内存，
 * 而清单里的 id 一个都对不上 —— 结果 {@code clear()} 按清单 id 去删，
 * 删的是「不存在的 id」，旧向量永远留在库里变成<b>孤儿</b>，
 * 表现就是「清空之后还能检索到内容」。
 *
 * <p>两条修正，缺一不可：
 * <ol>
 *   <li><b>清单即为真相</b>：清单为空就不加载向量文件（孤儿向量一律不认）；
 *   <li><b>变更即落盘</b>：{@link #ingest} 与 {@link #clear} 之后立刻
 *       把清单和向量一起写盘，让二者永远同一代际。
 * </ol>
 * 顺带一个通用的教训：<b>「谁是权威」这件事必须在设计时讲清楚</b>。
 * 这里向量库是个「可以被重建的缓存」，清单才是账本 ——
 * 一旦反过来（让缓存当真相），就会出现上面这种删不掉、也说不清的残留状态。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

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

    /** 内置示例语料的 classpath 位置。 */
    private static final String SAMPLE_PATTERN = "classpath:rag/*.md";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SimpleVectorStore vectorStore;
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

    public KnowledgeBaseService(SimpleVectorStore vectorStore,
                               EmbeddingModel embeddingModel,
                               RagProperties props,
                               ResourcePatternResolver resourceResolver) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.resourceResolver = resourceResolver;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(props.getChunkSize())
                .withMinChunkSizeChars(props.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(props.getMinChunkLengthToEmbed())
                .withKeepSeparator(true)
                .build();
    }

    /**
     * 启动时尝试从磁盘恢复。
     *
     * <p>{@code SimpleVectorStore} 是纯内存实现，进程一停向量就没了。
     * 它提供了 {@code save(File)} / {@code load(File)}，
     * 这就是「不想重跑一遍嵌入」时最低成本的持久化方案（本质是把整个 Map 序列化成 JSON）。
     */
    @PostConstruct
    void restoreFromDisk() {
        Path manifestPath = manifestPath();
        if (!Files.isRegularFile(manifestPath)) {
            log.info("Stage 8 · 未发现历史清单 {}，知识库从空开始。"
                    + "调用 GET /stage8/kb/ingest-sample 可一键载入内置示例语料。", manifestPath);
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
            log.warn("Stage 8 · 清单读取失败（{}），按空知识库继续启动。", e.getMessage());
            return;
        }

        File storeFile = new File(props.getStorePath());
        if (manifest.isEmpty()) {
            // 清单是权威：清单里没有的东西一律不认。
            // 这里如果硬把向量文件读进来，就会得到一批「没有账本的孤儿向量」——
            // 它们能检索到、却永远删不掉（clear 是按清单 id 删的）。
            // 空库属于正常状态（清空之后落盘的就是空 store），所以只对「非空但不认」报警。
            if (storeFile.isFile() && storeFile.length() > 10) {
                log.warn("Stage 8 · 清单为空但向量文件 {} 有内容（{} bytes），"
                                + "已忽略以避免产生孤儿向量。重新入库即可对齐。",
                        storeFile.getAbsolutePath(), storeFile.length());
            } else {
                log.info("Stage 8 · 清单为空，知识库从空开始。"
                        + "调用 GET /stage8/kb/ingest-sample 可一键载入内置示例语料。");
            }
            return;
        }

        log.info("Stage 8 · 已从磁盘恢复清单：{} 篇文档 / {} 个片段（{}）",
                manifest.size(), totalChunks(), manifestPath);

        if (storeFile.isFile()) {
            vectorStore.load(storeFile);
            log.info("Stage 8 · 向量已从 {} 载入完成", storeFile.getAbsolutePath());
        } else {
            // 清单有、向量没有：账本记得 11 块，但库里是空的。
            // 这种不一致必须响亮地说出来，否则「检索不到」会被误判成检索算法有问题。
            log.warn("Stage 8 · 清单有 {} 篇文档，但向量文件 {} 不存在 —— "
                            + "检索结果会是空的。重新执行一次入库即可对齐。",
                    manifest.size(), storeFile.getAbsolutePath());
        }
    }

    // ==================================================================
    // 入库
    // ==================================================================

    /**
     * 把一段纯文本切块并写入向量库。
     *
     * @param title   文档标题，会写进每个片段的 metadata（检索后用于「认出处」）
     * @param content 正文
     * @param source  来源标识，内置示例传文件名，接口调用传 "api"
     */
    public IngestRecord ingest(String title, String content, String source) {
        Objects.requireNonNull(content, "content 不能为 null");
        String safeTitle = (title == null || title.isBlank()) ? "未命名文档" : title.trim();

        // 标题拼进正文再切块：让每个片段都带上「我属于哪篇」的语义，
        // 否则一块正文里可能完全没出现文档主题词，向量就会漂。
        String fullText = safeTitle + "\n\n" + content;

        List<Document> chunks = chunk(fullText, safeTitle, source);
        if (chunks.isEmpty()) {
            log.warn("Stage 8 · 「{}」切块后为空（原文 {} 字），已跳过入库", safeTitle, content.length());
            return new IngestRecord(java.util.UUID.randomUUID().toString(), safeTitle, source,
                    List.of(), LocalDateTime.now().format(TS));
        }

        vectorStore.add(chunks);

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

        log.info("Stage 8 · 入库完成：「{}」{} 字 → {} 块（来源 {}）",
                safeTitle, content.length(), chunks.size(), source);
        return record;
    }

    /**
     * 载入 classpath 下的内置示例语料（{@code resources/rag/*.md}）。
     *
     * <p>用 {@link TextReader} 而不是自己读流：它会把资源标识写进 metadata，
     * 是 Spring AI 里读取「整个文档」的标准入口
     * （PDF/Word 换 {@code TikaDocumentReader}、JSON 换 {@code JsonReader}，
     * 拿到的都是同一种 {@code List<Document>}，后面的链路完全不用改）。
     */
    public List<IngestRecord> ingestSampleDocs() {
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
     * 纯向量检索 —— <b>不经过大模型</b>。这是 L1 最该先看的接口：
     * 它把「检索质量」和「模型发挥」这两件事解耦了。
     *
     * <p>如果这个接口返回的片段里根本没有答案，那问题一定在入库或检索侧，
     * 再怎么调提示词也没用 —— 这是排查 RAG「答非所问」时的第一步。
     */
    public List<Document> search(String query, Integer topK, Double similarityThreshold) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK == null ? props.getTopK() : topK)
                .similarityThreshold(similarityThreshold == null
                        ? props.getSimilarityThreshold() : similarityThreshold)
                .build();
        // 注意：没有命中时 SimpleVectorStore 返回 null，而不是空列表。
        List<Document> hits = vectorStore.similaritySearch(request);
        return hits == null ? List.of() : hits;
    }

    /**
     * 把检索结果转成「可打印」的视图。
     * <p>相似度从 {@link Document#getScore()} 取 —— {@code SimpleVectorStore}
     * 同时还会往 metadata 里塞一个 {@code distance = 1 - score}，
     * 两个方向都有人用，容易混，这里统一只暴露 score。
     */
    public List<Hit> searchAsHits(String query, Integer topK, Double similarityThreshold) {
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

    // ==================================================================
    // 统计 / 清理 / 落盘
    // ==================================================================

    public Stats stats() {
        return new Stats(
                manifest.size(),
                totalChunks(),
                embeddingModel.dimensions(),
                props.getModelDir(),
                props.getTopK(),
                props.getSimilarityThreshold(),
                props.getChunkSize(),
                props.getMinChunkSizeChars(),
                props.getMinChunkLengthToEmbed(),
                props.getStorePath(),
                new File(props.getStorePath()).isFile(),
                manifest.values().stream()
                        .map(r -> new DocBrief(r.title(), r.source(), r.chunkIds().size(), r.ingestedAt()))
                        .toList());
    }

    /** 清空向量库与清单。 */
    public int clear() {
        List<String> ids = manifest.values().stream()
                .flatMap(r -> r.chunkIds().stream())
                .toList();
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
        int removed = ids.size();
        manifest.clear();
        persist();
        log.info("Stage 8 · 知识库已清空，共删除 {} 个片段", removed);
        return removed;
    }

    /**
     * 把向量库落盘。正常流程不需要手动调 —— {@link #ingest} / {@link #clear}
     * 已经会自动落盘（见类注释里那个「代际不一致」的坑）。
     * 保留这个显式入口，是为了让你能主动确认「磁盘上那份到底是什么」。
     */
    public Map<String, Object> saveToDisk() {
        File file = new File(props.getStorePath());
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建目录：" + parent.getAbsolutePath());
        }
        vectorStore.save(file);
        persistManifest();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storePath", file.getAbsolutePath());
        result.put("sizeBytes", file.length());
        result.put("documents", manifest.size());
        result.put("chunks", totalChunks());
        log.info("Stage 8 · 向量库已落盘：{}（{} bytes，{} 篇 / {} 块）",
                file.getAbsolutePath(), file.length(), manifest.size(), totalChunks());
        return result;
    }

    /** 从磁盘载入向量库。 */
    public Map<String, Object> loadFromDisk() {
        File file = new File(props.getStorePath());
        if (!file.isFile()) {
            throw new IllegalStateException("向量库文件不存在：" + file.getAbsolutePath()
                    + "，请先调用 /stage8/kb/save");
        }
        vectorStore.load(file);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storePath", file.getAbsolutePath());
        result.put("documents", manifest.size());
        result.put("chunks", totalChunks());
        log.info("Stage 8 · 向量库已从 {} 载入（清单口径 {} 篇 / {} 块）",
                file.getAbsolutePath(), manifest.size(), totalChunks());
        return result;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

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
        vectorStore.delete(stale);
        manifest.values().removeIf(r -> source.equals(r.source()));
        log.info("Stage 8 · 来源 {} 已存在，先清掉旧的 {} 个片段再重新入库", source, stale.size());
    }

    private int totalChunks() {
        return manifest.values().stream().mapToInt(r -> r.chunkIds().size()).sum();
    }

    private Path manifestPath() {
        return Path.of(props.getStorePath() + ".manifest.tsv");
    }

    /**
     * 变更后一次性写盘（向量 + 清单），保证两者永远同一代际。
     *
     * <p>这里用「写入量换一致性」：向量文件约 166KB / 11 块，每次入库都重写一遍。
     * 对 L1 这种千级片段的规模完全没问题；真到几十万块时，
     * 正确做法就不是「反复全量写文件」了，而是换成数据库或专业向量库
     * （它们本来就负责持久化，不需要你在应用层操心）。
     */
    private void persist() {
        File storeFile = new File(props.getStorePath());
        File parent = storeFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try {
            vectorStore.save(storeFile);
        } catch (RuntimeException e) {
            log.warn("Stage 8 · 向量落盘失败（不影响本次检索）：{}", e.getMessage());
        }
        persistManifest();
    }

    private void persistManifest() {
        Path path = manifestPath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<String> lines = manifest.values().stream()
                    .map(KnowledgeBaseService::toManifestLine)
                    .toList();
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 清单写失败不该让业务失败：向量已经进库了，清单只是加速恢复的辅助。
            log.warn("Stage 8 · 清单落盘失败（不影响本次检索）：{}", e.getMessage());
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
    public record Stats(int documents, int chunks, int dimensions, String modelDir,
                        int topK, double similarityThreshold,
                        int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
                        String storePath, boolean storeFileExists, List<DocBrief> catalog) {
    }

    /** 清单里一篇文档的摘要。 */
    public record DocBrief(String title, String source, int chunks, String ingestedAt) {
    }
}
