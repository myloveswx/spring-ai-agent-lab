package com.agentlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 *   <li>8 个阶段 + 诊断分组都出现在 tags 里</li>
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
                "spring.ai.mcp.client.enabled=false",
                // 这个测试只关心「文档里有没有这些路径」，不需要真的把模型载进来跑一遍，
                // 更要紧的是别去读/写开发时攒下来的真实向量库。
                "agentlab.rag.store-path=target/stage8-openapi-test-store.json",
                "agentlab.rag.warmup=false"
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
        for (int stage = 1; stage <= 8; stage++) {
            assertTrue(body.contains("Stage " + stage + " ·"),
                    "缺少 Stage " + stage + " 分组，检查该阶段 Controller 的 @Tag 与 OpenApiConfig 常量是否一致");
        }
        assertTrue(body.contains("工具 · 编码自检"), "缺少诊断分组");
    }

    @Test
    @DisplayName("OpenAPI JSON：8 个阶段的路径应全部被扫描到")
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
                // Stage 6 检索策略实验室：把「检索」从模型链路里剥出来单独暴露的四个端点
                "/stage6/lab/search",
                "/stage6/lab/compare",
                "/stage6/lab/catalog",
                "/stage6/lab/chat",
                // Stage 8 RAG：kb/** 是「不经过大模型」的检索层端点，chat 是生成层端点。
                // 这个划分本身就是 L1 的教学设计 —— 排 RAG 问题要先看检索层。
                "/stage8/kb/ingest",
                "/stage8/kb/ingest-sample",
                "/stage8/kb/search",
                "/stage8/kb/stats",
                "/stage8/kb/save",
                "/stage8/kb/load",
                "/stage8/chat",
                "/stage8/chat/compare",
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
    @DisplayName("Stage 6 实验室：/compare 应并排返回四条策略的命中结果")
    void labCompareShouldReturnAllStrategies() throws Exception {
        // 中文必须预编码 —— 未编码的中文字节会被 Tomcat 在进 Spring 之前挡掉，返回 HTML 400 页。
        String query = URLEncoder.encode("钱什么时候能退回来", StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/stage6/lab/compare?q=" + query);

        assertEquals(200, response.statusCode(), "实验室检索端点应可直接访问（它不调用模型）");

        JsonNode root = new ObjectMapper().readTree(response.body());
        assertEquals(13, root.get("toolCount").asInt(), "实验室应索引 CrmTools 的全部 13 个工具");

        JsonNode results = root.get("results");
        assertEquals(4, results.size(), "应返回 regex / keyword / synonym / category 四条策略");

        JsonNode synonym = null;
        JsonNode regex = null;
        for (JsonNode r : results) {
            if ("synonym".equals(r.get("strategy").asText())) {
                synonym = r;
            }
            if ("regex".equals(r.get("strategy").asText())) {
                regex = r;
            }
        }
        assertTrue(synonym != null, "缺少 synonym 策略结果");
        assertEquals("applyRefund", synonym.get("hits").get(0).get("toolName").asText(),
                "同义词策略应把口语 query「钱什么时候能退回来」定位到 applyRefund");
        assertTrue(regex != null && regex.get("hits").isEmpty(),
                "内置 regex 策略对这句中文口语应无命中 —— 这正是实验室要演示的基线");
    }

    @Test
    @DisplayName("Stage 6 实验室：分类过滤应把召回锁死在指定业务域")
    void labSearchShouldRespectCategoryFilter() throws Exception {
        String query = URLEncoder.encode("帮 C1001 查一下", StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/stage6/lab/search?q=" + query
                + "&strategy=category&category=logistics");

        assertEquals(200, response.statusCode());
        JsonNode hits = new ObjectMapper().readTree(response.body()).get("hits");
        assertTrue(hits.size() > 0, "物流域应至少召回 queryLogistics");
        for (JsonNode hit : hits) {
            assertEquals("logistics", hit.get("category").asText(),
                    "带 category 过滤时，命中必须全部属于该域");
        }
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
