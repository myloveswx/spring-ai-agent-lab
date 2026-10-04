package com.agentlab.stage1;

import com.agentlab.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Stage 1 —— ChatClient 基础用法。
 *
 * <p>本阶段只做一件事：把「问一句、答一句」跑通，并理解 Spring AI 2.0 的核心立场：
 * <b>ChatClient 是唯一推荐的用户入口，ChatModel 降级为底层构件</b>。
 *
 * <p>关键 API：
 * <ul>
 *   <li>{@link ChatClient#builder(ChatModel)} —— 手工构建客户端（完全可控）</li>
 *   <li>{@code defaultSystem(...)} —— 设定人格/系统提示</li>
 *   <li>{@code prompt().user(...).call().content()} —— 同步一问一答</li>
 *   <li>{@code prompt().user(...).stream().content()} —— 流式（SSE）输出</li>
 * </ul>
 */
@RestController
@RequestMapping("/stage1")
@Tag(name = OpenApiConfig.TAG_STAGE1)
public class BasicChatController {

    private final ChatClient chatClient;

    public BasicChatController(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一位严谨的 Java 后端技术顾问。
                        回答要求：准确、简洁，能用代码说明的优先给代码，不要客套话。
                        """)
                .build();
    }

    /**
     * 最简同步对话。
     * <pre>curl "http://localhost:8080/stage1/chat?message=什么是虚拟线程"</pre>
     */
    @GetMapping("/chat")
    @Operation(
            summary = "最简同步对话",
            description = "把用户消息发给模型，等模型完整回答后一次性返回纯文本。"
                    + "同步接口的耗时 ≈ 模型生成整段回答的时间，长回答会明显阻塞。")
    public String chat(
            @Parameter(description = "用户提问，例如「什么是虚拟线程」", example = "什么是虚拟线程")
            @RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * 使用占位符模板组织 Prompt（比字符串拼接更安全、更清晰）。
     * <pre>curl "http://localhost:8080/stage1/chat/template?topic=Spring%20AI&level=高级"</pre>
     */
    @GetMapping("/chat/template")
    @Operation(summary = "占位符模板对话",
            description = "用 {占位符} + param() 组织 Prompt，比字符串拼接更安全清晰，"
                    + "也更利于把 Prompt 抽成可配置模板。")
    public String chatWithTemplate(
            @Parameter(description = "讲解主题", example = "Spring AI")
            @RequestParam String topic,
            @Parameter(description = "难度档位，决定 Prompt 里 {level} 的取值", example = "高级")
            @RequestParam(defaultValue = "中级") String level) {
        return chatClient.prompt()
                .user(u -> u.text("请用 {level} 难度讲解 {topic}，控制在 150 字以内。")
                        .param("level", level)
                        .param("topic", topic))
                .call()
                .content();
    }

    /**
     * 流式输出（Server-Sent Events）。浏览器/前端可逐字渲染。
     * <pre>curl -N "http://localhost:8080/stage1/stream?message=写一首关于编译器的五言绝句"</pre>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    @Operation(summary = "流式输出（SSE）",
            description = "返回 text/event-stream，前端可逐字渲染。"
                    + "注意 Swagger UI 对这种流式响应支持有限：点 Try it out 会等到全部结束后一次性显示，"
                    + "想看真实逐字效果请用 curl -N 或浏览器 EventSource。")
    public Flux<String> stream(
            @Parameter(description = "用户提问", example = "写一首关于编译器的五言绝句")
            @RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
