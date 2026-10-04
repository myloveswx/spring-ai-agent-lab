package com.agentlab.stage6;

import com.agentlab.stage6.tools.CrmTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 6 —— 多工具场景与渐进式工具披露（Progressive Tool Disclosure）。
 *
 * <p>本阶段注册了 {@link CrmTools} 的全部 12 个工具，然后由
 * {@code ToolSearchToolCallingAdvisor} 接管工具循环：
 * <ul>
 *   <li>它先对全量工具建一次索引（本示例用零依赖的 regex 索引）</li>
 *   <li>每轮只把与当前问题最相关的少数几个工具定义发给模型</li>
 *   <li>模型可以按需「检索」更多工具</li>
 * </ul>
 *
 * <p><b>注意这里注入的是 Spring Boot 自动装配的 {@code ChatClient.Builder}</b>，
 * 因为 {@code spring.ai.chat.client.tool-search-advisor.enabled=true} 只会作用于它。
 * Stage 1-5 用的是 {@code ChatClient.builder(chatModel)} 手工构建，完全不受影响——
 * 这也顺带说明了「自动装配的默认值」与「手工构建」两条路线的边界。
 *
 * <p>想对比效果：把 application.yml 里的 {@code tool-search-advisor.enabled} 改成 false，
 * 重启后再问同样的问题，观察控制台里下发到模型的工具数量差异。
 */
@RestController
@RequestMapping("/stage6")
public class ToolSearchChatController {

    private final ChatClient chatClient;

    public ToolSearchChatController(ChatClient.Builder autoConfiguredBuilder, CrmTools crmTools) {
        this.chatClient = autoConfiguredBuilder
                .defaultSystem("""
                        你是客服智能助手。需要客户、订单、物流、售后、发票等数据时必须调用工具，
                        禁止编造任何数字、单号和状态。
                        写操作（如发起退款）在执行前必须向用户确认。
                        """)
                .defaultTools(crmTools)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    /**
     * <pre>
     * 只要 1 个工具：curl "http://localhost:8080/stage6/chat?message=客户 C1001 还有多少积分？"
     * 需要 2 个工具：curl "http://localhost:8080/stage6/chat?message=客户 C1001 的订单 SO202610010001 到哪了？顺便看看他有哪些优惠券"
     * 多工具编排：  curl "http://localhost:8080/stage6/chat?message=帮 C1001 查一下最近的订单、物流、发票状态和账户余额"
     * </pre>
     */
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
