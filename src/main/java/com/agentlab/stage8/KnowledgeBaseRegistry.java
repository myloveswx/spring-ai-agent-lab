package com.agentlab.stage8;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.agentlab.stage8.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import io.micrometer.observation.ObservationRegistry;

/**
 * 多知识库的注册表 —— 管「有哪些库」，{@link KnowledgeBase} 管「一个库怎么干活」。
 *
 * <h2>两种多知识库方案，为什么选「一库一份存储」</h2>
 * 要让「知识库1 / 知识库2」互不干扰，本质上有两条路：
 *
 * <table border="1">
 *   <caption>多知识库的两种隔离方式</caption>
 *   <tr>
 *     <th></th><th>A · 物理隔离（本实现）</th><th>B · 逻辑隔离</th>
 *   </tr>
 *   <tr>
 *     <td>做法</td>
 *     <td>每个库一个 {@code SimpleVectorStore} 实例、一个目录、一份落盘文件</td>
 *     <td>全部库共用一个 store，片段 metadata 里打 {@code kbId}，
 *         检索时用 {@code SearchRequest.filterExpression("kbId == 'kb1'")}</td>
 *   </tr>
 *   <tr>
 *     <td>创建/删除库</td>
 *     <td>建目录 / 删目录，物理上彻底隔开</td>
 *     <td>只改元数据，无需动存储</td>
 *   </tr>
 *   <tr>
 *     <td>内存占用</td>
 *     <td>随「活跃库数」线性增长（配合懒加载可控）</td>
 *     <td>一份，与库数无关</td>
 *   </tr>
 *   <tr>
 *     <td>检索开销</td>
 *     <td>只在<b>本库</b>的向量里算相似度</td>
 *     <td>若实现是「全量算完再过滤」，库越多、单库越小，浪费越大</td>
 *   </tr>
 *   <tr>
 *     <td>并发</td>
 *     <td>锁按库隔离：往 kb1 灌数据不阻塞 kb2 检索</td>
 *     <td>同一个 store，写操作容易互相阻塞</td>
 *   </tr>
 *   <tr>
 *     <td>误伤风险</td>
 *     <td>几乎为零：A 库的操作碰不到 B 库的文件</td>
 *     <td>filter 写错一个字符，就可能读到别的库的内容</td>
 *   </tr>
 * </table>
 *
 * <p>本实现选 A，理由很直白：<b>「知识库」这个词在用户脑中的语义就是「一个独立的东西」</b>
 * —— 建一个、删一个、各自备份，用文件系统来表达最自然，
 * 也最好观察（{@code ls} 一眼就能看到有几个库、每个库多大）。
 *
 * <p>但必须说清楚：<b>生产环境更常用的是 B</b>。原因是专业向量库（Milvus / Qdrant /
 * PGVector…）的 {@code filterExpression} 是<b>走索引</b>的（先把候选集收敛到
 * 这个小分区，再算相似度），不是内存里逐条判断，所以「一张表存所有租户、
 * 用 metadata 过滤」反而是标准做法，运维成本也低得多。
 * 换句话说：<b>A 是「用文件系统做隔离」的教学版，B 是「用数据库做隔离」的生产版</b>。
 * 真要在这条路上往下走，把 {@code VectorStore} 换成自研 MySQL 实现时，
 * 顺手把 kbId 做成一列（或一个分区）就是 B 方案 —— 接口不用改，只改实现。
 *
 * <h2>元数据也是应用自己维护的</h2>
 * {@code VectorStore} 接口既不给「库列表」，也不给「库的名字」。
 * 所以这里用一份 {@code kb-index.tsv} 当账本，记录每个库的
 * id / 名称 / 备注 / 创建时间 —— 和单库时代那份 manifest 是同一个思路：
 * <b>向量库存向量，业务元数据由应用自己拿账本记</b>。
 */
