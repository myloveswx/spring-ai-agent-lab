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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8 —— <b>多知识库</b>测试。
 *
 * <h2>它到底在证明什么</h2>
 * 「能创建两个库」这件事本身很好验证（调一次接口看一眼列表）。
 * 真正值得写成断言的是下面这三条 —— 它们才是多知识库<b>能不能用</b>的分界线：
 * <ol>
 *   <li><b>隔离</b>：往 kb-a 灌的文档，在 kb-b 里一条都搜不到。
 *       这条如果挂了，用户会看到「知识库2 里居然有知识库1 的资料」，
 *       而且通常是「你以为删干净了、其实没有」的那种隐蔽 bug。</li>
 *   <li><b>互不影响</b>：删掉 / 清空一个库，另一个库原样不动。
 *       这条挂了意味着共享了可变状态 —— 典型的物理隔离没做彻底。</li>
 *   <li><b>边界</b>：非法 id、重复 id、不存在的库、删默认库，
 *       必须给出明确的状态码，而不是 500 或者静默成功。</li>
 * </ol>
 *
 * <h2>为什么每个用例都自己准备前置状态</h2>
 * JUnit 不保证方法执行顺序。所以这里不用 {@code @BeforeEach} 造一份公共数据，
 * 而是让每个用例自己 {@code ensureKb} + {@code clear}——
 * <b>用例之间零顺序依赖，单独挑一个跑也能通过</b>。
 * 这比「跑单个用例就挂、必须跑全类才行」那种测试可靠得多。
 *
 * <h2>用独立的知识库根目录</h2>
 * {@code agentlab.rag.store-root=target/stage8-multikb-test-store} ——
 * 与 {@link Stage8RagTest} 完全隔开，互不干扰，也绝不碰真实知识库。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.deepseek.api-key=test-key-for-context-load",
                "spring.ai.mcp.client.enabled=false",
                "agentlab.rag.store-root=target/stage8-multikb-test-store",
                "agentlab.rag.warmup=false"
        })
class Stage8MultiKnowledgeBaseTest {

    private static final String STORE_ROOT = "target/stage8-multikb-test-store";

    /** 一份内容独一无二的文档 —— 「AAA 项目 / 绿灯率 97%」在示例语料里不存在，不可能被别的库蒙对。 */
    private static final String AAA_DOC = """
            {"title":"AAA 项目说明",
             "content":"AAA 项目的验收标准是绿灯率 97%，唯一责任人是张三，代号 T-2049。"}
            """;

    private static final String BBB_DOC = """
            {"title":"BBB 项目说明",
             "content":"BBB 项目的验收标准是单车日活 3.8 万，唯一责任人是李四，代号 T-3072。"}
            """;

