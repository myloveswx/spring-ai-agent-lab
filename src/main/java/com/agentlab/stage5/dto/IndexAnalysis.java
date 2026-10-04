package com.agentlab.stage5.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 结构化输出的目标类型。
 *
 * <p>{@code @JsonPropertyOrder} 可以固定属性在生成 JSON Schema 中的顺序，
 * 对大模型来说「先给结论字段、再给依据字段」通常更稳。
 */
@JsonPropertyOrder({"indexCode", "indexName", "trend", "confidence", "drivers", "summary"})
public record IndexAnalysis(
        /** 指数代码 */
        String indexCode,
        /** 指数名称 */
        String indexName,
        /** 趋势判断：只能是「上涨」「下跌」「震荡」 */
        String trend,
        /** 置信度：0 ~ 100 */
        int confidence,
        /** 驱动因素，2~3 条 */
        List<String> drivers,
        /** 一句话总结 */
        String summary
) {
}
