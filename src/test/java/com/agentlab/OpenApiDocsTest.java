package com.agentlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 文档冒烟测试。
 *
 * <p>它要回答一个很实际的问题：<b>「文档是不是真的生成了，还是只是依赖加上了？」</b>
 * 光靠「应用能启动」证明不了这一点 —— springdoc 的 OpenAPI 模型是<b>懒生成</b>的，
 * 只有真正请求 {@code /v3/api-docs} 那一刻才会去扫描所有 Controller。
 * 换句话说：注解写错、Tag 名对不上、路径被拦截……这些问题都只在「有人来取文档」时才暴露。
 *
 * <p>所以这里用真实 HTTP 请求（而不是 MockMvc）来验证：
 * <ul>
 *   <li>{@code /v3/api-docs} 返回 200，且 JSON 里有我们声明的标题</li>
 *   <li>7 个阶段 + 诊断分组都出现在 tags 里</li>
 *   <li>每个阶段的路径都被扫描到了（按前缀抽查）</li>
 *   <li>{@code /swagger-ui/index.html} 能拿到 UI 页面（证明 webjar 静态资源也在）</li>
 * </ul>
 *
 * <p>用 JDK 自带的 {@link HttpClient} 而不是 TestRestTemplate / MockMvc：
 * Spring Boot 4 把测试工具类拆到了不同模块，包路径有变动，
 * 直接用标准库最省心，也不会因为工具类迁移而编译失败。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.deepseek.api-key=test-key-for-context-load",
                "spring.ai.mcp.client.enabled=false"
        })
class OpenApiDocsTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("OpenAPI JSON：应能生成并包含项目元数据与全部分组")
    void apiDocsShouldContainMetadataAndTags() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertEquals(200, response.statusCode(), "OpenAPI JSON 未生成，检查 springdoc 依赖与 api-docs.path 配置");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                "响应必须是 JSON");

        String body = response.body();
        assertTrue(body.contains("Spring AI Agent Lab API"), "缺少 Info.title —— OpenApiConfig 未被加载");
        assertTrue(body.contains("\"1.0.0\""), "缺少版本号");

        // 分组：名字里带「Stage N」的都必须在
        for (int stage = 1; stage <= 7; stage++) {
            assertTrue(body.contains("Stage " + stage + " ·"),
                    "缺少 Stage " + stage + " 分组，检查该阶段 Controller 的 @Tag 与 OpenApiConfig 常量是否一致");
        }
        assertTrue(body.contains("工具 · 编码自检"), "缺少诊断分组");
    }

    @Test
    @DisplayName("OpenAPI JSON：7 个阶段的路径应全部被扫描到")
    void apiDocsShouldExposeEveryStagePath() throws Exception {
        String body = get("/v3/api-docs").body();

        String[] expectedPaths = {
                "/stage1/chat",
                "/stage1/chat/template",
                "/stage1/stream",
                "/stage2/chat",
                "/stage2/history",
                "/stage2/db/rows",
                "/stage2/db/stats",
                "/stage3/chat",
                "/stage4/chat",
                "/stage5/analyze",
                "/stage5/analyze/validated",
                "/stage6/chat",
                "/diagnostics/encoding/text",
                "/diagnostics/encoding/json"
        };
        for (String path : expectedPaths) {
            assertTrue(body.contains("\"" + path + "\""), "文档里缺少路径 " + path);
        }

        // Stage 7 默认关闭（spring.ai.mcp.client.enabled=false），不应出现在文档里。
        // 这正好反向验证了「@ConditionalOnProperty 未命中 → Bean 不创建 → 文档里也没有」。
        assertTrue(!body.contains("/stage7/"), "Stage 7 默认关闭，不应出现在文档中");
    }

    @Test
    @DisplayName("Stage 6：/stage6/chat 必须声明 conversationId 参数")
    void stage6ChatMustDeclareConversationId() throws Exception {
        JsonNode root = new ObjectMapper().readTree(get("/v3/api-docs").body());
        JsonNode params = root.at("/paths/~1stage6~1chat/get/parameters");

        assertTrue(params.isArray() && !params.isEmpty(), "/stage6/chat 没有 parameters 定义");

        // ToolSearchToolCallingAdvisor 按「会话」缓存工具索引，会话标识从请求 context 里读，
        // key 是 ChatMemory.CONVERSATION_ID（chat_memory_conversation_id）。
        // 接口若不把它塞进 context，每次调用都会抛
        //   IllegalArgumentException: context must contain a non-null value for 'chat_memory_conversation_id'
        // 直接 500（这个坑真踩过）。把参数固化进文档，防止以后被误删。
        boolean hasConversationId = false;
        for (JsonNode p : params) {
            if ("conversationId".equals(p.path("name").asText())) {
                hasConversationId = true;
            }
        }
        assertTrue(hasConversationId,
                "/stage6/chat 缺少 conversationId 参数：ToolSearchToolCallingAdvisor 依赖它来取会话 ID，"
                        + "缺失会导致每次调用都 500");
    }

    @Test
    @DisplayName("Swagger UI：静态资源应可访问")
    void swaggerUiShouldBeServed() throws Exception {
        HttpResponse<String> response = get("/swagger-ui/index.html");

        assertEquals(200, response.statusCode(), "Swagger UI 页面未提供，检查 webjars 依赖是否完整");
        assertTrue(response.body().toLowerCase().contains("swagger"),
                "返回内容不像 Swagger UI 页面");
    }
}
