package com.agentlab.stage4.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 示例二：放在<b>工具循环内部</b>的 Advisor —— 观测模型的每一次工具请求。
 *
 * <p>这是 Spring AI 2.0 相比 1.x 最实用的能力之一。1.x 里工具循环深埋在 ChatModel 内部，
 * 外部完全看不见中间步骤；2.0 把循环提升到 Advisor 链后，<b>只要把 Advisor 放在
 * ToolCallingAdvisor 的「下游」（order 更大），工具循环每迭代一轮它就会被调用一次</b>。
 *
 * <p>{@code ToolCallingAdvisor.DEFAULT_ORDER == Integer.MIN_VALUE + 300}，
 * 所以这里用 {@code DEFAULT_ORDER + 10} 就落到了循环内部。
 *
 * <p>这也解释了一个常见困惑：为什么 Memory Advisor 默认在循环「外面」（order 更小）？
 * 因为绝大多数 ChatMemoryRepository 实现不支持 tool 消息类型，所以默认只落库最终的
 * user/assistant 一轮交换。要「循环内记忆」得显式调 {@code disableInternalConversationHistory()}
 * 并把 Memory Advisor 的 order 调到循环内部。
 */
public class ToolLoopObserverAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopObserverAdvisor.class);

    @Override
    public String getName() {
        return "ToolLoopObserverAdvisor";
    }

    @Override
    public int getOrder() {
        // 关键：比 ToolCallingAdvisor 更靠内，才能观测到每一轮迭代
        return ToolCallingAdvisor.DEFAULT_ORDER + 10;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        if (response.chatResponse() != null && response.chatResponse().getResult() != null) {
            AssistantMessage output = response.chatResponse().getResult().getOutput();
            if (output.hasToolCalls()) {
                output.getToolCalls().forEach(toolCall ->
                        log.info("[ToolLoop] 模型请求调用工具 → name = {}，arguments = {}",
                                toolCall.name(), toolCall.arguments()));
            } else {
                log.info("[ToolLoop] 本轮无工具调用 → 工具循环结束");
            }
        }
        return response;
    }
}
