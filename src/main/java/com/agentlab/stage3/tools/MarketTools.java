package com.agentlab.stage3.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行情工具（返回结构化对象，用于演示「工具返回类型会被序列化成 JSON」）。
 *
 * <p>注意：这里是<b>演示用模拟数据</b>，不是真实行情，绝不能用于投资决策。
 * 换成真实数据源时，只要替换方法体即可，模型侧完全无感——这正是工具抽象的价值。
 */
public class MarketTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> INDEX_NAMES = new LinkedHashMap<>();

    static {
        INDEX_NAMES.put("000001", "上证指数");
        INDEX_NAMES.put("399001", "深证成指");
        INDEX_NAMES.put("399006", "创业板指");
    }

    /** 工具返回的 DTO —— 会被自动序列化成 JSON */
    public record IndexQuote(String code,
                             String name,
                             double point,
                             double changePercent,
                             String direction,
                             String asOf,
                             String notice) {
    }

    @Tool(description = "列出当前支持的 A 股指数代码及其名称")
    public String listSupportedIndices() {
        StringBuilder sb = new StringBuilder("支持的指数：");
        INDEX_NAMES.forEach((code, name) -> sb.append(code).append("=").append(name).append("；"));
        return sb.toString();
    }

    @Tool(description = "查询 A 股指数的最新行情快照（演示用模拟数据，非真实行情）")
    public IndexQuote indexQuote(
            @ToolParam(description = "指数代码，可选：000001（上证指数）、399001（深证成指）、399006（创业板指）") String code) {
        String normalized = code == null ? "" : code.trim();
        String name = INDEX_NAMES.getOrDefault(normalized, "未知指数");

        double base = switch (normalized) {
            case "000001" -> 3200;
            case "399001" -> 10500;
            case "399006" -> 2100;
            default -> 1000;
        };

        // 用代码哈希生成「稳定的模拟数据」，避免每次调用结果乱跳
        int seed = Math.abs(normalized.hashCode());
        double point = Math.round((base + (seed % 100)) * 100) / 100.0;
        double changePercent = Math.round(((seed % 61) - 30) / 10.0 * 100) / 100.0;
        String direction = changePercent > 0.05 ? "上涨" : (changePercent < -0.05 ? "下跌" : "震荡");
        String asOf = LocalDateTime.now(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return new IndexQuote(normalized, name, point, changePercent, direction, asOf, "演示数据，非真实行情");
    }
}
