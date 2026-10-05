package com.agentlab.stage6.lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

/**
 * 自定义 {@link ToolIndex}：中文分词 + 工具名加权 +（可选）领域同义词词典。
 *
 * <h2>它替换掉了什么</h2>
 * 内置 {@code RegexToolIndex} 的策略是「把 query 转成正则 → 在
 * {@code description} 上做词法匹配」，附加一张<b>英文</b>停用词表。
 * 对中文 query 它几乎不可用（无空格 → 整句一个 token）。
 *
 * <h2>它做了什么</h2>
 * 三个可独立开关的增量，正好对应 {@link SearchStrategy} 的三档：
 * <ol>
 *   <li><b>中文 n-gram 召回</b>：把 query 与 description 都切成 2/3-gram，解决「切不了词」</li>
 *   <li><b>工具名加权</b>：query 里的英文词命中 camelCase 工具名时给 3 倍权重</li>
 *   <li><b>同义词命中</b>：query 命中 {@link ToolDictionary} 登记的口语说法时给 4 倍权重</li>
 * </ol>
 *
 * <h2>两个必须自己处理的工程细节</h2>
 * <ol>
 *   <li><b>索引是幂等的</b>：{@link #indexTools} 会先清掉该会话的旧索引。
 *       Advisor 只在「工具集指纹变化」时重建索引，但一旦你手工调用、
 *       或在会话中动态换工具，追加式索引就会产生重复条目、分数虚高。</li>
 *   <li><b>检索可能返回空</b>：如果 query 与任何工具都不沾边（例如「你好」），
 *       严格返回空列表会让模型<b>彻底失去工具能力</b>。这里选择回落到
 *       「描述最长的前 N 个工具」，并在 {@code SearchMetadata.searchType} 里
 *       标记 {@code -fallback}，让调用方看得见这次是兜底而非命中。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：Advisor 可能并发处理不同会话，所以会话表用
 * {@link ConcurrentHashMap}，单会话内的工具表用 {@link CopyOnWriteArrayList}
 * （读多写极少）。
 */
public class SynonymBoostedToolIndex implements ToolIndex {

    /** 未显式指定 maxResults 时的默认返回条数。 */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** 权重：工具名命中（最可信，query 里出现 refund 这类英文词时）。 */
    private static final double WEIGHT_NAME = 3.0;
    /** 权重：同义词命中（业务词典给了强证据）。 */
    private static final double WEIGHT_SYNONYM = 4.0;
    /** 权重：描述 n-gram 命中（召回主力，但最容易被噪声干扰，所以权重最低）。 */
    private static final double WEIGHT_GRAM = 1.0;

    /** 检索类型标识，会出现在 {@code SearchMetadata} 里，方便和别的策略区分。 */
    private static final String SEARCH_TYPE = "synonym-boosted";

    /**
     * 同义词「部分命中」的覆盖率门槛。
     *
     * <p>为什么需要它：用户的说法几乎不会与词典条目逐字相同 ——
     * 词典写「钱什么时候退」，用户问「钱什么时候<b>能</b>退回来」。若要求完整匹配，
     * 词典的绝大部分条目都会失效；改判 n-gram 覆盖率后，上例约 0.78，可以命中。
     *
     * <p>门槛为什么是 0.75 而不是更低：中文里的疑问套话（「什么时候」「能不能」）
     * 会制造大量虚假重合 —— 物流的同义词「什么时候能到」与退款的
     * 「钱什么时候能退回来」覆盖率就有 0.78。门槛太松，检索会被套话拉平、失去区分度。
     * 这类「通用词稀释分数」的问题，在大规模场景里的正规解法是引入 IDF 权重，
     * 本实验室刻意用最朴素的阈值来暴露它，好让你知道升级到 lucene / 向量检索时
     * 究竟是在解决什么问题。
     */
    private static final double SYNONYM_COVERAGE_THRESHOLD = 0.75;

    /** sessionId -> 该会话已索引的工具。 */
    private final Map<String, CopyOnWriteArrayList<IndexedTool>> sessionIndexes = new ConcurrentHashMap<>();

    /** 是否启用领域同义词词典。false 时退化为纯分词+名字加权的消融对照组。 */
    private final boolean useSynonymDictionary;

    public SynonymBoostedToolIndex() {
        this(true);
    }

    public SynonymBoostedToolIndex(boolean useSynonymDictionary) {
        this.useSynonymDictionary = useSynonymDictionary;
    }

    /** 一个工具在索引里的全部可比较特征，建索引时算一次，检索时只做集合交。 */
    private record IndexedTool(String toolName,
                               String description,
                               ToolCategory category,
                               Set<String> nameTokens,
                               Set<String> descriptionGrams,
                               List<String> synonyms) {
    }

    /** 打分中间结果。 */
    private record Scored(IndexedTool tool, double score, double nameHits, double synonymHits, double gramHits) {
    }

    // ------------------------------------------------------------------
    // ToolIndex
    // ------------------------------------------------------------------