    @BeforeAll
    static void cleanSlate() throws IOException {
        deleteRecursively(Path.of(STORE_ROOT));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    // ==================================================================
    // HTTP 小工具
    // ==================================================================

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET().build());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return send(request(path).DELETE().build());
    }

    /** 发 JSON 体。注意必须显式指定 UTF-8 —— 用默认编码发中文会变成乱码。 */
    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return send(request(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), "GET " + path + " 应返回 200，实际："
                + response.statusCode() + " / " + response.body());
        return json.readTree(response.body());
    }

    private JsonNode postJsonOk(String path, String body) throws Exception {
        HttpResponse<String> response = postJson(path, body);
        assertEquals(200, response.statusCode(), "POST " + path + " 应返回 200，实际："
                + response.statusCode() + " / " + response.body());
        return json.readTree(response.body());
    }

    private String encode(String raw) {
        // 中文必须预编码：未编码的中文字节会被 Tomcat 挡在 Spring 之前（返回 HTML 400 页）。
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** 当前全部知识库的 id。 */
    private Set<String> baseIds() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode kb : getJson("/stage8/kb").get("knowledgeBases")) {
            ids.add(kb.get("id").asText());
        }
        return ids;
    }

    /** 建库（已存在则跳过）—— 让用例不依赖执行顺序。 */
    private void ensureKb(String id) throws Exception {
        if (baseIds().contains(id)) {
            return;
        }
        postJsonOk("/stage8/kb", "{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}");
    }

    /** 清空某库内容（保留库），用于让用例从干净状态开始。 */
    private void clearKb(String id) throws Exception {
        delete("/stage8/kb/" + id + "/clear");
    }

    /** 在某库里检索，返回命中条数。 */
    private int hitCount(String kbId, String query) throws Exception {
        return getJson("/stage8/kb/" + kbId + "/search?query=" + encode(query) + "&threshold=0.3").size();
    }

    // ==================================================================
    // 1. 建库 / 列库
    // ==================================================================

    @Test
    @DisplayName("建库：新库应出现在列表里，且默认库一直都在")
    void createdBaseShouldAppearInList() throws Exception {
        delete("/stage8/kb/kb-alpha");
        JsonNode created = postJsonOk("/stage8/kb",
                "{\"id\":\"kb-alpha\",\"name\":\"知识库A\",\"description\":\"只放 A 线资料\"}");

        assertEquals("kb-alpha", created.get("id").asText());
        assertEquals("知识库A", created.get("name").asText(), "id 与 name 是两件事：id 走路径与目录，name 只给人看");

        Set<String> ids = baseIds();
        assertTrue(ids.contains("kb-alpha"), "新建的库应出现在列表里：" + ids);
        assertTrue(ids.contains(KnowledgeBaseRegistry.DEFAULT_ID), "默认库必须永远在：" + ids);
    }

    @Test
    @DisplayName("列库：应报出根目录与库数量")
    void listShouldReportRootAndCount() throws Exception {
        JsonNode list = getJson("/stage8/kb");
        assertFalse(list.get("root").asText().isBlank(), "应报出知识库根目录");
        assertEquals(list.get("count").asInt(), list.get("knowledgeBases").size(),
                "count 必须与列表长度一致");
        assertEquals(KnowledgeBaseRegistry.DEFAULT_ID, list.get("defaultId").asText());
    }

    // ==================================================================
    // 2. 隔离 —— 多知识库最核心的保证
    // ==================================================================

    @Test
    @DisplayName("隔离：往 kb-a 灌的文档，在 kb-b 里一条都搜不到")
    void searchShouldBeIsolatedBetweenBases() throws Exception {
        ensureKb("kb-a");
        ensureKb("kb-b");
        clearKb("kb-a");
        clearKb("kb-b");

        postJsonOk("/stage8/kb/kb-a/ingest", AAA_DOC);

        assertTrue(hitCount("kb-a", "AAA 项目的验收标准") > 0,
                "kb-a 里应搜得到刚入库的内容");
        assertEquals(0, hitCount("kb-b", "AAA 项目的验收标准"),
                "kb-b 里搜到了 kb-a 的内容 —— 物理隔离失效了");
    }

    @Test
    @DisplayName("隔离：同一个问题在两个库里各自命中自己的资料")
    void sameQuestionShouldHitDifferentBases() throws Exception {
        ensureKb("kb-a");
        ensureKb("kb-b");
        clearKb("kb-a");
        clearKb("kb-b");

        postJsonOk("/stage8/kb/kb-a/ingest", AAA_DOC);
        postJsonOk("/stage8/kb/kb-b/ingest", BBB_DOC);

        JsonNode hitsA = getJson("/stage8/kb/kb-a/search?query=" + encode("验收标准") + "&threshold=0.3");
        JsonNode hitsB = getJson("/stage8/kb/kb-b/search?query=" + encode("验收标准") + "&threshold=0.3");

        assertTrue(hitsA.size() > 0 && hitsB.size() > 0, "两个库都应命中");
        assertEquals("AAA 项目说明", hitsA.get(0).get("title").asText(),
                "kb-a 的 Top-1 应是 A 项目的资料");
        assertEquals("BBB 项目说明", hitsB.get(0).get("title").asText(),
                "kb-b 的 Top-1 应是 B 项目的资料 —— 同一个 query，两边答案不同，这正是多库的意义");
    }

    @Test
    @DisplayName("隔离：示例语料分别载入两个库，互不串台")
    void sampleDocsShouldNotLeakAcrossBases() throws Exception {
        ensureKb("kb-s1");
        ensureKb("kb-s2");
        clearKb("kb-s1");
        clearKb("kb-s2");

        postJsonOk("/stage8/kb/kb-s1/ingest-sample", "{}");
        postJsonOk("/stage8/kb/kb-s2/ingest-sample", "{}");

        JsonNode stats1 = getJson("/stage8/kb/kb-s1/stats");
        JsonNode stats2 = getJson("/stage8/kb/kb-s2/stats");

        assertEquals(3, stats1.get("documents").asInt(), "kb-s1 应有 3 篇");
        assertEquals(3, stats2.get("documents").asInt(), "kb-s2 应有 3 篇");
        assertEquals(stats1.get("chunks").asInt(), stats2.get("chunks").asInt(),
                "同样的语料在两个库里应切出同样多的块");

        // 清掉 s1，s2 必须原样不动 —— 证明两者不共享可变状态
        clearKb("kb-s1");
        assertEquals(0, getJson("/stage8/kb/kb-s1/stats").get("chunks").asInt());
        assertEquals(3, getJson("/stage8/kb/kb-s2/stats").get("documents").asInt(),
                "清空 kb-s1 影响到了 kb-s2 —— 两个库共享了状态");
    }

    // ==================================================================
    // 3. 删库
    // ==================================================================

    @Test
    @DisplayName("删库：被删的库从列表消失，再访问返回 404，其它库不受影响")
    void deletingOneBaseShouldNotAffectAnother() throws Exception {
        ensureKb("kb-keep");
        ensureKb("kb-del");
        clearKb("kb-keep");
        clearKb("kb-del");

        postJsonOk("/stage8/kb/kb-keep/ingest", AAA_DOC);
        postJsonOk("/stage8/kb/kb-del/ingest", BBB_DOC);

        JsonNode deleted = json.readTree(delete("/stage8/kb/kb-del").body());
        assertTrue(deleted.get("deleted").asBoolean(), "应真的删掉了一个库");

        Set<String> ids = baseIds();
        assertFalse(ids.contains("kb-del"), "被删的库不该还在列表里：" + ids);
        assertEquals(404, get("/stage8/kb/kb-del/stats").statusCode(),
                "访问已删除的库应返回 404（而不是 500）");

        assertTrue(hitCount("kb-keep", "AAA 项目的验收标准") > 0,
                "删掉 kb-del 后 kb-keep 的内容应原样还在");
    }

    @Test
    @DisplayName("删库：默认库不允许删除（不带 kbId 的老接口都落在它上面）")
    void defaultBaseShouldNotBeDeletable() throws Exception {
        HttpResponse<String> response = delete("/stage8/kb/" + KnowledgeBaseRegistry.DEFAULT_ID);
        assertEquals(400, response.statusCode(),
                "删默认库应被拒绝并返回 400：" + response.body());
        assertTrue(baseIds().contains(KnowledgeBaseRegistry.DEFAULT_ID), "默认库必须还在");
    }

    // ==================================================================
    // 4. 边界
    // ==================================================================

    @Test
    @DisplayName("边界：非法 id、重复 id、未知库都应给出明确状态码")
    void edgeCasesShouldReturnClearStatusCodes() throws Exception {
        // 中文 id：它同时是磁盘目录名，中文目录名在 Git Bash / curl / 日志里都有编码风险
        assertEquals(400, postJson("/stage8/kb", "{\"id\":\"知识库1\"}").statusCode(),
                "中文 id 应被拒绝 —— 中文请写在 name 字段里");

        // 带路径分隔符的 id 更是必须挡住，否则可以逃出知识库根目录
        assertEquals(400, postJson("/stage8/kb", "{\"id\":\"../evil\"}").statusCode(),
                "带 .. 的 id 应被拒绝（目录穿越）");

        ensureKb("kb-dup");
        assertEquals(400, postJson("/stage8/kb", "{\"id\":\"kb-dup\"}").statusCode(),
                "重复创建同名库应被拒绝");
        assertEquals(400, postJson("/stage8/kb", "{\"id\":\"\"}").statusCode(),
                "空 id 应被拒绝（@NotBlank）");

        assertEquals(404, get("/stage8/kb/no-such-kb/stats").statusCode(),
                "未知库应返回 404");
        assertEquals(404, get("/stage8/kb/no-such-kb/search?query=" + encode("随便") + "").statusCode(),
                "未知库的检索也应 404，而不是静默返回空列表");
    }

    @Test
    @DisplayName("兼容：不带 kbId 的老端点 = 默认库")
    void legacyPathsShouldMapToDefaultBase() throws Exception {
        clearKb(KnowledgeBaseRegistry.DEFAULT_ID);
        postJsonOk("/stage8/kb/ingest", AAA_DOC);

        // 老路径检索
        assertTrue(hitCount(KnowledgeBaseRegistry.DEFAULT_ID, "AAA 项目的验收标准") > 0,
                "老路径写入的内容应在默认库里");

        // 新路径检索同一个库，结果必须一致
        assertEquals(
                getJson("/stage8/kb/search?query=" + encode("AAA 项目的验收标准") + "&threshold=0.3").size(),
                getJson("/stage8/kb/" + KnowledgeBaseRegistry.DEFAULT_ID
                        + "/search?query=" + encode("AAA 项目的验收标准") + "&threshold=0.3").size(),
                "GET /stage8/kb/search 与 GET /stage8/kb/default/search 必须是同一个库");
    }
}
