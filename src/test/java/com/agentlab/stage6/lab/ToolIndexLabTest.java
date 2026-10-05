package com.agentlab.stage6.lab;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.agentlab.stage6.tools.CrmTools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具检索策略的纯单元测试（不需要 Spring 上下文、不需要 API Key）。
 *
 * <h2>为什么这个测试特别有价值</h2>
 * 调 Agent 的检索效果，最常见的低效做法是：改一版策略 → 启动应用 → 发一次对话 →
 * 盯着模型输出猜「到底是没检索对，还是模型用错了」。一次循环几分钟，还带随机性。
 *
 * <p>而检索这一层<b>是纯函数</b>：给定 (工具集, query) 必然得到同一组命中。
 * 把它单测起来，就能做到「改打分公式 → 几百毫秒看到所有 query 的命中变化」。
 * 本测试里那几个 {@code printXxx} 方法就是干这个的：
 * 它们不是断言，而是<b>把四策略的命中并排打出来给你看</b>。
 *
 * <p>换句话说：<b>把 Agent 链路里可以确定化的部分确定化，是让 Agent 可调试的前提。</b>
 */
class ToolIndexLabTest {

    private static final String SESSION = "lab-test";

    /**
     * 与运行时完全一致的索引输入：只有 {@code toolName} 与 {@code summary(=description)}
     * 两个字段会参与检索 —— 参数 Schema 不在其中。
     */
    private static final List<ToolReference> CATALOG = buildCatalog();

    private static List<ToolReference> buildCatalog() {
        ToolCallback[] callbacks = ToolCallbacks.from(new CrmTools());
        return Arrays.stream(callbacks)
                .map(cb -> ToolReference.builder()
                        .toolName(cb.getToolDefinition().name())
                        .summary(cb.getToolDefinition().description())
                        .build())
                .toList();
    }

    private static ToolIndex indexOf(SearchStrategy strategy) {
        ToolIndex index = strategy.newIndex();
        index.indexTools(SESSION, CATALOG);
        return index;
    }

    private static ToolSearchResponse search(SearchStrategy strategy, String query, String category, int max) {
        return indexOf(strategy).search(new ToolSearchRequest(SESSION, query, max, category));
    }

    private static List<String> hitNames(SearchStrategy strategy, String query, String category, int max) {
        return search(strategy, query, category, max).toolReferences().stream()
                .map(ToolReference::toolName)
                .toList();
    }

    // ------------------------------------------------------------------
    // 前置：索引输入是否正确
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具集应被完整索引，且全部已在业务词典中登记")
    void catalogShouldCoverAllToolsAndBeRegistered() {
        assertEquals(13, CATALOG.size(), "CrmTools 应暴露 13 个 @Tool 方法");
        long unregistered = CATALOG.stream()
                .filter(ref -> !ToolDictionary.isRegistered(ref.toolName()))
                .count();
        assertEquals(0, unregistered,
                "有工具没进 ToolDictionary —— 这类工具在 SYNONYM 策略下只能靠描述分词兜底");
        assertEquals(13, ToolDictionary.size());
    }

    @Test
    @DisplayName("索引应保存 description 作为唯一检索文本")
    void indexShouldKeepDescriptionAsSummary() {
        ToolIndex index = indexOf(SearchStrategy.SYNONYM);
        ToolSearchResponse response = search(SearchStrategy.SYNONYM, "积分", null, 3);
        assertNotNull(response.toolReferences());
        assertFalse(response.toolReferences().isEmpty());

        ToolReference first = response.toolReferences().get(0);
        assertEquals("queryPoints", first.toolName());
        assertNotNull(first.summary());
        assertTrue(first.summary().contains("积分"), "summary 必须承载工具描述，否则索引没有可比对的文本");
        assertNotNull(response.searchMetadata());
        assertEquals("synonym-boosted", response.searchMetadata().searchType());
    }

