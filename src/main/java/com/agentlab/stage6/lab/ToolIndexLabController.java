package com.agentlab.stage6.lab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.agentlab.config.OpenApiConfig;
import com.agentlab.stage6.tools.CrmTools;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 6 实验室 —— 把「工具检索策略」单独拎出来做对照实验。
 *
 * <h2>为什么值得单独做一个入口</h2>
 * {@code ToolSearchToolCallingAdvisor} 的完整链路是
 * 「建索引 → 检索 → 改写本轮 toolCallbacks → 模型决策 → 执行 → 回填」，
 * 中间夹着一次大模型往返。一旦效果不好，你很难判断问题出在
 * <b>检索选错了工具</b>，还是<b>模型拿到了对的工具却用错了</b>。
 *
 * <p>所以这里把「检索」这一步<b>从模型链路里剥出来单独暴露</b>：
 * {@code /stage6/lab/search} 与 {@code /compare} 直接返回某个 query 命中了哪些工具，
 * 不发一次模型请求、几百微秒出结果、完全确定 ——
 * 调检索策略时应该反复刷这两个端点，而不是反复问模型再猜原因。
 *
 * <h2>与内置 regex 策略的调用方式差异</h2>
 * 内置策略靠 {@code application.yml}（{@code tool-index-type}）切换，只作用于
 * Spring Boot 自动装配的 {@code ChatClient.Builder}。
 * 本实验室要同时挂载多条不同策略的链路，所以改用
 * {@code ToolSearchToolCallingAdvisor.builder().toolIndex(...)} 手工装配 ——
 * 这也是「一个进程里并存多种检索策略」的标准姿势。
 */
@RestController
@RequestMapping("/stage6/lab")
@Tag(name = OpenApiConfig.TAG_STAGE6)
public class ToolIndexLabController {

    private static final String LAB_SYSTEM = """
            你是客服智能助手。需要客户、订单、物流、售后、发票等数据时必须调用工具，
            禁止编造任何数字、单号和状态。
            """;

    private final CrmTools crmTools;
    private final ChatModel chatModel;

    /** 每条策略一条独立的 ChatClient（懒建 + 缓存），互不干扰。 */
    private final Map<SearchStrategy, ChatClient> clients = new EnumMap<>(SearchStrategy.class);

    /** 索引是只读的演示数据，建一次即可复用；按会话隔离由索引内部负责。 */
    private volatile List<ToolReference> catalog;

    public ToolIndexLabController(CrmTools crmTools, ChatModel chatModel) {
        this.crmTools = crmTools;
        this.chatModel = chatModel;
    }

    // ------------------------------------------------------------------
    // 纯检索端点（不调用模型）
    // ------------------------------------------------------------------

    @GetMapping("/search")
    @Operation(summary = "单策略检索（不调用模型）",
            description = "把 query 直接喂给指定的 ToolIndex，返回命中的工具与相关度分数。"
                    + "这是理解「渐进式披露到底下发了哪几个工具」最快的办法："
                    + "它跳过模型，几百微秒出结果，且结果完全确定。")
    public LabSearchResult search(
            @Parameter(description = "自然语言提问", example = "钱什么时候能退回来")
            @RequestParam String q,
            @Parameter(description = "检索策略：regex / keyword / synonym / category",
                    example = "synonym")
            @RequestParam(defaultValue = "synonym") String strategy,
            @Parameter(description = "返回条数上限", example = "5")
            @RequestParam(defaultValue = "5") int max,
            @Parameter(description = "分类硬过滤，可填 customer / order / logistics / aftersales / finance / product",
                    example = "aftersales")
            @RequestParam(required = false) String category) {
        SearchStrategy s = SearchStrategy.fromCode(strategy);
        ToolIndex index = s.newIndex();
        String sessionId = "lab";
        index.indexTools(sessionId, catalog());

        ToolSearchResponse response = index.search(new ToolSearchRequest(sessionId, q, max, category));
        return LabSearchResult.of(s, response, category);
    }

    @GetMapping("/compare")
    @Operation(summary = "四策略并排对比（不调用模型）",
            description = "同一条 query 依次喂给 REGEX / KEYWORD / SYNONYM / CATEGORY 四条策略，"
                    + "并排返回各自的命中列表 —— 用来直观看到「中文分词」「业务词典」"
                    + "「分类约束」各自贡献了多少召回。")
    public LabCompareResult compare(
            @Parameter(description = "自然语言提问", example = "帮 C1001 查一下物流到哪了")
            @RequestParam String q,
            @Parameter(description = "返回条数上限", example = "5")
            @RequestParam(defaultValue = "5") int max,
            @Parameter(description = "分类硬过滤（对不支持该参数的策略无效果）",
                    example = "logistics")
            @RequestParam(required = false) String category) {
        List<LabSearchResult> results = new ArrayList<>();
        for (SearchStrategy s : SearchStrategy.values()) {
            ToolIndex index = s.newIndex();
            String sessionId = "lab";
            index.indexTools(sessionId, catalog());
            results.add(LabSearchResult.of(s, index.search(new ToolSearchRequest(sessionId, q, max, category)), category));
        }
        return new LabCompareResult(q, category, catalog().size(), results);
    }

    @GetMapping("/catalog")
    @Operation(summary = "工具业务画像（不调用模型）",
            description = "列出全部工具、所属业务域、以及词典登记的口语同义词。"
                    + "看一眼就知道「同义词词典覆盖了哪些工具」——没登记的工具在 SYNONYM "
                    + "策略下只能靠描述分词兜底。")
    public List<LabToolProfile> catalogEndpoint() {
        return catalog().stream()
                .map(ref -> {
                    ToolCategory category = ToolDictionary.categoryOf(ref.toolName());
                    return new LabToolProfile(
                            ref.toolName(),
                            category == null ? null : category.code(),
                            category == null ? null : category.label(),
                            ToolDictionary.synonymsOf(ref.toolName()),
                            ref.summary(),
                            ToolDictionary.isRegistered(ref.toolName()));
                })
                .toList();
    }

