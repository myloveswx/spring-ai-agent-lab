package com.agentlab.stage4;

import com.agentlab.config.OpenApiConfig;
import com.agentlab.stage3.tools.MarketTools;
import com.agentlab.stage3.tools.TimeTools;
import com.agentlab.stage4.advisor.TimingAdvisor;
import com.agentlab.stage4.advisor.ToolLoopObserverAdvisor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 4 —— 自定义 Advisor 与工具循环观测。
 *
 * <p>这里挂了两个 Advisor，一外一内，正好演示「order 决定位置」这一条最重要的规则：
 *
 * <pre>
 *   请求 ──▶ TimingAdvisor (order = MIN+100)          ← 最外层，测总耗时（含整个工具循环）
 *              └─▶ ToolCallingAdvisor (order = MIN+300)  ← 框架自动注册，负责工具循环
 *                    └─▶ ToolLoopObserverAdvisor (MIN+310) ← 循环内部，每轮迭代都被调用
 *                          └─▶ ChatModel
 * </pre>
 *
 * <p>调用一个需要多次工具往返的问题，日志里就能看到「每轮工具调用」与「总耗时」的对应关系。
 */
@RestController
@RequestMapping("/stage4")
@Tag(name = OpenApiConfig.TAG_STAGE4)
public class AdvisorChatController {

    private final ChatClient chatClient;

    public AdvisorChatController(ChatModel chatModel,
                                 TimeTools timeTools,
                                 MarketTools marketTools) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是 A 股行情助手。涉及时间与行情必须调用工具，禁止编造数据。
                        可以一次调用多个工具，再综合回答。
                        """)
                .defaultTools(timeTools, marketTools)
                .defaultAdvisors(
                        // 外层：测「一次完整对话（含多轮工具往返）」的总耗时
                        new TimingAdvisor("Timing(outside)", Ordered.HIGHEST_PRECEDENCE + 100),
                        // 内层：观测工具循环每一轮的动作
                        new ToolLoopObserverAdvisor())
                .build();
    }

    /**
     * <pre>
     * 单轮工具：curl "http://localhost:8080/stage4/chat?message=现在几点？"
     * 多轮工具：curl "http://localhost:8080/stage4/chat?message=先告诉我今天日期，再查上证指数和深证成指的行情，最后比较涨跌幅"
     * </pre>
     * 观察控制台：Timing(outside) 只打印一次（总耗时），ToolLoop 会打印多轮。
     */
    @GetMapping("/chat")
    @Operation(summary = "Advisor 链 + 工具循环观测",
            description = "外层 TimingAdvisor 测「一次完整对话（含多轮工具往返）」的总耗时，"
                    + "内层 ToolLoopObserverAdvisor 观测每一轮迭代 —— 二者靠 order 决定嵌套关系。"
                    + "本接口的返回值与 Stage 3 没有区别，价值全在**控制台日志**里。")
    public String chat(
            @Parameter(description = "用户提问，建议用需要多轮工具的问题来看清嵌套关系",
                    example = "先告诉我今天日期，再查上证指数和深证成指的行情，最后比较涨跌幅")
            @RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