    // ------------------------------------------------------------------
    // 场景一：默认 RegexToolIndex 在中文 query 下的基线表现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景一：内置 regex 策略（基线）——中文整句切不开，召回极低")
    void regexBaseline() {
        List<String> chineseQueries = List.of(
                "客户 C1001 还有多少积分",
                "钱什么时候能退回来",
                "帮 C1001 查一下物流到哪了");

        System.out.println("\n===== 场景一：内置 RegexToolIndex 对中文 query 的表现（基线）=====");
        for (String q : chineseQueries) {
            List<String> names = hitNames(SearchStrategy.REGEX, q, null, 5);
            System.out.printf("  %-28s -> %s%n", q, names.isEmpty() ? "(无命中)" : names);
        }
        System.out.println("  说明：内置实现用英文停用词表 + 正则匹配，中文 query 没有空格 → 整句一个 token。\n");

        // 只做弱断言：结果必须非 null（空列表是允许的，正是这里要观察的现象）。
        assertNotNull(hitNames(SearchStrategy.REGEX, "客户 C1001 还有多少积分", null, 5));
    }

    // ------------------------------------------------------------------
    // 场景二：中文 n-gram 分词 + 工具名加权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景二：中文 n-gram 分词让「积分」这类业务词可被召回")
    void keywordStrategyRecoversChineseBusinessWords() {
        String query = "客户 C1001 还有多少积分?";
        List<String> names = hitNames(SearchStrategy.KEYWORD, query, null, 5);

        System.out.println("\n===== 场景二：中文 2/3-gram 分词（KEYWORD）=====");
        System.out.println("  query: " + query);
        System.out.println("  query 切出的 gram: " + ChineseText.grams(query));
        System.out.println("  命中: " + names + "\n");

        assertFalse(names.isEmpty(), "分词后应能召回工具");
        assertEquals("queryPoints", names.get(0), "「积分」应把 queryPoints 排到首位");

        // 参数值必须被当作 ID 忽略：给一个纯 ID 的 query，它不该凭空召回任何工具。
        ToolSearchResponse idOnly = search(SearchStrategy.KEYWORD, "SO202610010001", null, 5);
        assertTrue(idOnly.searchMetadata().searchType().endsWith("-fallback"),
                "纯参数值 query 不应因 ID 里的数字与描述重合而误召回，实际命中: "
                        + idOnly.toolReferences().stream().map(ToolReference::toolName).toList());
    }

    @Test
    @DisplayName("场景二补充：参数值（C1001 / SO2026…）不参与工具选择打分")
    void idLikeTokensShouldBeIgnored() {
        assertTrue(ChineseText.isIdLike("C1001"));
        assertTrue(ChineseText.isIdLike("SO202610010001"));
        assertTrue(ChineseText.isIdLike("20261001"));
        assertFalse(ChineseText.isIdLike("points"));
        assertFalse(ChineseText.isIdLike("customer"));

        Set<String> tokens = ChineseText.significantAsciiTokens("客户 C1001 的订单 SO202610010001 到哪了");
        assertTrue(tokens.isEmpty(), "ID 值应被完全过滤，实际: " + tokens);

        Set<String> toolTokens = ChineseText.wordTokens("queryCustomerByPhone");
        assertEquals(Set.of("query", "customer", "phone"), toolTokens);
        assertTrue(ChineseText.wordTokens("HTTPServerTool").contains("http"),
                "连续大写缩写应能被正确拆开");
    }

