package com.agentlab.stage5;

import com.agentlab.stage3.tools.MarketTools;
import com.agentlab.stage5.dto.IndexAnalysis;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 5 —— 结构化输出（Structured Output）。
 *
 * <p>不再解析字符串，而是直接让模型产出 Java 对象：{@code .call().entity(Xxx.class)}。
 * Spring AI 会在发请求前把「目标类型的 JSON Schema + 格式指令」拼进 Prompt，
 * 收到回复后再用 {@code BeanOutputConverter} 反序列化。
 *
 * <p>但「模型返回不合规 JSON」是常态：多包一层 ```json 代码围栏、字段类型写错、缺字段……
 * Spring AI 2.0 新增的 {@link StructuredOutputValidationAdvisor} 就是干这个的——
 * <b>校验失败时自动重试纠正</b>，最多重试 {@code maxRepeatAttempts} 次。
 *
 * <p>本阶段提供两条对照接口：
 * <ul>
 *   <li>{@code /stage5/analyze} —— 纯 {@code .entity()}，朴素但可能偶发解析失败</li>
 *   <li>{@code /stage5/analyze/validated} —— 额外挂自纠错 Advisor</li>
 * </ul>
 */
@RestController
@RequestMapping("/stage5")
public class StructuredOutputController {

    private static final String SYSTEM = """
            你是 A 股行情分析助手。
            只能依据工具返回的数据作答，禁止编造任何数字或事实。
            trend 字段只能是「上涨」「下跌」「震荡」三者之一。
            """;

    private final ChatClient plainClient;
    private final ChatClient validatingClient;

    public StructuredOutputController(ChatModel chatModel, MarketTools marketTools) {
        this.plainClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM)
                .defaultTools(marketTools)
                .build();

        this.validatingClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM)
                .defaultTools(marketTools)
                .defaultAdvisors(StructuredOutputValidationAdvisor.builder()
                        .outputType(IndexAnalysis.class)   // 校验依据的 Schema
                        .maxRepeatAttempts(2)              // 最多自动纠正 2 次
                        .build())
                .build();
    }

    /** 朴素版：只靠 .entity() 转换。 */
    @GetMapping("/analyze")
    public IndexAnalysis analyze(@RequestParam String indexCode) {
        return plainClient.prompt()
                .user(u -> u.text(ANALYSIS_PROMPT).param("code", indexCode))
                .call()
                .entity(IndexAnalysis.class);
    }

    /** 自纠错版：同样的 Prompt，多挂一个 StructuredOutputValidationAdvisor。 */
    @GetMapping("/analyze/validated")
    public IndexAnalysis analyzeValidated(@RequestParam String indexCode) {
        return validatingClient.prompt()
                .user(u -> u.text(ANALYSIS_PROMPT).param("code", indexCode))
                .call()
                .entity(IndexAnalysis.class);
    }

    private static final String ANALYSIS_PROMPT = """
            请查询指数 {code} 的最新行情，并输出一份简短的行情分析：
            - indexCode / indexName：指数代码与名称
            - trend：趋势判断，只能是「上涨」「下跌」「震荡」
            - confidence：0~100 的整数，表示你对结论的把握程度
            - drivers：2~3 条驱动因素
            - summary：一句话总结（不超过 50 字）
            """;
}
