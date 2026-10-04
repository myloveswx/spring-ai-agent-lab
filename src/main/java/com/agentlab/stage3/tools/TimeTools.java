package com.agentlab.stage3.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 用 {@code @Tool} 注解声明的工具。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code description} 极其重要——模型完全靠它判断「何时该调用」，写不清楚必然乱调</li>
 *   <li>参数默认全部必填，可选参数加 {@code @ToolParam(required = false)} 或 {@code @Nullable}</li>
 *   <li>方法可见性不限（public / package-private / private 均可）</li>
 *   <li>返回对象会被自动序列化成 JSON 交给模型</li>
 * </ul>
 */
public class TimeTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(description = "获取当前日期和时间（东八区，格式 yyyy-MM-dd HH:mm:ss）")
    public String currentDateTime() {
        return LocalDateTime.now(ZONE).format(DATE_TIME);
    }

    @Tool(description = "计算相对于今天偏移若干天后的日期。days 为正表示未来，为负表示过去")
    public String dateOffset(
            @ToolParam(description = "相对今天的天数偏移，例如 7 表示 7 天后，-3 表示 3 天前") int days) {
        LocalDate target = LocalDate.now(ZONE).plusDays(days);
        return target + "（" + weekdayName(target.getDayOfWeek().getValue()) + "）";
    }

    private String weekdayName(int isoWeekday) {
        return switch (isoWeekday) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            default -> "周日";
        };
    }
}