@Service
@ConditionalOnProperty(prefix = "agentlab.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeBaseRegistry {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRegistry.class);

    /** 内置默认库的 id。老的 {@code /stage8/kb/xxx} 端点（不带 kbId）都指向它。 */
    public static final String DEFAULT_ID = "default";

    /** 库清单文件名。 */
    private static final String INDEX_FILE = "kb-index.tsv";

    /** 单库时代遗留的落盘文件名（仅用于一次性迁移）。 */
    private static final String LEGACY_STORE_FILE = "stage8-store.json";

    /**
     * 库 id 的合法格式。
     * <p>之所以<b>不允许中文</b>：id 同时是磁盘目录名，而中文目录名在
     * Git Bash / curl / 日志 这几个环节都会引入编码风险
     * （本机就踩过「中文经 Git Bash 传给原生 exe 时被转成 GBK」的坑）。
     * 中文想用就写在 {@code name} 里 —— 它只用于展示，不参与存储路径与 URL。
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    /**
     * 保留字：这些名字在 {@code /stage8/kb/...} 下已经被当成固定路径段用了，
     * 拿它们当库 id 会造成「<b>永远访问不到这个库</b>」。
     *
     * <p>具体冲突长这样：{@code GET /stage8/kb/search} 到底是想「列出 search 这个库」
     * 还是「在默认库里检索」？Spring 的路径匹配会优先选字面量段（也就是检索接口），
     * 于是那个库变成了一个<b>建得出来、却谁也碰不到</b>的幽灵。</p>
     *
     * <p>这类「名字与路由撞车」的问题在真实系统里很常见（想想给用户起名 {@code admin} 的后果），
     * 处理办法就是一张显式的黑名单 —— <b>让它在创建时就失败，而不是在使用时诡异</b>。
     */
    private static final Set<String> RESERVED_IDS = Set.of(
            "kb", "search", "stats", "docs", "ingest", "ingest-sample",
            "save", "load", "chat", "compare", "clear");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmbeddingModel embeddingModel;
    private final RagProperties props;
    private final ResourcePatternResolver resourceResolver;
    private final ObservationRegistry observationRegistry;

    private final Path root;
    private final Path indexFile;

    /** 全部库的元数据（含尚未加载向量的库）。 */
    private final Map<String, KnowledgeBaseMeta> metas = new ConcurrentHashMap<>();

    /** 已实例化的库。懒加载：只有第一次被访问时才读盘。 */
    private final Map<String, KnowledgeBase> bases = new ConcurrentHashMap<>();

    public KnowledgeBaseRegistry(EmbeddingModel embeddingModel,
                                 RagProperties props,
                                 ResourcePatternResolver resourceResolver,
                                 ObjectProvider<ObservationRegistry> observationRegistry) {
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.resourceResolver = resourceResolver;
        this.observationRegistry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
        this.root = Path.of(props.getStoreRoot());
        this.indexFile = root.resolve(INDEX_FILE);
    }

    /**
     * 启动时恢复「有哪些库」。
     *
     * <p>注意这里<b>只读元数据、不碰向量</b> —— 向量要等某个库被真正用到时才读
     * （见 {@link KnowledgeBase#ensureLoaded()}）。20 个库的元数据读起来是几毫秒，
     * 20 个库的向量读起来可能是几秒 + 几百 MB 内存，差别就是在这里省下来的。
     */
    @PostConstruct
    void restoreIndex() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建知识库根目录：" + root, e);
        }

        readIndex();
        migrateLegacySingleStore();

        if (!metas.containsKey(DEFAULT_ID)) {
            // 默认库永远存在：老的接口（/stage8/kb/ingest 等不带 kbId 的那批）
            // 都指向它，删掉它会让那些接口全部 404。
            metas.put(DEFAULT_ID, new KnowledgeBaseMeta(DEFAULT_ID, "默认知识库",
                    "内置示例语料与不带 kbId 的接口都落在这里", LocalDateTime.now().format(TS)));
            persistIndex();
            log.info("Stage 8 · 初始化默认知识库（{}），共 {} 个知识库", DEFAULT_ID, metas.size());
        } else {
            log.info("Stage 8 · 已恢复知识库清单：{} 个（{}）｜根目录 {}",
                    metas.size(), String.join(", ", metas.keySet()), root);
        }
    }

    // ==================================================================
    // 对外能力
    // ==================================================================

    /** 全部知识库（按「默认库优先 → 创建时间 → id」排序）。 */
    public List<BaseBrief> list() {
        List<KnowledgeBaseMeta> sorted = new ArrayList<>(metas.values());
        sorted.sort(Comparator
                .comparing((KnowledgeBaseMeta m) -> !DEFAULT_ID.equals(m.id()))
                .thenComparing(KnowledgeBaseMeta::createdAt)
                .thenComparing(KnowledgeBaseMeta::id));

        return sorted.stream().map(m -> {
            KnowledgeBase kb = bases.get(m.id());
            // 只对「已经加载过的库」报数字。为了列个表而把所有库的向量读进内存
            // 是一种典型的「查询接口产生副作用」，这里刻意不这么做。
            return kb == null
                    ? new BaseBrief(m.id(), m.name(), m.description(), m.createdAt(), false, -1, -1)
                    : new BaseBrief(m.id(), m.name(), m.description(), m.createdAt(), true,
                    kb.stats().documents(), kb.stats().chunks());
        }).toList();
    }

    /**
     * 创建一个知识库。
     *
     * @param id   库标识，只允许 {@code [A-Za-z0-9_-]}，同时作为磁盘目录名
     * @param name 显示名（可以是中文，例：知识库1）
     * @param description 备注，可空
     */
    public KnowledgeBase create(String id, String name, String description) {
        String safeId = normalizeId(id);
        if (metas.containsKey(safeId)) {
            throw new IllegalArgumentException("知识库已存在：" + safeId
                    + "（现有：" + String.join(", ", metas.keySet()) + "）");
        }
        String safeName = (name == null || name.isBlank()) ? safeId : name.trim();
        String safeDesc = description == null ? "" : description.trim();

        KnowledgeBaseMeta meta = new KnowledgeBaseMeta(safeId, safeName, safeDesc,
                LocalDateTime.now().format(TS));
        metas.put(safeId, meta);
        persistIndex();
        log.info("Stage 8 · 已创建知识库：id={} name={}（目录 {}）", safeId, safeName, root.resolve(safeId));
        return get(safeId);
    }

    /**
     * 更新知识库的元数据（名称 / 备注）。<b>补的是 CRUD 里缺的那个 U。</b>
     *
     * <p>两个字段都是「不传就不改」：{@code name} 传 null / 空白表示保持原名；
     * {@code description} 传 null 表示保持原备注（想清空备注请传空串 {@code ""}）。
     * 之所以要这种「部分更新」语义：调用方只需改一个字段，
     * 不必先把另一个读出来再原样写回 —— <b>「读-改-写」正是并发下最容易丢更新的模式</b>。
     *
     * <p><b>id 不能改</b>：它同时是磁盘目录名和账本主键，改名等价于
     * 「删掉旧库 + 新建一个库」，那不是更新。要换 id 就新建一个库再导资料过去。
     *
     * @return 更新后的元数据
     * @throws NoSuchElementException id 对应的库不存在
     */
    public synchronized KnowledgeBaseMeta update(String id, String name, String description) {
        String safeId = normalizeId(id);
        KnowledgeBaseMeta old = metas.get(safeId);
        if (old == null) {
            throw new NoSuchElementException("知识库不存在：" + safeId
                    + "（现有：" + String.join(", ", metas.keySet()) + "）");
        }
        String newName = (name == null || name.isBlank()) ? old.name() : name.trim();
        String newDesc = description == null ? old.description() : description.trim();

        KnowledgeBaseMeta updated = new KnowledgeBaseMeta(safeId, newName, newDesc, old.createdAt());
        metas.put(safeId, updated);

        // 已经实例化的库要同步换掉 meta 引用。漏了这一步的话，
        // GET /stage8/kb/{id} 显示新名字、而 /stats 显示旧名字 ——
        // 同一份数据两个答案，这类不一致最难查。
        KnowledgeBase live = bases.get(safeId);
        if (live != null) {
            live.refreshMeta(updated);
        }
        persistIndex();

        log.info("Stage 8 · 已更新知识库元数据：id={} name={} description={}",
                safeId, newName, newDesc);
        return updated;
    }

    /**
     * 按 id 取库；{@code null} / 空串视为默认库。
     *
     * <p>这样「老的接口」和「新接口」可以共用同一条取值路径：
     * 老接口拿到 null 就直接落到默认库上。
     */
    public KnowledgeBase get(String id) {
        String safeId = (id == null || id.isBlank()) ? DEFAULT_ID : id.trim();
        KnowledgeBaseMeta meta = metas.get(safeId);
        if (meta == null) {
            throw new NoSuchElementException("知识库不存在：" + safeId
                    + "（现有：" + String.join(", ", metas.keySet()) + "）");
        }
        return instantiate(meta);
    }

    /** 按 id 取库，不存在返回 {@code null}（给「删库」这类幂等操作留口子）。 */
    public KnowledgeBase find(String id) {
        return id == null || !metas.containsKey(id.trim()) ? null : get(id);
    }

    /**
     * 删除知识库（连同它的目录）。
     *
     * <p>默认库不允许删除 —— 只允许 {@link KnowledgeBase#clear() 清空}。
     * 这是刻意的：默认库是「不带 kbId 的老接口」的落点，
     * 把它删掉会让那批接口集体 404，属于破坏性变更，不做。
     *
     * @return 是否真的删掉了一个库
     */
    public boolean delete(String id) {
        String safeId = normalizeId(id);
        if (DEFAULT_ID.equals(safeId)) {
            throw new IllegalArgumentException("默认知识库不能删除，如需腾空请用 "
                    + "DELETE /stage8/kb/" + DEFAULT_ID + "/clear");
        }
        if (!metas.containsKey(safeId)) {
            return false;
        }

        bases.remove(safeId);
        metas.remove(safeId);
        persistIndex();
        removeDirectory(root.resolve(safeId));
        log.info("Stage 8 · 已删除知识库：{}（剩余 {} 个）", safeId, metas.size());
        return true;
    }

    /** 知识库根目录，仅用于展示与排障。 */
    public Path root() {
        return root;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private KnowledgeBase instantiate(KnowledgeBaseMeta meta) {
        KnowledgeBase cached = bases.get(meta.id());
        if (cached != null) {
            return cached;
        }
        // 双检锁：读盘是 I/O，不能放在 ConcurrentHashMap.computeIfAbsent 里，
        // 否则会长时间占住那个 bin 的锁、阻塞其它库的读取。
        synchronized (this) {
            KnowledgeBase again = bases.get(meta.id());
            if (again != null) {
                return again;
            }
            KnowledgeBase created = new KnowledgeBase(meta, root.resolve(meta.id()),
                    embeddingModel, props, resourceResolver, observationRegistry);
            created.ensureLoaded();
            bases.put(meta.id(), created);
            return created;
        }
    }

    private String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("知识库 id 不能为空");
        }
        String id = raw.trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("知识库 id 只允许字母/数字/下划线/连字符，"
                    + "且不超过 32 个字符（它同时是磁盘目录名）：" + id
                    + "。想用中文请写在 name 字段里。");
        }
        if (RESERVED_IDS.contains(id.toLowerCase())) {
            throw new IllegalArgumentException("知识库 id 不能叫「" + id
                    + "」：它已经被固定接口路径占用（保留字：" + String.join(", ", RESERVED_IDS)
                    + "）。换一个名字，显示名可以写在 name 里。");
        }
        return id;
    }

    /**
     * 删除库目录。只删我们自己创建的那两个文件，最后尝试删掉空目录 ——
     * <b>不递归</b>。这样即使有人在库里手放了别的东西，也会因为「目录非空」
     * 而删不掉并留下警告，而不是被静默清空。
     */
    private void removeDirectory(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(KnowledgeBase.STORE_FILE));
            Files.deleteIfExists(dir.resolve(KnowledgeBase.MANIFEST_FILE));
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Stage 8 · 知识库目录清理失败（清单已移除，功能不受影响）：{} —— {}",
                    dir, e.getMessage());
        }
    }

    /**
     * 一次性迁移：单库时代数据在 {@code &lt;root&gt;/stage8-store.json}，
     * 现在应该在 {@code &lt;root&gt;/default/store.json}。
     *
     * <p>不迁移的话，升级后用户会发现「原来入库的语料全不见了」——
     * 数据其实还在，只是换了个位置没人去找。这类「升级把自己数据弄丢」的体验
     * 比功能缺失更伤，所以宁可多写这十行。
     */
    private void migrateLegacySingleStore() {
        Path legacyStore = root.resolve(LEGACY_STORE_FILE);
        Path legacyManifest = root.resolve(LEGACY_STORE_FILE + ".manifest.tsv");
        Path targetDir = root.resolve(DEFAULT_ID);
        Path targetStore = targetDir.resolve(KnowledgeBase.STORE_FILE);

        if (!Files.isRegularFile(legacyStore) || Files.isRegularFile(targetStore)) {
            return;
        }
        try {
            Files.createDirectories(targetDir);
            Files.move(legacyStore, targetStore);
            if (Files.isRegularFile(legacyManifest)) {
                Files.move(legacyManifest, targetDir.resolve(KnowledgeBase.MANIFEST_FILE));
            }
            log.info("Stage 8 · 检测到单库时代的数据，已迁移到默认知识库目录：{} → {}",
                    legacyStore, targetStore);
        } catch (IOException e) {
            log.warn("Stage 8 · 旧数据迁移失败（不影响启动）：{} —— {}", legacyStore, e.getMessage());
        }
    }

    private void readIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                KnowledgeBaseMeta meta = parseIndexLine(line);
                if (meta != null) {
                    metas.put(meta.id(), meta);
                }
            }
        } catch (IOException e) {
            log.warn("Stage 8 · 知识库清单读取失败（{}），按空清单继续。", e.getMessage());
        }
    }

    private void persistIndex() {
        try {
            if (indexFile.getParent() != null) {
                Files.createDirectories(indexFile.getParent());
            }
            List<String> lines = metas.values().stream()
                    .map(m -> String.join("\t",
                            sanitize(m.id()), sanitize(m.name()),
                            sanitize(m.description()), sanitize(m.createdAt())))
                    .toList();
            Files.write(indexFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Stage 8 · 知识库清单落盘失败：{}", e.getMessage());
        }
    }

    /** 索引一行 = 一个库：{@code id \t name \t description \t createdAt}。 */
    private static KnowledgeBaseMeta parseIndexLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 4 || parts[0].isBlank()) {
            return null;
        }
        return new KnowledgeBaseMeta(parts[0], parts[1], parts[2], parts[3]);
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

    /**
     * 知识库一览里的一项。
     *
     * @param loaded   该库的向量是否已经在内存里
     * @param documents / {@code chunks} 只有 {@code loaded=true} 时才有意义，否则为 -1
     */
    public record BaseBrief(String id, String name, String description, String createdAt,
                            boolean loaded, int documents, int chunks) {
    }
}
