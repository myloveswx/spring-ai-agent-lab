package com.agentlab.stage8;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.agentlab.stage8.KnowledgeBaseService.Hit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8（L1 朴素 RAG）测试。
 *
 * <h2>这个测试为什么跑得动、且值得跑</h2>
 * RAG 链路里最难测的一直是「检索对不对」，因为它依赖模型输出、带随机性。
 * 但<b>向量检索这一层是确定性的</b>：同一个 (语料, query, topK, 阈值) 必然得到同一组命中。
 * 所以下面这些断言全部是稳定的 —— 这正是 L1 要先做「检索接口」而不是先做「问答接口」的原因：
 * <b>把可以确定化的部分确定化，才有可回归的基线。</b>
 *
 * <h2>完全离线</h2>
 * 嵌入用本机 ONNX 模型，检索用内存向量库，
 * <b>整个测试不碰 DeepSeek、不联网</b>（DeepSeek 的 Key 是假的，且不会被真正调用）。
 * 这也是本地嵌入方案（方案 A）相对于云端 embedding API 的一个实际好处：
 * 单测可以完整覆盖 RAG 的检索侧，不需要 mock 网络。
 *
 * <h2>两个刻意的测试配置</h2>
 * <ul>
 *   <li>{@code agentlab.rag.store-path=target/stage8-test-store.json}
 *       —— 把落盘位置指到构建目录，<b>绝不污染真实知识库</b>；
 *       否则一次 {@code clear()} 就会把开发时攒的向量全删了。</li>
 *   <li>{@code agentlab.rag.warmup=false} —— 预热是给「真实启动」消除首次延迟用的
 *       （详见 {@code Stage8RagConfig#ragWarmupRunner}），测试里没必要付这份开销。</li>
 * </ul>
 *
 * <p>{@link #cleanSlate()} 那个 {@code @BeforeAll} 也不是多余的：知识库会在
 * {@code @PostConstruct} 阶段（也就是 Spring 上下文加载时）从磁盘恢复历史状态。
 * 如果上一次运行留下的清单和向量文件还在，测试就会从一个「有历史」的状态开始 ——
 * <b>测试必须自己负责把外部状态清零</b>，不能指望上一次跑完是干净的。
 * 好在 JUnit 的 {@code @BeforeAll} 一定早于「创建测试实例」，
 * 而 Spring 正是在创建实例时才加载上下文，所以这个时机是安全的。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.deepseek.api-key=test-key-for-context-load",
                "spring.ai.mcp.client.enabled=false",
                "agentlab.rag.store-path=target/stage8-test-store.json",
                "agentlab.rag.warmup=false"
        })
class Stage8RagTest {

    /** 与 @SpringBootTest 里的 agentlab.rag.store-path 保持一致。 */
    private static final String STORE_PATH = "target/stage8-test-store.json";

