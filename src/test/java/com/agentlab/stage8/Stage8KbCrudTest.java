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
import java.util.Map;
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
 * Stage 8 —— <b>库级 / 文档级的更新与查询</b>测试。
 *
 * <h2>它补的是哪个洞</h2>
 * 加多知识库之后，这套接口的 CRUD 其实只齐了一半：
 * <pre>
 *   C  create      建库、入库、载入示例语料
 *   R  read        列库、stats、纯向量检索
 *   U  update      ← 完全没有：库名建完改不了，文档改不了（只能清空整库重灌）
 *   D  delete      删库、清空整库 —— 但删不掉「一篇」
 * </pre>
 * 本类钉死的就是补上的 U、以及粒度降到「一篇」的 R / D。
 *
 * <h2>三个最容易搞错、也最值得写成断言的点</h2>
 * <ol>
 *   <li><b>覆盖更新后 docId 必须不变</b>。变了的话「PUT 同一个 docId」就不幂等，
 *       调用方每改一次就得重新查一遍 id，稍不留神就写出「越改越多篇」的 bug。</li>
 *   <li><b>默认是 append、不是 upsert</b>。同一篇灌两次得到两份，这看着像 bug，
 *       其实是刻意的：接口入库的 source 默认都是 {@code "api"}，
 *       若默认按 source 覆盖，连灌三篇不同文档就会互相删。
 *       这里把两种模式的行为都断言住，免得日后有人「顺手改成默认覆盖」。</li>
 *   <li><b>部分更新语义</b>。PUT 只传 name 时，description 必须原样保留 ——
 *       否则「只改名字」会静默把备注清空。</li>
 * </ol>
 *
 * <h2>用独立的知识库根目录</h2>
 * {@code agentlab.rag.store-root=target/stage8-crud-test-store}，
 * 与另外两个 Stage 8 测试类互不干扰，也绝不碰真实知识库。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.deepseek.api-key=test-key-for-context-load",
                "spring.ai.mcp.client.enabled=false",
                "agentlab.rag.store-root=target/stage8-crud-test-store",
                "agentlab.rag.warmup=false"
        })
class Stage8KbCrudTest {

    private static final String STORE_ROOT = "target/stage8-crud-test-store";

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

