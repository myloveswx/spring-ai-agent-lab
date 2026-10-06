package com.agentlab.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * OpenAPI（Swagger）文档元数据配置。
 *
 * <p>springdoc-openapi 是「约定优于配置」的：只要引入 starter，它就会自动扫描
 * 所有 {@code @RestController}，把方法签名、参数、返回类型翻译成 OpenAPI 3 模型，
 * <b>一行代码都不写也能出文档</b>。这个类只负责补上「机器推不出来的东西」：
 * <ul>
 *   <li>{@code Info} —— 文档标题、版本、说明（页面上半部分）</li>
 *   <li>{@code Tag} —— 接口分组的顺序与说明。控制器的 {@code @Tag(name=...)} 只声明归属，
 *       分组在前端的<b>排列顺序</b>由这里决定（不声明就按扫描到的字母序，很乱）</li>
 *   <li>{@code Server} —— 告诉 Swagger UI「Try it out」该往哪个地址发请求</li>
 * </ul>
 *
 * <p>访问入口：
 * <pre>
 *   Swagger UI : http://localhost:8080/swagger-ui/index.html
 *   OpenAPI JSON: http://localhost:8080/v3/api-docs
 * </pre>
 *
 * <p><b>关于 name 的一致性</b>：下面每个 {@link Tag#name} 都必须与各控制器上
 * {@code @Tag(name = "...")} 的字符串逐字相同（含「·」和空格），否则该分组
 * 会掉到未排序区，出现在列表末尾。
 */
@Configuration
public class OpenApiConfig {

    /** 分组名常量：集中定义，避免控制器与配置两处手写字符串漂移。 */
    public static final String TAG_STAGE1 = "Stage 1 · ChatClient 基础";
    public static final String TAG_STAGE2 = "Stage 2 · 会话记忆";
    public static final String TAG_STAGE3 = "Stage 3 · 工具调用";
    public static final String TAG_STAGE4 = "Stage 4 · 自定义 Advisor";
    public static final String TAG_STAGE5 = "Stage 5 · 结构化输出";
    public static final String TAG_STAGE6 = "Stage 6 · 渐进式工具披露";
    public static final String TAG_STAGE7 = "Stage 7 · MCP 客户端";
    public static final String TAG_STAGE8 = "Stage 8 · RAG 知识库";
    public static final String TAG_DIAGNOSTICS = "工具 · 编码自检";

    @Bean
    public OpenAPI agentLabOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring AI Agent Lab API")
                        .version("1.0.0")
                        .description("""
                                Spring AI 2.0 渐进式 Agent 学习项目的接口文档。

                                8 个阶段对应 Agent 能力的 8 个台阶，建议按顺序阅读 / 调用：

                                1. **ChatClient 基础** —— 同步问答、Prompt 模板、SSE 流式输出
                                2. **会话记忆** —— 多轮上下文、`conversationId` 隔离、记忆落库到 MySQL
                                3. **工具调用** —— Tool Calling 循环、`@Tool` POJO 与 `ToolCallback` 异构注册
                                4. **自定义 Advisor** —— 拦截链与 order 顺序、工具循环观测
                                5. **结构化输出** —— `.entity()` 反序列化 + 校验失败自动重试
                                6. **渐进式工具披露** —— 工具多了以后按相关度动态筛选，而非全量下发
                                7. **MCP 客户端** —— 接入外部 MCP Server（默认关闭）
                                8. **RAG 知识库（多库）** —— 本地 ONNX 嵌入 + 向量检索 + `QuestionAnswerAdvisor`；
                                   每个知识库一个独立目录与独立向量库，物理隔离、互不干扰

                                > 除「编码自检」外，所有对话接口都需要真实调用 DeepSeek，
                                > 因此启动前必须先设置环境变量 `DEEPSEEK_API_KEY` ——
                                > 该 Key 缺失时不是「调用报 401」，而是**应用直接启动失败**。
                                >
                                > Stage 8 另有前置条件：本地需存在 ONNX 嵌入模型
                                > （默认 `D:/workspace/.toolchain/models/bge-small-zh-v1.5`）。
                                > 模型缺失时整块装配会失败，把 `agentlab.rag.enabled` 设为 `false`
                                > 即可让 Stage 1~7 不受影响地启动。
                                """)
                        .contact(new Contact().name("追光者"))
                        .license(new License()
                                .name("Apache License 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                // Swagger UI 的「Try it out」默认向这里发请求。
                // 之所以写 8080 而不是配置里的 server.port，是因为本地调试时常被
                // 环境变量 SERVER__PORT 覆盖成 4682，显式声明可以避免文档与实际端口对不上。
                .servers(List.of(new Server()
                        .url("http://localhost:8080")
                        .description("本地开发环境")))
                .tags(List.of(
                        new Tag().name(TAG_STAGE1)
                                .description("最小可用对话：一问一答、占位符模板、流式（SSE）逐字输出。"),
                        new Tag().name(TAG_STAGE2)
                                .description("记忆的两个正交抽象 ChatMemory / ChatMemoryRepository，"
                                        + "以及直查 MySQL 记忆表的调试端点。"),
                        new Tag().name(TAG_STAGE3)
                                .description("工具调用循环：模型请求工具 → 执行 → 回填 → 再推理。"),
                        new Tag().name(TAG_STAGE4)
                                .description("在 Advisor 链上插入自定义逻辑，并用 order 控制内外层关系。"),
                        new Tag().name(TAG_STAGE5)
                                .description("让模型直接产出 Java 对象，并对不合规 JSON 自动纠错重试。"),
                        new Tag().name(TAG_STAGE6)
                                .description("工具数量多时的渐进式披露：先检索、再下发，控制 Token 与准确率。"),
                        new Tag().name(TAG_STAGE7)
                                .description("MCP 客户端接入，默认关闭；开启后需在 Swagger 中触发 "
                                        + "（注意需重启才生效）。"),
                        new Tag().name(TAG_STAGE8)
                                .description("L1 朴素 RAG：本地 ONNX 嵌入 + 内存向量库 + 检索增强问答，"
                                        + "**支持多知识库**（每个库一个独立目录，物理隔离、互不干扰）。"
                                        + "库管理走 /stage8/kb（GET 列表 / POST 建库 / DELETE /{kbId} 删库）；"
                                        + "库内操作走 /stage8/kb/{kbId}/**，而不带 kbId 的老路径等价于 default 库。"
                                        + "建议先跑 /stage8/kb/search（不含模型、结果可复现），"
                                        + "再看 /stage8/chat/compare 的对照效果；"
                                        + "把同一个问题分别问 kb1 与 kb2，就能看到「各自只依据自己的资料作答」。"),
                        new Tag().name(TAG_DIAGNOSTICS)
                                .description("不依赖大模型的纯本地端点，用于定位请求 / 响应编码问题。")));
    }
}