    // ------------------------------------------------------------------
    // 场景三：业务同义词词典（口语 -> 工具）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景三：同义词词典让「钱什么时候能退回来」命中退款工具")
    void synonymStrategyFindsRefundFromColloquialPhrase() {
        String query = "钱什么时候能退回来";

        ToolSearchResponse withoutDict = search(SearchStrategy.KEYWORD, query, null, 5);
        ToolSearchResponse withDict = search(SearchStrategy.SYNONYM, query, null, 5);
        List<String> dictNames = withDict.toolReferences().stream().map(ToolReference::toolName).toList();

        System.out.println("\n===== 场景三：领域同义词词典的增益 =====");
        System.out.println("  query: " + query);
        System.out.println("  KEYWORD（无词典）: " + withoutDict.toolReferences().stream()
                .map(ToolReference::toolName).toList()
                + "   searchType=" + withoutDict.searchMetadata().searchType());
        System.out.println("  SYNONYM（有词典）: " + dictNames
                + "   searchType=" + withDict.searchMetadata().searchType());
        System.out.println("  词典命中入口: applyRefund -> " + ToolDictionary.synonymsOf("applyRefund").size()
                + " 个口语说法\n");

        assertEquals("applyRefund", dictNames.get(0),
                "口语说法「钱什么时候能退回来」应定位到退款工具，这正是纯分词做不到的部分");

        // 反证：去掉词典后这句话与任何工具描述都没有共同的 n-gram，只能走兜底。
        // 注意这里不能用「结果里不含 applyRefund」来断言 —— 兜底会按索引顺序返回前 N 个，
        // 恰好可能包含它。真正要验证的是「没有真实命中」。
        assertTrue(withoutDict.searchMetadata().searchType().endsWith("-fallback"),
                "反证：没有词典时这句口语与工具描述没有任何共同 n-gram，只应兜底。"
                        + "实际 searchType=" + withoutDict.searchMetadata().searchType());
    }

    // ------------------------------------------------------------------
    // 场景四：分类硬过滤（装饰器）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("场景四：分类过滤装饰器把召回锁死在指定业务域内")
    void categoryFilterRestrictsRecall() {
        String query = "帮 C1001 查一下";

        List<String> unfiltered = hitNames(SearchStrategy.CATEGORY, query, null, 5);
        List<String> logisticsOnly = hitNames(SearchStrategy.CATEGORY, query, "logistics", 5);
        List<String> afterSalesOnly = hitNames(SearchStrategy.CATEGORY, query, "aftersales", 5);

        System.out.println("\n===== 场景四：分类硬过滤 =====");
        System.out.println("  query: " + query);
        System.out.println("  不过滤     : " + unfiltered);
        System.out.println("  物流域     : " + logisticsOnly);
        System.out.println("  售后域     : " + afterSalesOnly + "\n");

        assertTrue(logisticsOnly.stream().allMatch(n -> ToolDictionary.categoryOf(n) == ToolCategory.LOGISTICS),
                "带 categoryFilter 时结果必须全部属于该域，实际: " + logisticsOnly);
        assertTrue(afterSalesOnly.stream().allMatch(n -> ToolDictionary.categoryOf(n) == ToolCategory.AFTER_SALES),
                "带 categoryFilter 时结果必须全部属于该域，实际: " + afterSalesOnly);
        assertEquals(List.of("queryLogistics"), logisticsOnly, "物流域只有一个工具");
    }

    @Test
    @DisplayName("场景四补充：装饰器在无分类约束时必须完全透明")
    void decoratorIsTransparentWithoutFilter() {
        CategoryFilteredToolIndex decorator =
                new CategoryFilteredToolIndex(new SynonymBoostedToolIndex(true));
        SynonymBoostedToolIndex inner = new SynonymBoostedToolIndex(true);

        decorator.indexTools(SESSION, CATALOG);
        inner.indexTools(SESSION, CATALOG);

        String query = "客户还有多少积分";
        List<String> viaDecorator = decorator.search(new ToolSearchRequest(SESSION, query, 5, null))
                .toolReferences().stream().map(ToolReference::toolName).toList();
        List<String> viaInner = inner.search(new ToolSearchRequest(SESSION, query, 5, null))
                .toolReferences().stream().map(ToolReference::toolName).toList();

        assertEquals(viaInner, viaDecorator, "没有分类约束时装饰器不应改变任何结果");
        assertEquals(CATALOG.size(), decorator.registeredCategories(SESSION));
    }

