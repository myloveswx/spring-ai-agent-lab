package com.agentlab.stage8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8 —— <b>上传 .md 文件入库</b>的测试。
 *
 * <h2>为什么这个接口值得单独钉住</h2>
 * 它是唯一一个「用户自己的文件 → 向量库」的入口，链路上有四个各自会独立出错的环节：
 * <pre>
 *   multipart 收文件 → 编码/BOM 处理 → 推标题 → 切块 → 嵌入 → 写清单
 * </pre>
 * 任何一环坏掉，表现都是「上传成功了但检索不到」——最难查的那种。
 *
 * <h2>四条断言分别防什么</h2>
 * <ol>
 *   <li><b>切片明细必须回得来</b>。{@code VectorStore} 接口没有 get，
 *       片段正文入库后就取不回来；如果哪天有人「顺手」把 storeChunks 的返回值
 *       改回只给 IngestRecord，这个接口就会静默退化成「只说切了几块」——
 *       功能看着还在，教学价值没了。</li>
 *   <li><b>标题推导优先级</b>：正文首个 {@code #} 标题 > 文件名。
 *       反过来的话，检索结果里会显示「新建文档(1)」这种文件名，看不出出处。</li>
 *   <li><b>坏文件不拖垮整批</b>。一次选 5 个文件，其中一个不是 md，
 *       另外 4 个仍然要入库 —— 整体 400 是更省事的实现，但对用户更差。</li>
 *   <li><b>upsert 按文件名覆盖</b>。md 的 source 就是文件名，
 *       所以重复上传同一份资料不该越堆越多（这是 append 与 upsert 的分界）。</li>
 * </ol>
 *
 * <h2>用独立的知识库根目录</h2>
 * {@code agentlab.rag.store-root=target/stage8-upload-test-store}，
 * 与另外三个 Stage 8 测试类互不干扰，也绝不碰真实知识库。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.deepseek.api-key=test-key-for-context-load",
                "spring.ai.mcp.client.enabled=false",
                "agentlab.rag.store-root=target/stage8-upload-test-store",
                "agentlab.rag.warmup=false"
        })
class Stage8UploadTest {

    private static final String STORE_ROOT = "target/stage8-upload-test-store";
    private static final String KB = "upload-test";

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
    // multipart 小工具
    // ==================================================================