    @Override
    public void indexTool(String sessionId, ToolReference reference) {
        indexTools(sessionId, List.of(reference));
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> references) {
        if (sessionId == null || references == null || references.isEmpty()) {
            return;
        }
        CopyOnWriteArrayList<IndexedTool> list = new CopyOnWriteArrayList<>();
        for (ToolReference ref : references) {
            if (ref == null || ref.toolName() == null) {
                continue;
            }
            ToolCategory category = ToolDictionary.categoryOf(ref.toolName());
            List<String> synonyms = useSynonymDictionary
                    ? ToolDictionary.synonymsOf(ref.toolName())
                    : List.of();
            String description = ref.summary() == null ? "" : ref.summary();
            list.add(new IndexedTool(
                    ref.toolName(),
                    description,
                    category,
                    ChineseText.wordTokens(ref.toolName()),
                    ChineseText.grams(description),
                    synonyms));
        }
        // 覆盖而非追加：保证同一会话重复索引是幂等的。
        sessionIndexes.put(sessionId, list);
    }

    @Override
    public void clearIndex(String sessionId) {
        if (sessionId != null) {
            sessionIndexes.remove(sessionId);
        }
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest request) {
        long start = System.nanoTime();
        int topK = resolveTopK(request);
        List<IndexedTool> tools = sessionIndexes.getOrDefault(request.sessionId(), new CopyOnWriteArrayList<>());

        String query = request.query() == null ? "" : request.query();
        Set<String> queryGrams = ChineseText.grams(query);
        Set<String> queryTokens = ChineseText.significantAsciiTokens(query);
        ToolCategory filter = ToolCategory.fromAlias(request.categoryFilter());

        List<Scored> hits = new ArrayList<>();
        for (IndexedTool tool : tools) {
            if (filter != null && tool.category() != filter) {
                continue;
            }
            double nameHits = ChineseText.intersectSize(queryTokens, tool.nameTokens());
            double synonymHits = countSynonymHits(query, queryGrams, tool.synonyms());
            double gramHits = ChineseText.intersectSize(queryGrams, tool.descriptionGrams());
            double score = WEIGHT_NAME * nameHits + WEIGHT_SYNONYM * synonymHits + WEIGHT_GRAM * gramHits;
            if (score > 0) {
                hits.add(new Scored(tool, score, nameHits, synonymHits, gramHits));
            }
        }

        boolean fallback = hits.isEmpty();
        if (fallback) {
            // 兜底：至少给模型几个工具，否则它连「试着查一下」的机会都没有。
            hits = tools.stream()
                    .limit(topK)
                    .map(t -> new Scored(t, 0.0, 0, 0, 0))
                    .toList();
        } else {
            hits.sort(Comparator.comparingDouble(Scored::score).reversed());
        }

        int totalMatches = hits.size();
        List<ToolReference> references = hits.stream()
                .limit(topK)
                .map(s -> ToolReference.builder()
                        .toolName(s.tool().toolName())
                        .summary(s.tool().description())
                        .relevanceScore(s.score())
                        .build())
                .toList();

        return ToolSearchResponse.builder()
                .toolReferences(references)
                .totalMatches(totalMatches)
                .searchMetadata(ToolSearchResponse.SearchMetadata.builder()
                        .searchType(fallback ? SEARCH_TYPE + "-fallback" : SEARCH_TYPE)
                        .query(query)
                        .searchTimeMs((System.nanoTime() - start) / 1_000_000)
                        .build())
                .build();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static int resolveTopK(ToolSearchRequest request) {
        Integer max = request.maxResults();
        return (max != null && max > 0) ? max : DEFAULT_MAX_RESULTS;
    }

    /**
     * 同义词命中计数。
     *
     * <p>两级匹配：
     * <ol>
     *   <li>query 直接包含整个同义词短语（「我要退钱」包含「退钱」）→ 满额 1 分</li>
     *   <li>否则算同义词 n-gram 在 query 里的覆盖率，达到
     *       {@link #SYNONYM_COVERAGE_THRESHOLD} 即按覆盖率给部分分 ——
     *       用来救回「钱什么时候<b>能</b>退回来」这类把词说散的说法</li>
     * </ol>
     * 必须用覆盖率而不是「任意一个 gram 命中」，否则「退」字会同时点亮
     * 退款 / 退货 / 退单三个工具，检索直接失去区分度。
     */
    private static double countSynonymHits(String query, Set<String> queryGrams, List<String> synonyms) {
        if (synonyms.isEmpty()) {
            return 0;
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        double count = 0;
        for (String synonym : synonyms) {
            if (synonym.isEmpty()) {
                continue;
            }
            if (lowerQuery.contains(synonym.toLowerCase(Locale.ROOT))) {
                count += 1.0;
                continue;
            }
            Set<String> synonymGrams = ChineseText.grams(synonym);
            if (synonymGrams.isEmpty()) {
                continue;
            }
            double coverage = (double) ChineseText.intersectSize(synonymGrams, queryGrams) / synonymGrams.size();
            if (coverage >= SYNONYM_COVERAGE_THRESHOLD) {
                count += coverage;
            }
        }
        return count;
    }

    /** 当前会话已索引的工具数（测试与调试用）。 */
    public int size(String sessionId) {
        List<IndexedTool> list = sessionIndexes.get(sessionId);
        return list == null ? 0 : list.size();
    }

    /** 所有会话的索引总数。 */
    public int totalSize() {
        return sessionIndexes.values().stream().mapToInt(List::size).sum();
    }

    /** 暴露给测试：某个工具名被拆成了哪些 token。 */
    Set<String> nameTokensOf(String sessionId, String toolName) {
        List<IndexedTool> list = sessionIndexes.get(sessionId);
        if (list == null) {
            return Set.of();
        }
        return list.stream()
                .filter(t -> t.toolName().equals(toolName))
                .findFirst()
                .map(t -> Set.copyOf(t.nameTokens()))
                .orElseGet(Set::of);
    }
}
