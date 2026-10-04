package com.agentlab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文装配冒烟测试。
 *
 * <p>它验证的是「所有 Bean 能否装配成功」——包括：
 * <ul>
 *   <li>DeepSeek ChatModel / ChatClient.Builder 自动装配</li>
 *   <li>ChatMemory / MessageChatMemoryAdvisor</li>
 *   <li>ToolSearchToolCallingAdvisor 自动装配（由 starter + 配置触发）</li>
 *   <li>7 个阶段的所有 Controller 与工具 Bean</li>
 * </ul>
 *
 * <p>测试使用假的 API Key，不会产生任何真实模型调用，也不需要联网。
 */
@SpringBootTest(properties = {
        "spring.ai.deepseek.api-key=test-key-for-context-load",
        "spring.ai.mcp.client.enabled=false"
})
class AgentLabApplicationTests {

    @Test
    void contextLoads() {
        // 只要上下文能起来，就说明整条装配链是对的
    }
}