    /**
     * 手搓一个 multipart/form-data 请求。
     *
     * <p>JDK 的 HttpClient 没有内置 multipart body publisher，而引一个
     * 测试专用库只为发一次请求不划算。这里的实现刚好也把边界写清楚了：
     * 每个 part 是 {@code --boundary} + 头 + 空行 + 正文 + CRLF，最后以
     * {@code --boundary--} 收尾 —— 少一个 CRLF 服务端就解析不出 part。
     */
    private HttpResponse<String> postFiles(String path, Map<String, byte[]> files) throws Exception {
        String boundary = "----AgentLabBoundary" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(("Content-Disposition: form-data; name=\"files\"; filename=\""
                    + entry.getKey() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Content-Type: text/markdown; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.writeBytes(entry.getValue());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode uploadOk(String kbId, Map<String, byte[]> files) throws Exception {
        HttpResponse<String> response = postFiles("/stage8/kb/" + kbId + "/upload", files);
        assertEquals(200, response.statusCode(), "上传应返回 200，实际：" + response.statusCode()
                + " / " + response.body());
        return json.readTree(response.body());
    }

    private JsonNode uploadOk(String kbId, Map<String, byte[]> files, String mode) throws Exception {
        HttpResponse<String> response = postFiles("/stage8/kb/" + kbId + "/upload?mode=" + mode, files);
        assertEquals(200, response.statusCode(), "上传应返回 200，实际：" + response.statusCode()
                + " / " + response.body());
        return json.readTree(response.body());
    }

    private static Map<String, byte[]> file(String name, String content) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(name, content.getBytes(StandardCharsets.UTF_8));
        return map;
    }

    private JsonNode stats(String kbId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/stage8/kb/" + kbId + "/stats"))
                .timeout(Duration.ofSeconds(30)).GET().build());
        assertEquals(200, response.statusCode(), "stats 应返回 200");
        return json.readTree(response.body());
    }

    /** 每个用例都从一个干净的空库开始，避免互相干扰。 */
    private void resetKb() throws Exception {
        HttpResponse<String> del = send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/stage8/kb/" + KB))
                .timeout(Duration.ofSeconds(30)).DELETE().build());
        // 库不存在时删会 404，这不影响后面的建库
        assertTrue(del.statusCode() == 200 || del.statusCode() == 404,
                "删库应返回 200 或 404，实际：" + del.statusCode());

        HttpResponse<String> create = send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/stage8/kb"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("id", KB, "name", "上传测试库")),
                        StandardCharsets.UTF_8))
                .build());
        assertEquals(200, create.statusCode(), "建库应返回 200，实际：" + create.statusCode()
                + " / " + create.body());
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("上传 md：切片明细必须回得来（含每块序号与正文预览）")
    void uploadReturnsChunkPreviews() throws Exception {
        resetKb();

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("handbook.md", """
                # 员工手册（节选）

                ## 年假

                入职满一年后有 5 天年假，满三年后 10 天，满五年后 15 天。
                年假需提前三个工作日申请，未休完的部分可以结转，最多结转 5 天。

                ## 加班

                加班需要提前申请，未经审批的加班不计入调休。
                工作日加班按 1.5 倍计算，周末按 2 倍计算。
                """.getBytes(StandardCharsets.UTF_8));

        JsonNode result = uploadOk(KB, files);

        assertEquals(1, result.get("filesIngested").asInt(), "应入库 1 篇");
        assertEquals(0, result.get("filesSkipped").asInt(), "不该有跳过");
        assertEquals(KB, result.get("knowledgeBase").asText(), "应落在指定的库上");
        assertEquals(512, result.get("dimensions").asInt(),
                "本地 bge-small-zh-v1.5 是 512 维 —— 变了说明嵌入模型换掉了");
        assertTrue(result.get("costMillis").asLong() >= 0, "耗时应该有值");

        JsonNode doc = result.get("uploaded").get(0);
        assertEquals("员工手册（节选）", doc.get("title").asText(),
                "标题应取正文第一个一级标题，而不是文件名 handbook");
        assertEquals("handbook.md", doc.get("source").asText(), "source 应保留文件名");
        assertTrue(doc.get("chars").asInt() > 0, "原文字数应大于 0");

        JsonNode previews = doc.get("previews");
        assertNotNull(previews, "必须带切片明细 —— 这是本接口相对 /ingest 的唯一增量");
        assertEquals(doc.get("chunks").asInt(), previews.size(),
                "previews 数量应与 chunks 一致");
        assertTrue(previews.size() >= 1, "至少切出一块");

        JsonNode first = previews.get(0);
        assertEquals(0, first.get("index").asInt(), "第一块序号应为 0");
        assertTrue(first.get("chars").asInt() > 0, "每块都要有字数");
        assertFalse(first.get("text").asText().isBlank(), "每块都要有正文预览");

        // 前端展示的就是这些数字，和库里的实况对齐
        JsonNode s = stats(KB);
        assertEquals(1, s.get("documents").asInt(), "库里有 1 篇");
        assertEquals(result.get("chunksAdded").asInt(), s.get("chunks").asInt(),
                "本次新增块数应与库内总块数一致（库是干净的）");
    }

    @Test
    @DisplayName("标题推导：没有一级标题时回退到文件名（去扩展名）")
    void titleFallsBackToFilename() throws Exception {
        resetKb();

        JsonNode result = uploadOk(KB, file("release-checklist.md",
                "这份文件没有一级标题，第一行就是普通正文。\n\n发布前需要确认三件事：回归通过、文档更新、回滚方案就绪。\n"));

        assertEquals("release-checklist", result.get("uploaded").get(0).get("title").asText(),
                "没有 # 标题时应用文件名去掉扩展名");
    }

    @Test
    @DisplayName("坏文件不拖垮整批：非 md 与空文件被跳过，同批其它文件照常入库")
    void badFilesAreSkippedWithoutBreakingTheBatch() throws Exception {
        resetKb();

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("good-01.md", "# 第一篇\n\n这份应该入库成功。\n".getBytes(StandardCharsets.UTF_8));
        files.put("binary.pdf", "%PDF-1.4 not really a pdf\n".getBytes(StandardCharsets.UTF_8));
        files.put("empty.md", new byte[0]);
        files.put("good-02.md", "# 第二篇\n\n这份也应该入库成功。\n".getBytes(StandardCharsets.UTF_8));

        JsonNode result = uploadOk(KB, files);

        assertEquals(4, result.get("filesReceived").asInt(), "收到 4 个文件");
        assertEquals(2, result.get("filesIngested").asInt(), "其中 2 个入库");
        assertEquals(2, result.get("filesSkipped").asInt(), "另外 2 个被跳过");

        String skipped = result.get("skipped").toString();
        assertTrue(skipped.contains("binary.pdf"), "跳过原因要点名 binary.pdf，实际：" + skipped);
        assertTrue(skipped.contains("empty.md"), "跳过原因要点名 empty.md，实际：" + skipped);

        assertEquals(2, stats(KB).get("documents").asInt(), "两个好文件都要真的进库");
    }

    @Test
    @DisplayName("upsert 按文件名覆盖：同源旧文档整体清掉，重复上传不该越堆越多")
    void upsertOverwritesByFilename() throws Exception {
        resetKb();

        uploadOk(KB, file("policy.md", "# 报销制度 v1\n\n住宿标准：一线城市 500 元/晚。\n"));
        assertEquals(1, stats(KB).get("documents").asInt(), "第一次上传后有 1 篇");

        // 默认 append：同一份再传一次就变成两篇 —— 这是刻意的默认，不是 bug
        uploadOk(KB, file("policy.md", "# 报销制度 v1\n\n住宿标准：一线城市 500 元/晚。\n"));
        assertEquals(2, stats(KB).get("documents").asInt(),
                "append 模式下重复上传会多一篇（默认行为必须对「连续灌不同文档」安全）");

        // upsert 是按 source 全覆盖，不是「按篇匹配」：
        // source 就是文件名，所以上面 append 留下的两份 policy.md 会被一起清掉，
        // 最终只剩这一篇 —— 这正是它和「PUT /docs/{docId} 只改一篇」的分工。
        JsonNode updated = uploadOk(KB,
                file("policy.md", "# 报销制度 v2\n\n住宿标准：一线城市 700 元/晚。\n"), "upsert");
        assertEquals("报销制度 v2", updated.get("uploaded").get(0).get("title").asText(),
                "应写入新版本的内容");
        assertEquals(1, stats(KB).get("documents").asInt(),
                "upsert 清掉所有同 source 的旧文档，只留新的这一篇（旧的两份都算 policy.md）");
    }

    @Test
    @DisplayName("UTF-8 BOM 会被剥掉：标题里不该出现 \\uFEFF 残渣")
    void bomIsStripped() throws Exception {
        resetKb();

        // Windows 记事本存出来的 UTF-8 文件就是这样：开头多三个字节 EF BB BF。
        // 不剥掉的话首行是 "\uFEFF# 带 BOM 的文档"，既匹配不上「首个 # 标题」，
        // 那个零宽字符也会跟着标题一起被嵌进向量 —— 肉眼看不见，检索结果里才露馅。
        JsonNode result = uploadOk(KB, file("bom.md",
                "\uFEFF# 带 BOM 的文档\n\n这是一份用记事本另存出来的文件。\n"));

        String title = result.get("uploaded").get(0).get("title").asText();
        assertEquals("带 BOM 的文档", title, "BOM 没剥干净的话这里会是 \"\\uFEFF带 BOM 的文档\"");
        assertFalse(title.startsWith("\uFEFF"), "标题首字符不能是 BOM");
    }

    @Test
    @DisplayName("mode 只认 append / upsert，传别的给 400 而不是静默按追加处理")
    void unknownModeIsRejected() throws Exception {
        resetKb();

        HttpResponse<String> response = postFiles("/stage8/kb/" + KB + "/upload?mode=replace",
                file("x.md", "# 标题\n\n正文。\n"));

        assertEquals(400, response.statusCode(),
                "未知 mode 必须报错 —— 静默按 append 会让「我以为覆盖了」变成数据越堆越多");
        assertTrue(response.body().contains("mode"), "错误信息要点出是 mode 的问题，实际：" + response.body());
    }
}