    /**
     * 清掉上一次运行留下的落盘文件，让每次测试都从「空知识库」开始。
     * <p>必须用 {@code @BeforeAll}（早于 Spring 加载上下文），
     * 因为恢复逻辑跑在上下文初始化阶段，晚于 {@code @BeforeEach} 就没意义了。
     */
    @BeforeAll
    static void cleanSlate() throws IOException {
        Files.deleteIfExists(Path.of(STORE_PATH));
        Files.deleteIfExists(Path.of(STORE_PATH + ".manifest.tsv"));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private KnowledgeBaseService knowledgeBase;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), "接口 " + path + " 应返回 200，实际："
                + response.statusCode() + " / " + response.body());
        return json.readTree(response.body());
    }

    private String encode(String raw) {
        // 中文必须预编码：未编码的中文字节会被 Tomcat 挡在 Spring 之前，返回 HTML 400 页。
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // HTTP 层：证明接口真的能用，而不是「Bean 装配上了」
    // ==================================================================

    @Test
    @DisplayName("入库：示例语料应被切成多块并写入向量库")
    void ingestSampleShouldProduceChunks() throws Exception {
        JsonNode records = json.readTree(post("/stage8/kb/ingest-sample").body());

        assertEquals(3, records.size(), "resources/rag 下应有 3 篇示例文档");

        int totalChunks = 0;
        for (JsonNode record : records) {
            int chunks = record.get("chunkIds").size();
            assertTrue(chunks > 0, "文档「" + record.get("title").asText() + "」切块后不应为空");
            assertTrue(record.get("source").asText().endsWith(".md"), "source 应为文件名");
            totalChunks += chunks;
        }
        // 切块粒度是否合理，看这个数：3 篇各约 1500 字的中文文档，
        // chunkSize=400 / minChunkSizeChars=200 下大致每篇 3~6 块。
        assertTrue(totalChunks >= 6 && totalChunks <= 30,
                "总块数 " + totalChunks + " 明显偏离预期（3~5 块/篇），检查切块参数");
    }

    @Test
    @DisplayName("检索：中文语义查询应命中正确来源的片段（不经过大模型）")
    void searchShouldHitTheRightDocument() throws Exception {
        post("/stage8/kb/ingest-sample");

        JsonNode hits = getJson("/stage8/kb/search?query=" + encode("年假有几天") + "&topK=4&threshold=0.3");

        assertTrue(hits.size() > 0, "「年假有几天」应至少命中一个片段");
        assertEquals("01-员工手册.md", hits.get(0).get("source").asText(),
                "Top-1 应来自员工手册；若命中别的文档，说明切块或嵌入出了问题");

        // 「12 天」这个具体数字只出现在员工手册的年假表格里 ——
        // 模型不可能凭先验编出来，所以「检索到的上下文里含 12」是 RAG 能答对的必要条件。
        StringBuilder topTexts = new StringBuilder();
        for (JsonNode hit : hits) {
            topTexts.append(hit.get("text").asText());
        }
        assertTrue(topTexts.toString().contains("12"),
                "Top-4 的片段里应包含年假表格中的「12 天」；否则检索没召回关键块");

        // 每个片段都必须带着「我是谁、我来自哪、我是第几块」三样信息，
        // 否则检索回来之后你无法判断是原文没写、还是这块被切坏了。
        for (JsonNode hit : hits) {
            assertFalse(hit.get("title").asText().isBlank(), "片段缺少 title");
            assertTrue(hit.get("chunkIndex").asInt() >= 0, "片段缺少 chunkIndex");
            assertTrue(hit.get("score").asDouble() > 0.3, "score 应高于请求阈值");
        }
    }

    @Test
    @DisplayName("检索：阈值调高应把弱命中全部过滤掉")
    void thresholdShouldFilterWeakHits() throws Exception {
        post("/stage8/kb/ingest-sample");

        JsonNode loose = getJson("/stage8/kb/search?query=" + encode("年假有几天") + "&threshold=0.2");
        JsonNode strict = getJson("/stage8/kb/search?query=" + encode("年假有几天") + "&threshold=0.999");

        assertTrue(loose.size() > 0, "低阈值下应有命中");
        assertEquals(0, strict.size(),
                "阈值 0.999 下应过滤掉全部命中 —— 这演示了 similarityThreshold 是检索精度的主要旋钮之一");
    }

    @Test
    @DisplayName("统计：应能报出文档数、块数、向量维度与切块参数")
    void statsShouldReportKnowledgeBaseFacts() throws Exception {
        post("/stage8/kb/ingest-sample");

        JsonNode stats = getJson("/stage8/kb/stats");

        assertEquals(3, stats.get("documents").asInt(), "应有 3 篇文档");
        assertTrue(stats.get("chunks").asInt() > 0, "块数应大于 0");
        assertEquals(512, stats.get("dimensions").asInt(), "bge-small-zh-v1.5 的输出维度是 512");
        assertEquals(400, stats.get("chunkSize").asInt(), "切块参数应来自 application.yml");
        assertTrue(stats.get("catalog").isArray() && stats.get("catalog").size() == 3,
                "catalog 应列出全部已入库文档");
    }

    @Test
    @DisplayName("检索：换一个语义完全不同的查询，应命中另一篇文档")
    void searchShouldRouteToDifferentDocument() throws Exception {
        post("/stage8/kb/ingest-sample");

        JsonNode hits = getJson("/stage8/kb/search?query=" + encode("大促期间能不能发版") + "&topK=3&threshold=0.3");

        assertTrue(hits.size() > 0, "「大促期间能不能发版」应有命中");
        assertEquals("03-运维值班与故障响应.md", hits.get(0).get("source").asText(),
                "发布窗口/冻结期的规则在运维规范里，Top-1 应命中它");
    }

    // ==================================================================
    // 服务层：把确定性的行为钉死
    // ==================================================================

    @Test
    @DisplayName("检索结果必须按相似度降序 —— 否则 topK 截断就是错的")
    void hitsMustBeSortedByScoreDesc() {
        knowledgeBase.ingestSampleDocs();

        List<Hit> hits = knowledgeBase.searchAsHits("报销要附什么材料", 6, 0.0);
        assertTrue(hits.size() >= 2, "应有多个命中用于比较排序");

        for (int i = 1; i < hits.size(); i++) {
            assertNotNull(hits.get(i).score(), "score 不应为空");
            assertTrue(hits.get(i - 1).score() >= hits.get(i).score(),
                    "第 " + i + " 条分数高于前一条，排序被破坏："
                            + hits.get(i - 1).score() + " -> " + hits.get(i).score());
        }
    }

    @Test
    @DisplayName("重复载入同一份语料必须幂等，不能把向量库灌成好几份")
    void reingestShouldBeIdempotent() {
        knowledgeBase.clear();
        knowledgeBase.ingestSampleDocs();
        int first = knowledgeBase.stats().chunks();

        knowledgeBase.ingestSampleDocs();
        int second = knowledgeBase.stats().chunks();

        assertEquals(first, second,
                "同一来源重复载入后块数变化（" + first + " -> " + second + "），removeBySource 没生效");
        assertEquals(3, knowledgeBase.stats().documents(), "文档数应保持 3");
    }

    @Test
    @DisplayName("清空后检索应为空 —— 用来重置「无 RAG / 有 RAG」的对照实验")
    void clearShouldEmptyTheKnowledgeBase() {
        knowledgeBase.ingestSampleDocs();
        assertTrue(knowledgeBase.stats().chunks() > 0);

        knowledgeBase.clear();

        assertEquals(0, knowledgeBase.stats().chunks());
        assertEquals(0, knowledgeBase.stats().documents());
        assertTrue(knowledgeBase.search("年假有几天", null, null).isEmpty(),
                "清空后不应再检索到任何片段");
    }

    @Test
    @DisplayName("落盘与载入：向量应能持久化并恢复")
    void saveAndLoadShouldRoundTrip() {
        knowledgeBase.ingestSampleDocs();
        int chunks = knowledgeBase.stats().chunks();

        var saved = knowledgeBase.saveToDisk();
        assertTrue(((Number) saved.get("sizeBytes")).longValue() > 0, "落盘文件不应为空");
        assertEquals(chunks, ((Number) saved.get("chunks")).intValue());

        // 载回之后检索结果必须还在，且内容一致 ——
        // 如果只 load 了向量却丢了清单，这里能立刻发现。
        var loaded = knowledgeBase.loadFromDisk();
        assertEquals(chunks, ((Number) loaded.get("chunks")).intValue());
        assertTrue(knowledgeBase.search("年假有几天", 1, 0.3).size() > 0,
                "载入后应仍能检索到片段");
    }
}
