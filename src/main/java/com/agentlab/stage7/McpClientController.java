package com.agentlab.stage7;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Stage 7 —— MCP（Model Context Protocol）客户端接入。
 *
 * <p>MCP 正在成为「AI 与外部系统对接」的通用协议。Spring 团队自己维护官方 MCP Java SDK，
 * 所以 Spring AI 直接对齐规范源头：内置 <b>MCP Java SDK 2.0.0</b>（符合 2025-11-25 规范），
 * 并且 <b>Streamable HTTP 已成为默认传输方式</b>（取代被弃用的 SSE），STDIO 保留用于本地进程集成。
 *
 * <p>本阶段做的是「客户端」侧：把一个远端 MCP Server 暴露的工具，直接变成 ChatClient 可调用的工具。
 * 关键点在于 —— 你<b>不需要为 MCP 工具写任何适配代码</b>：
 * <pre>
 *   MCP Server (stdio / streamable-http)
 *        ↓  Spring AI 自动发现并包装
 *   SyncMcpToolCallbackProvider : ToolCallbackProvider
 *        ↓  .defaultToolCallbacks(provider)
 *   ChatClient  →  ToolCallingAdvisor 统一驱动
 * </pre>
 *
 * <p><b>本类默认不生效</b>：只有把 {@code spring.ai.mcp.client.enabled} 设为 {@code true}
 * 时才创建（否则没有任何 MCP Server 可连，启动没意义）。
 * 开启方式见项目根目录 README 的「Stage 7」章节，以及
 * {@code src/main/resources/application-mcp.yml.example}。
 */
@RestController
@RequestMapping("/stage7")
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
public class McpClientController {

    private final ChatClient chatClient;
    private final List<ToolCallbackProvider> mcpProviders;

    public McpClientController(ChatModel chatModel, ObjectProvider<ToolCallbackProvider> providers) {
        // 只保留 MCP 提供的那些 provider，避免把本地 @Tool 工具也算进来
        this.mcpProviders = providers.stream()
                .filter(provider -> provider instanceof SyncMcpToolCallbackProvider)
                .toList();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你可以调用通过 MCP 协议接入的外部工具。
                        需要外部信息时必须调用工具，禁止编造结果。
                        """)
                .defaultToolCallbacks(mcpProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }

    /**
     * 列出当前从所有 MCP Server 发现到的工具。
     * <pre>curl "http://localhost:8080/stage7/tools"</pre>
     */
    @GetMapping("/tools")
    public List<String> listTools() {
        return mcpProviders.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(this::describe)
                .sorted()
                .toList();
    }

    /**
     * 用自然语言调用 MCP 工具。
     * <pre>curl "http://localhost:8080/stage7/chat?message=列出 D:/workspace 下的文件"</pre>
     */
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    private String describe(ToolCallback callback) {
        var definition = callback.getToolDefinition();
        return definition.name() + " —— " + definition.description();
    }
}
