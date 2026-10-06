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
 *   <li>Stage 8 的 EmbeddingModel / SimpleVectorStore / QuestionAnswerAdvisor
 *       —— 这条尤其值得留着：{@code TransformersEmbeddingModelAutoConfiguration}
 *       上也声明了一个 {@code EmbeddingModel}，一旦手工 Bean 的类型没写对，
 *       两个同类型 Bean 会让注入直接抛 NoUniqueBeanDefinitionException。
 *       本测试就是这条约束的守门人。</li>
 *   <li>8 个阶段的所有 Controller 与工具 Bean</li>
 * </ul>
 *
 * <p>测试使用假的 API Key，不会产生任何真实模型调用，也不需要联网
 * （Stage 8 的嵌入用本机 ONNX 模型，同样离线）。
 */
@SpringBootTest(properties = {
        "spring.ai.deepseek.api-key=test-key-for-context-load",
        "spring.ai.mcp.client.enabled=false",
        // 别去读/写开发时攒下来的真实向量库，也别为测试付预热开销
        "agentlab.rag.store-root=target/stage8-smoke-test-store",
        "agentlab.rag.warmup=false"
})
class AgentLabApplicationTests {

    @Test
    void contextLoads() {
        // 只要上下文能起来，就说明整条装配链是对的
    }
}