    // ------------------------------------------------------------------
    // 工程细节：幂等、兜底、会话隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("索引必须幂等：重复 indexTools 不应产生重复条目")
    void indexingShouldBeIdempotent() {
        SynonymBoostedToolIndex index = new SynonymBoostedToolIndex(true);
        index.indexTools(SESSION, CATALOG);
        int first = index.size(SESSION);
        index.indexTools(SESSION, CATALOG);
        index.indexTools(SESSION, CATALOG);

        assertEquals(CATALOG.size(), first);
        assertEquals(first, index.size(SESSION),
                "Advisor 在指纹变化时会重建索引，追加式实现会造成条目翻倍、分数虚高");
    }

    @Test
    @DisplayName("检索无命中时必须兜底，否则模型会彻底失去工具能力")
    void emptyResultShouldFallBack() {
        ToolSearchResponse response = search(SearchStrategy.SYNONYM, "你好呀", null, 4);

        System.out.println("\n===== 工程细节：无命中兜底 =====");
        System.out.println("  query=你好呀 -> " + response.toolReferences().stream()
                .map(ToolReference::toolName).toList());
        System.out.println("  searchType=" + response.searchMetadata().searchType() + "\n");

        assertFalse(response.toolReferences().isEmpty(), "空结果会让模型连「试着查一下」的机会都没有");
        assertTrue(response.searchMetadata().searchType().endsWith("-fallback"),
                "兜底必须在 searchType 里可见，否则线上分不清「命中」和「降级」");
        assertEquals(4, response.toolReferences().size());
    }

    @Test
    @DisplayName("索引按会话隔离，互不串味")
    void indexesAreIsolatedPerSession() {
        SynonymBoostedToolIndex index = new SynonymBoostedToolIndex(true);
        index.indexTools("session-a", CATALOG);
        index.indexTools("session-b", List.of(CATALOG.get(0)));

        assertEquals(CATALOG.size(), index.size("session-a"));
        assertEquals(1, index.size("session-b"));

        index.clearIndex("session-a");
        assertEquals(0, index.size("session-a"));
        assertEquals(1, index.size("session-b"), "清空一个会话不应影响其它会话");
    }

    @Test
    @DisplayName("清理未索引的会话应当是安全的空操作")
    void clearingUnknownSessionIsNoOp() {
        SynonymBoostedToolIndex index = new SynonymBoostedToolIndex(true);
        index.clearIndex("never-indexed");
        assertEquals(0, index.totalSize());
    }

    // ------------------------------------------------------------------
    // 汇总：四策略并排对比
    // ------------------------------------------------------------------

    @Test
    @DisplayName("汇总：同一条 query 在四条策略下的命中对比表")
    void printStrategyComparisonTable() {
        List<String> queries = List.of(
                "客户 C1001 还有多少积分?",
                "钱什么时候能退回来",
                "帮 C1001 查一下物流到哪了",
                "我要开个发票报销",
                "这个券怎么用",
                "还有货吗");

        System.out.println("\n================ 四策略命中对比 ================");
        for (String q : queries) {
            System.out.println("\nQ: " + q);
            for (SearchStrategy s : SearchStrategy.values()) {
                ToolSearchResponse r = search(s, q, null, 3);
                String names = r.toolReferences().stream()
                        .map(ref -> ref.toolName() + "(" + fmt(ref.relevanceScore()) + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("(无命中)");
                String type = r.searchMetadata() == null ? "-" : r.searchMetadata().searchType();
                System.out.printf("  %-11s [%s] %s%n", s.code(), type, names);
            }
        }
        System.out.println("\n===============================================\n");

        // 抽一条做硬断言，保证这张表不是「看起来有输出」而已。
        assertEquals("queryInvoice", hitNames(SearchStrategy.SYNONYM, "我要开个发票报销", null, 3).get(0));
        assertEquals("queryStock", hitNames(SearchStrategy.SYNONYM, "还有货吗", null, 3).get(0));
    }

    private static String fmt(Double score) {
        return score == null ? "-" : String.format("%.2f", score);
    }
}
