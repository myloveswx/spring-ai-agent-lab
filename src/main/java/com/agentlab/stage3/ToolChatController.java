package com.agentlab.stage3;

import com.agentlab.stage3.tools.MarketTools;
import com.agentlab.stage3.tools.TimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 3 —— 工具调用（Tool Calling）。
 *
 * <p>这是 Spring AI 2.0 最核心的架构变化之一：
 * <b>工具执行循环从「每个 ChatModel 内部私有的黑盒」上移为 Advisor 链上的一等公民</b>。
 * {@code ToolCallingAdvisor} 由 ChatClient 自动注册（无需任何配置），完整负责
 * 「模型请求工具 → 执行工具 → 把结果回填 → 再次推理」的往返，直到模型不再请求工具。
 *
 * <p>你只需要做两件事：
 * <ol>
 *   <li>把工具挂到请求上（{@code defaultTools} / {@code tools}）</li>
 *   <li>写好 description</li>
 * </ol>
 *
 * <p>观察日志：因为挂了 {@link SimpleLoggerAdvisor}，且配置里把它的日志级别设为 DEBUG，
 * 控制台会打印每次往返的完整 Prompt 与模型响应。
 */
@RestController
@RequestMapping("/stage3")
public class ToolChatController {

    private final ChatClient chatClient;

    public ToolChatController(ChatModel chatModel,
                              TimeTools timeTools,
                              MarketTools marketTools,
                              ToolCallback chineseHolidayChecker) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个实用助手。
                        规则：
                        1. 涉及实时时间、日期推算、指数行情的提问，必须调用工具获取数据，禁止凭记忆编造；
                        2. 工具返回的数据要原样采信，不要「脑补」更精确的数字；
                        3. 最终用简洁自然语言回答用户。
                        """)
                // 异构注册：@Tool POJO + ToolCallback Bean 可以混着传
                .defaultTools(timeTools, marketTools, chineseHolidayChecker)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    /**
     * <pre>
     * 会触发工具：curl "http://localhost:8080/stage3/chat?message=现在几点？"
     * 会触发工具：curl "http://localhost:8080/stage3/chat?message=今天往后 10 天是几号？"
     * 多工具组合：curl "http://localhost:8080/stage3/chat?message=查一下上证指数和创业板指现在多少点，哪个涨得多？"
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