    /** 发 JSON 体。必须显式指定 UTF-8 —— 用默认编码发中文会变成乱码。 */
    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return send(request(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private HttpResponse<String> putJson(String path, String body) throws Exception {
        return send(request(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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

    private JsonNode putJsonOk(String path, String body) throws Exception {
        HttpResponse<String> response = putJson(path, body);
        assertEquals(200, response.statusCode(), "PUT " + path + " 应返回 200，实际："
                + response.statusCode() + " / " + response.body());
        return json.readTree(response.body());
    }

    private JsonNode deleteJson(String path) throws Exception {
        HttpResponse<String> response = delete(path);
        assertEquals(200, response.statusCode(), "DELETE " + path + " 应返回 200，实际："
                + response.statusCode() + " / " + response.body());
        return json.readTree(response.body());
    }

    /** 拼 JSON 请求体 —— 交给 Jackson 转义，免得手写引号把中文搞坏。 */
    private String body(Map<String, String> fields) throws Exception {
        return json.writeValueAsString(fields);
    }

    private String encode(String raw) {
        // 中文必须预编码：未编码的中文字节会被 Tomcat 挡在 Spring 之前（返回 HTML 400 页）。
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private Set<String> baseIds() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode kb : getJson("/stage8/kb").get("knowledgeBases")) {
            ids.add(kb.get("id").asText());
        }
        return ids;
    }

    private void ensureKb(String id) throws Exception {
        if (baseIds().contains(id)) {
            return;
        }
        postJsonOk("/stage8/kb", body(Map.of("id", id, "name", id)));
    }

    private void clearKb(String id) throws Exception {
        delete("/stage8/kb/" + id + "/clear");
    }

    /** 入库一篇，返回它的 docId。 */
    private String ingestOne(String kbId, String title, String content, String source) throws Exception {
        Map<String, String> payload = source == null
                ? Map.of("title", title, "content", content)
                : Map.of("title", title, "content", content, "source", source);
        return postJsonOk("/stage8/kb/" + kbId + "/ingest", body(payload)).get("id").asText();
    }

    private int hitCount(String kbId, String query) throws Exception {
        return getJson("/stage8/kb/" + kbId + "/search?query=" + encode(query) + "&threshold=0.2").size();
    }

    /**
     * 在库里检索，判断结果里是否出现了指定标题的文档。
     *
     * <p>为什么用「标题」判定，而不是「命中条数」：相似度阈值对短查询很不友好 ——
     * {@code threshold=0.2} 时，一篇毫不相关的文档也常常能挤进结果。
     * 那样断言「命中数 == 0」会得到一个<b>与被测行为无关的假失败</b>
     * （本测试第一版就踩了这个坑）。真正要验证的是「这篇文档还在不在结果里」，
     * 所以这里用 {@code threshold=0.0} 全召回，再按标题过滤。
     */
    private boolean anyHitWithTitle(String kbId, String query, String title) throws Exception {
        JsonNode hits = getJson("/stage8/kb/" + kbId + "/search?query=" + encode(query)
                + "&topK=10&threshold=0.0");
        for (JsonNode hit : hits) {
            if (title.equals(hit.get("title").asText())) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 1. 库级：查询与更新
    // ==================================================================

    @Test
    @DisplayName("查库：GET /stage8/kb/{kbId} 应返回这一个库的元数据与统计")
    void detailShouldReturnSingleBase() throws Exception {
        ensureKb("kb-detail");
        clearKb("kb-detail");
        ingestOne("kb-detail", "甲文档", "甲文档的内容是流水线编号 P-11。", null);

        JsonNode detail = getJson("/stage8/kb/kb-detail");

        assertEquals("kb-detail", detail.get("id").asText());
        assertEquals("kb-detail", detail.get("name").asText());
        assertEquals(1, detail.get("documents").asInt());
        assertTrue(detail.get("chunks").asInt() > 0);
        assertTrue(detail.get("storeFileExists").asBoolean(), "入库后向量文件应已落盘");
        assertFalse(detail.get("dir").asText().isBlank(), "应报出这个库的目录");
        assertFalse(detail.get("createdAt").asText().isBlank(), "应报出创建时间");

        assertEquals(404, get("/stage8/kb/no-such-base").statusCode(),
                "查不存在的库应 404，而不是 500 或空对象");
    }

    @Test
    @DisplayName("改库：只传 name 时，备注必须原样保留（部分更新语义）")
    void updateShouldBePartial() throws Exception {
        delete("/stage8/kb/kb-upd");
        postJsonOk("/stage8/kb", body(Map.of("id", "kb-upd", "name", "旧名字", "description", "旧备注")));

        JsonNode updated = putJsonOk("/stage8/kb/kb-upd", body(Map.of("name", "新名字")));

        assertEquals("新名字", updated.get("name").asText());
        assertEquals("旧备注", updated.get("description").asText(),
                "没传 description 就不该被动 —— 否则「只改名字」会静默清空备注");
        assertEquals("kb-upd", updated.get("id").asText(), "id 不参与更新");

        // 元数据必须落盘：重启后靠 kb-index.tsv 恢复，只在内存里改等于没改。
        boolean seenInList = false;
        for (JsonNode kb : getJson("/stage8/kb").get("knowledgeBases")) {
            if ("kb-upd".equals(kb.get("id").asText())) {
                assertEquals("新名字", kb.get("name").asText(), "列表里也应看到新名字");
                seenInList = true;
            }
        }
        assertTrue(seenInList, "更新后的库应还在列表里");

        // 显式传空串则清空备注 —— 「不传」与「传空」必须是两种不同的意思
        JsonNode cleared = putJsonOk("/stage8/kb/kb-upd", body(Map.of("description", "")));
        assertEquals("", cleared.get("description").asText(), "传空串应清空备注");
        assertEquals("新名字", cleared.get("name").asText(), "没传 name 就该保持新名字");
    }

    @Test
    @DisplayName("改库：id 是保留字 / 库不存在，都应给出明确状态码")
    void updateEdgeCasesShouldBeClear() throws Exception {
        assertEquals(404, putJson("/stage8/kb/no-such-base-2", body(Map.of("name", "x"))).statusCode(),
                "改不存在的库应 404");
    }

    @Test
    @DisplayName("建库：id 不能占用固定路径名（docs / search / stats 等保留字）")
    void reservedIdsShouldBeRejected() throws Exception {
        // 拿 "docs" 当库 id 的话，GET /stage8/kb/docs 永远会被「列文档」接口截走，
        // 这个库就成了「建得出来、却谁也访问不到」的幽灵 —— 所以在创建时就该失败。
        assertEquals(400, postJson("/stage8/kb", body(Map.of("id", "docs"))).statusCode(),
                "docs 是保留字");
        assertEquals(400, postJson("/stage8/kb", body(Map.of("id", "search"))).statusCode(),
                "search 是保留字");
        assertEquals(400, postJson("/stage8/kb", body(Map.of("id", "stats"))).statusCode(),
                "stats 是保留字");
    }

    // ==================================================================
    // 2. 文档级：查 / 改 / 删
    // ==================================================================

    @Test
    @DisplayName("列文档：应给出 docId，且它就是后续操作的凭据")
    void listDocsShouldExposeDocId() throws Exception {
        ensureKb("kb-list");
        clearKb("kb-list");
        String docId = ingestOne("kb-list", "甲文档", "甲文档的内容是流水线编号 P-11。", null);

        JsonNode docs = getJson("/stage8/kb/kb-list/docs");

        assertEquals(1, docs.size());
        assertEquals(docId, docs.get(0).get("docId").asText(),
                "列表里的 docId 应就是入库返回的 id");
        assertEquals("甲文档", docs.get(0).get("title").asText());
        assertEquals("api", docs.get(0).get("source").asText(), "不传 source 时默认为 api");
        assertTrue(docs.get(0).get("chunks").asInt() > 0);
        assertFalse(docs.get(0).get("ingestedAt").asText().isBlank());

        // 老路径（不带 kbId）= 默认库，也要能用
        assertTrue(getJson("/stage8/kb/docs").isArray(),
                "GET /stage8/kb/docs 应等价于查默认库的文档列表");
    }

    @Test
    @DisplayName("查文档：返回片段清单；docId 不存在应 404")
    void docDetailShouldListChunks() throws Exception {
        ensureKb("kb-docdetail");
        clearKb("kb-docdetail");
        String docId = ingestOne("kb-docdetail", "长文",
                "这是一段用于测试切块的长文本。".repeat(60), null);

        JsonNode detail = getJson("/stage8/kb/kb-docdetail/docs/" + docId);

        assertEquals(docId, detail.get("docId").asText());
        assertTrue(detail.get("chunks").asInt() >= 1);
        assertEquals(detail.get("chunks").asInt(), detail.get("chunkRefs").size(),
                "chunkRefs 的条数必须与 chunks 一致");
        assertEquals(0, detail.get("chunkRefs").get(0).get("index").asInt(),
                "片段序号应从 0 开始 —— 它是对照「问题落在第几个切口」的坐标");
        assertFalse(detail.get("chunkRefs").get(0).get("id").asText().isBlank());

        assertEquals(404, get("/stage8/kb/kb-docdetail/docs/no-such-doc").statusCode(),
                "查不存在的文档应 404");
    }

    @Test
    @DisplayName("改文档：覆盖更新后 docId 不变，检索到的是新内容、旧内容不残留")
    void replaceDocShouldKeepDocIdAndDropOldChunks() throws Exception {
        ensureKb("kb-rep");
        clearKb("kb-rep");
        String docId = ingestOne("kb-rep", "老标题", "老版本：单人审批上限 5000 元。", null);

        JsonNode updated = putJsonOk("/stage8/kb/kb-rep/docs/" + docId,
                body(Map.of("content", "新版本：单人审批上限 80000 元。")));

        assertEquals(docId, updated.get("id").asText(),
                "docId 必须保持不变 —— 变了的话「反复 PUT 同一个 id」就不幂等了");
        assertEquals("老标题", updated.get("title").asText(),
                "不传 title 应沿用原标题，而不是变成「未命名文档」");
        assertEquals(1, getJson("/stage8/kb/kb-rep/docs").size(),
                "覆盖更新之后库里必须还是只有一篇");

        JsonNode hits = getJson("/stage8/kb/kb-rep/search?query=" + encode("审批上限") + "&threshold=0.2");
        assertTrue(hits.size() > 0, "更新后应仍能检索到这篇文档");
        String text = hits.get(0).get("text").asText();
        assertTrue(text.contains("80000"), "检索到的应是更新后的内容，实际：" + text);
        assertFalse(text.contains("5000"),
                "旧内容不该残留 —— 残留说明旧片段没删干净（会变成删不掉的孤儿向量）");

        assertEquals(404, putJson("/stage8/kb/kb-rep/docs/no-such-doc",
                body(Map.of("content", "随便"))).statusCode(), "改不存在的文档应 404");
        assertEquals(400, putJson("/stage8/kb/kb-rep/docs/" + docId, body(Map.of("content", ""))).statusCode(),
                "content 必传（@NotBlank）—— 正文原文不在向量库里，没法「只改标题」");
    }

    @Test
    @DisplayName("删文档：只删这一篇，同库其它文档不受影响")
    void deleteDocShouldOnlyRemoveThatOne() throws Exception {
        ensureKb("kb-delone");
        clearKb("kb-delone");
        String keep = ingestOne("kb-delone", "留下的", "留下的文档里有编号 KA-1。", "manual-keep");
        String gone = ingestOne("kb-delone", "删掉的", "删掉的文档里有编号 GB-2。", "manual-gone");

        JsonNode result = deleteJson("/stage8/kb/kb-delone/docs/" + gone);

        assertEquals("kb-delone", result.get("knowledgeBase").asText());
        assertTrue(result.get("removedChunks").asInt() > 0, "应报出删掉了几个片段");

        JsonNode docs = getJson("/stage8/kb/kb-delone/docs");
        assertEquals(1, docs.size(), "只该剩下一篇");
        assertEquals(keep, docs.get(0).get("docId").asText());

        assertFalse(anyHitWithTitle("kb-delone", "编号 GB-2", "删掉的"),
                "删掉的文档不该还能被检索到");
        assertTrue(anyHitWithTitle("kb-delone", "编号 KA-1", "留下的"), "另一篇必须原样还在");

        assertEquals(404, delete("/stage8/kb/kb-delone/docs/no-such-doc").statusCode(),
                "删不存在的文档应 404（而不是静默成功）");
    }

    @Test
    @DisplayName("按来源删：同 source 的一批一起删，别的来源不动")
    void deleteBySourceShouldRemoveWholeBatch() throws Exception {
        ensureKb("kb-src");
        clearKb("kb-src");
        ingestOne("kb-src", "批次甲-1", "批次甲的第一篇，代号 SA-1。", "batch-x");
        ingestOne("kb-src", "批次甲-2", "批次甲的第二篇，代号 SA-2。", "batch-x");
        ingestOne("kb-src", "批次乙", "批次乙的唯一一篇，代号 SB-9。", "batch-y");
        assertEquals(3, getJson("/stage8/kb/kb-src/docs").size());

        JsonNode result = deleteJson("/stage8/kb/kb-src/docs?source=batch-x");

        assertEquals("batch-x", result.get("source").asText());
        assertTrue(result.get("removedChunks").asInt() >= 2);
        assertEquals(1, getJson("/stage8/kb/kb-src/docs").size(), "只该剩下 batch-y 那篇");
        assertFalse(anyHitWithTitle("kb-src", "批次甲的内容", "批次甲-1"), "被删的批次甲-1不该还在");
        assertFalse(anyHitWithTitle("kb-src", "批次甲的内容", "批次甲-2"), "被删的批次甲-2不该还在");
        assertTrue(anyHitWithTitle("kb-src", "代号 SB-9", "批次乙"), "别的来源必须不受影响");

        // source 必传：不传就等于清空整库，那是另一个接口的语义，不该在这里悄悄发生
        assertEquals(400, delete("/stage8/kb/kb-src/docs").statusCode(), "缺 source 应 400");
    }

    @Test
    @DisplayName("入库模式：默认 append 会得到两份，mode=upsert 按 source 覆盖只留一份")
    void ingestModeShouldControlOverwrite() throws Exception {
        ensureKb("kb-mode");
        clearKb("kb-mode");
        String weekly = body(Map.of(
                "title", "周报", "content", "本周周报：完成三件事，编号 W-41。", "source", "weekly"));

        postJsonOk("/stage8/kb/kb-mode/ingest", weekly);
        postJsonOk("/stage8/kb/kb-mode/ingest", weekly);
        assertEquals(2, getJson("/stage8/kb/kb-mode/docs").size(),
                "默认 append：同一篇灌两次就该是两份 —— 这是刻意的默认行为，"
                        + "因为 source 默认都是 api，默认覆盖会误删别的文档");

        postJsonOk("/stage8/kb/kb-mode/ingest?mode=upsert", weekly);
        assertEquals(1, getJson("/stage8/kb/kb-mode/docs").size(),
                "upsert：同 source 先删后入，库里应只剩一份");
        assertTrue(hitCount("kb-mode", "编号 W-41") > 0, "覆盖之后内容仍应检索得到");

        assertEquals(400, postJson("/stage8/kb/kb-mode/ingest?mode=whatever", weekly).statusCode(),
                "mode 只认 append / upsert，填错应 400 而不是悄悄按 append 处理");
    }

    // ==================================================================
    // 3. 老接口带上 kbId
    // ==================================================================

    @Test
    @DisplayName("兼容：DELETE /stage8/kb?kbId=xxx 清空指定库；不带参数仍是默认库")
    void legacyClearShouldHonorKbIdParam() throws Exception {
        ensureKb("kb-q");
        ensureKb("kb-q2");
        clearKb("kb-q");
        clearKb("kb-q2");
        ingestOne("kb-q", "甲", "甲的独有内容 QQ-1。", null);
        ingestOne("kb-q2", "乙", "乙的独有内容 QQ-2。", null);

        JsonNode result = deleteJson("/stage8/kb?kbId=kb-q");

        assertEquals("kb-q", result.get("knowledgeBase").asText(),
                "带 ?kbId= 时应清空指定的库，而不是默认库");
        assertEquals(0, getJson("/stage8/kb/kb-q/docs").size());
        assertEquals(1, getJson("/stage8/kb/kb-q2/docs").size(), "不该误伤别的库");

        // 不带参数 = 默认库，老行为一个字都不能变
        JsonNode dflt = deleteJson("/stage8/kb");
        assertEquals(KnowledgeBaseRegistry.DEFAULT_ID, dflt.get("knowledgeBase").asText());

        // 路径写法与 query 写法必须指向同一个库
        ensureKb("kb-q3");
        clearKb("kb-q3");
        ingestOne("kb-q3", "丙", "丙的独有内容 QQ-3。", null);
        JsonNode byPath = deleteJson("/stage8/kb/kb-q3/clear");
        assertEquals("kb-q3", byPath.get("knowledgeBase").asText());
    }

    @Test
    @DisplayName("兼容：对话的路径写法 /stage8/kb/{kbId}/chat 已注册（缺参数应 400 而非 404）")
    void chatPathFormShouldBeRouted() throws Exception {
        ensureKb("kb-chat");

        // 这里不真调模型（测试用的是假 Key）。
        // 不传 message 会命中参数校验直接 400 —— 足以证明「路由注册了、且通到了这个方法」；
        // 若是 404，说明路径写法根本没生效。
        assertEquals(400, get("/stage8/kb/kb-chat/chat").statusCode(),
                "路径写法的 /kb/{kbId}/chat 应存在（404 说明没注册上）");
        assertEquals(400, get("/stage8/kb/kb-chat/chat/compare").statusCode(),
                "路径写法的 /kb/{kbId}/chat/compare 应存在");
    }
}
