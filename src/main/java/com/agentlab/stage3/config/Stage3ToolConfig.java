package com.agentlab.stage3.config;

import com.agentlab.stage3.tools.MarketTools;
import com.agentlab.stage3.tools.TimeTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stage 3 的工具装配。
 *
 * <p>这里同时演示 Spring AI 2.0 支持的<b>三种工具定义方式中的两种</b>：
 * <ol>
 *   <li>声明式 {@code @Tool} POJO（见 {@link TimeTools} / {@link MarketTools}）—— 注册为普通 Bean 即可</li>
 *   <li>程序式 {@link FunctionToolCallback}（见下方 {@code chineseHolidayChecker}）—— 产出 {@link ToolCallback} Bean</li>
 * </ol>
 *
 * <p><b>2.0 重要变更</b>：1.x 的「用 {@code toolNames()} 按名字解析裸 Function Bean」机制
 * （{@code SpringBeanToolCallbackResolver}）已被移除。现在工具<b>必须</b>注册成显式的
 * {@link ToolCallback} Bean，或通过 {@code .tools(...)} 随请求下发。
 */
@Configuration
public class Stage3ToolConfig {

    @Bean
    public TimeTools timeTools() {
        return new TimeTools();
    }

    @Bean
    public MarketTools marketTools() {
        return new MarketTools();
    }

    public record HolidayRequest(String date) {
    }

    public record HolidayResult(String date, boolean holiday, String holidayName) {
    }

    /**
     * 用 {@link FunctionToolCallback} 把一个普通方法暴露成工具。
     *
     * <p>上下文里的 {@link ToolCallback} Bean 会被 {@code StaticToolCallbackResolver} 自动发现，
     * 也可以注入后显式传给 {@code .tools(...)}。
     */
    @Bean
    public ToolCallback chineseHolidayChecker() {
        return FunctionToolCallback
                .builder("isChineseHoliday", (HolidayRequest request) -> {
                    String date = request.date() == null ? "" : request.date().trim();
                    String tail = date.length() >= 5 ? date.substring(date.length() - 5) : date;
                    String holidayName = switch (tail) {
                        case "01-01" -> "元旦";
                        case "05-01" -> "劳动节";
                        case "10-01" -> "国庆节";
                        default -> null;
                    };
                    return new HolidayResult(date,
                            holidayName != null,
                            holidayName != null ? holidayName : "演示工具仅识别 01-01 / 05-01 / 10-01");
                })
                .description("判断某一天是否为中国法定节假日（演示版：仅识别元旦、劳动节、国庆节）")
                .inputType(HolidayRequest.class)
                .build();
    }
}
