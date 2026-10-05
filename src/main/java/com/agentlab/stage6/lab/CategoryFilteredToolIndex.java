package com.agentlab.stage6.lab;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

/**
 * 装饰器型 {@link ToolIndex}：在任意内层索引之上叠加「分类硬过滤」。
 *
 * <h2>为什么用装饰器而不是再写一个索引</h2>
 * 因为「按分类过滤」与「怎么算相关度」是两件正交的事。
 * 把它做成装饰器，就能得到一个很有用的组合能力 ——
 * <b>不用改 Spring AI 一行源码，也能给内置的 {@code RegexToolIndex} 加上分类过滤</b>：
 *
 * <pre>
 *   new CategoryFilteredToolIndex(new RegexToolIndex())              // 给内置索引加过滤
 *   new CategoryFilteredToolIndex(new SynonymBoostedToolIndex(true)) // 给自定义索引加过滤
 * </pre>
 *
 * <p>这正是 {@code ToolIndex} 被设计成接口（而不是抽象类）的价值所在。
 *
 * <h2>它必须自己解决的元数据问题</h2>
 * {@link ToolIndex} 的入参只有 {@code ToolReference(toolName, score, summary)}，
 * <b>没有地方放 category</b>。所以装饰器在 {@link #indexTool} 时顺手记一份
 * 「工具名 → 分类」的旁路表（数据源仍是 {@link ToolDictionary}）。
 * 这是所有「给检索加业务约束」的实现都绕不开的一步：
 * <b>业务元数据要么来自工具名约定，要么来自注册时的旁路登记，接口本身不会给你。</b>
 *
 * <h2>放大候选再截断</h2>
 * 过滤会让候选变少。如果直接拿内层的 topK 再过滤，很容易「过滤完只剩 1 个」。
 * 所以过滤时先用 {@code maxResults * 4}（下限 20）向内层要一批更宽的候选，
 * 过滤后再截断到真正的 topK —— 这是一个很常见、也很容易漏掉的实现细节。
 */
public class CategoryFilteredToolIndex implements ToolIndex {

    private static final int WIDEN_FACTOR = 4;
    private static final int WIDEN_MIN = 20;

    private final ToolIndex delegate;

    /** sessionId -> (toolName -> category)，旁路元数据。 */
    private final Map<String, Map<String, ToolCategory>> categoryRegistry = new ConcurrentHashMap<>();

    public CategoryFilteredToolIndex(ToolIndex delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate ToolIndex must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public void indexTool(String sessionId, ToolReference reference) {
        remember(sessionId, reference);
        delegate.indexTool(sessionId, reference);
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> references) {
        if (sessionId == null || references == null) {
            return;
        }
        Map<String, ToolCategory> registry = new ConcurrentHashMap<>();
        for (ToolReference ref : references) {
            if (ref == null || ref.toolName() == null) {
                continue;
            }
            ToolCategory category = ToolDictionary.categoryOf(ref.toolName());
            if (category != null) {
                registry.put(ref.toolName(), category);
            }
        }
        categoryRegistry.put(sessionId, registry);
        delegate.indexTools(sessionId, references);
    }

    @Override
    public void clearIndex(String sessionId) {
        if (sessionId != null) {
            categoryRegistry.remove(sessionId);
        }
        delegate.clearIndex(sessionId);
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest request) {
        ToolCategory target = ToolCategory.fromAlias(request.categoryFilter());
        if (target == null) {
            // 没有可识别的分类约束，直接放行 —— 装饰器必须是「透明」的。
            return delegate.search(request);
        }

        int topK = request.maxResults() != null && request.maxResults() > 0
                ? request.maxResults()
                : SynonymBoostedToolIndex.DEFAULT_MAX_RESULTS;

        // 步骤 1：向内层要一批更宽的候选（不带分类约束，因为它不认这个参数）。
        ToolSearchResponse wide = delegate.search(new ToolSearchRequest(
                request.sessionId(),
                request.query(),
                Math.max(topK * WIDEN_FACTOR, WIDEN_MIN),
                null));

        // 步骤 2：按旁路表过滤，再截断到真正的 topK。
        Map<String, ToolCategory> registry = categoryRegistry.getOrDefault(request.sessionId(), Map.of());
        List<ToolReference> filtered = wide.toolReferences().stream()
                .filter(ref -> target == registry.get(ref.toolName()))
                .limit(topK)
                .toList();

        return ToolSearchResponse.builder()
                .toolReferences(filtered)
                .totalMatches(filtered.size())
                .searchMetadata(ToolSearchResponse.SearchMetadata.builder()
                        .searchType(wide.searchMetadata() == null
                                ? "category-filtered"
                                : wide.searchMetadata().searchType() + "+category(" + target.code() + ")")
                        .query(request.query())
                        .searchTimeMs(wide.searchMetadata() == null ? null : wide.searchMetadata().searchTimeMs())
                        .build())
                .build();
    }

    private void remember(String sessionId, ToolReference reference) {
        if (sessionId == null || reference == null || reference.toolName() == null) {
            return;
        }
        ToolCategory category = ToolDictionary.categoryOf(reference.toolName());
        if (category == null) {
            return;
        }
        categoryRegistry.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(reference.toolName(), category);
    }

    /** 暴露给测试：某个会话里注册了多少个带分类的工具。 */
    int registeredCategories(String sessionId) {
        return categoryRegistry.getOrDefault(sessionId, Map.of()).size();
    }
}