    // ------------------------------------------------------------------
    // 真实对话端点（调用模型）
    // ------------------------------------------------------------------

    @GetMapping("/chat")
    @Operation(summary = "按指定策略进行真实对话",
            description = "用指定策略手工装配 ToolSearchToolCallingAdvisor 后真实调用模型。"
                    + "对比不同策略时，建议打开日志看每轮下发的工具数量与模型选择。"
                    + "注意：该端点需要真实 DEEPSEEK_API_KEY。")
    public String chat(
            @Parameter(description = "自然语言提问", example = "帮 C1001 查一下物流到哪了")
            @RequestParam String message,
            @Parameter(description = "检索策略：regex / keyword / synonym / category",
                    example = "synonym")
            @RequestParam(defaultValue = "synonym") String strategy,
            @Parameter(description = "会话标识", example = "stage6-lab")
            @RequestParam(defaultValue = "stage6-lab") String conversationId) {
        SearchStrategy s = SearchStrategy.fromCode(strategy);
        return clientFor(s).prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 把 {@code CrmTools} 上的 {@code @Tool} 方法翻译成索引的输入。
     *
     * <p>注意这里就是 Advisor 内部 {@code initializeSession} 做的事：
     * {@code ToolDefinition.name()} → {@code ToolReference.toolName()}，
     * {@code ToolDefinition.description()} → {@code ToolReference.summary()}。
     * <b>索引能看到的只有这两个字段</b>，参数 Schema 是不参与检索的
     * （这也是默认 regex 策略在「按参数选工具」场景下会失手的原因之一）。
     */
    private List<ToolReference> catalog() {
        List<ToolReference> cached = this.catalog;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.catalog == null) {
                ToolCallback[] callbacks = ToolCallbacks.from(crmTools);
                this.catalog = Arrays.stream(callbacks)
                        .map(cb -> ToolReference.builder()
                                .toolName(cb.getToolDefinition().name())
                                .summary(cb.getToolDefinition().description())
                                .build())
                        .toList();
            }
            return this.catalog;
        }
    }

    private ChatClient clientFor(SearchStrategy strategy) {
        return clients.computeIfAbsent(strategy, this::buildClient);
    }

    /**
     * 手工装配一条「指定检索策略」的完整链路。
     *
     * <p>与 Stage 6 主入口的三点差异，值得逐条对照：
     * <ol>
     *   <li>{@code ChatClient.builder(chatModel)} 手工构建而不是注入自动装配的 Builder ——
     *       自动装配那条链上已经挂了一个用 regex 的 Advisor</li>
     *   <li>{@code .toolIndex(...)} 显式指定索引实现，绕过 {@code tool-index-type} 配置项</li>
     *   <li>仍然必须注入会话 ID，否则 advisor 取不到会话就抛
     *       {@code IllegalArgumentException}</li>
     * </ol>
     */
    private ChatClient buildClient(SearchStrategy strategy) {
        ToolSearchToolCallingAdvisor toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(strategy.newIndex())
                .maxResults(5)
                .build();

        return ChatClient.builder(chatModel)
                .defaultSystem(LAB_SYSTEM)
                .defaultTools(crmTools)
                .defaultAdvisors(toolSearchAdvisor, new SimpleLoggerAdvisor())
                .build();
    }

    // ------------------------------------------------------------------
    // 响应模型
    // ------------------------------------------------------------------

    /** 单个命中工具。 */
    public record LabToolHit(String toolName, String category, Double score, String description) {
    }

    /** 单条策略的检索结果。 */
    public record LabSearchResult(String strategy,
                                  String strategyLabel,
                                  String searchType,
                                  String categoryFilter,
                                  int totalMatches,
                                  Long tookMs,
                                  List<LabToolHit> hits) {

        static LabSearchResult of(SearchStrategy strategy, ToolSearchResponse response, String categoryFilter) {
            List<LabToolHit> hits = response.toolReferences().stream()
                    .map(ref -> new LabToolHit(
                            ref.toolName(),
                            categoryCode(ToolDictionary.categoryOf(ref.toolName())),
                            ref.relevanceScore(),
                            abbreviate(ref.summary())))
                    .toList();
            ToolSearchResponse.SearchMetadata meta = response.searchMetadata();
            return new LabSearchResult(
                    strategy.code(),
                    strategy.label(),
                    meta == null ? null : meta.searchType(),
                    categoryFilter,
                    response.totalMatches() == null ? hits.size() : response.totalMatches(),
                    meta == null ? null : meta.searchTimeMs(),
                    hits);
        }

        private static String categoryCode(ToolCategory category) {
            return category == null ? null : category.code();
        }

        private static String abbreviate(String text) {
            if (text == null) {
                return null;
            }
            return text.length() <= 40 ? text : text.substring(0, 40) + "…";
        }
    }

    /** 四策略并排对比结果。 */
    public record LabCompareResult(String query,
                                   String categoryFilter,
                                   int toolCount,
                                   List<LabSearchResult> results) {
    }

    /** 工具的业务画像。 */
    public record LabToolProfile(String toolName,
                                 String category,
                                 String categoryLabel,
                                 List<String> synonyms,
                                 String description,
                                 boolean dictionaryRegistered) {
    }
}
