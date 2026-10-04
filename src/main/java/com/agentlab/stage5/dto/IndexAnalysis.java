package com.agentlab.stage5.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 结构化输出的目标类型。
 *
 * <p>{@code @JsonPropertyOrder} 可以固定属性在生成 JSON Schema 中的顺序，
 * 对大模型来说「先给结论字段、再给依据字段」通常更稳。
 *
 * <p>{@link Schema} 注解在这里有两个作用：
 * <ul>
 *   <li>让 Swagger UI 的响应示例带上字段含义、取值约束与样例值（纯展示价值）</li>
 *   <li>注意 —— 它<b>不会</b>影响发给模型的 JSON Schema，
 *       那条链路走的是 {@code BeanOutputConverter} 从 record 结构 + Jackson 注解推导出的 Schema。
 *       想约束模型输出，靠的是 Prompt 里的文字说明与该推导结果，不是这里的注解。</li>
 * </ul>
 */
@JsonPropertyOrder({"indexCode", "indexName", "trend", "confidence", "drivers", "summary"})
@Schema(name = "IndexAnalysis", description = "指数行情分析结果（结构化输出目标类型）")
public record IndexAnalysis(
        @Schema(description = "指数代码", example = "000001")
        String indexCode,
        @Schema(description = "指数名称", example = "上证指数")
        String indexName,
        @Schema(description = "趋势判断，只能是三者之一：上涨 / 下跌 / 震荡", example = "上涨",
                allowableValues = {"上涨", "下跌", "震荡"})
        String trend,
        @Schema(description = "置信度，0 ~ 100 的整数", example = "72", minimum = "0", maximum = "100")
        int confidence,
        @Schema(description = "驱动因素，2~3 条",
                example = "[\"政策面利好落地\", \"成交量温和放大\"]")
        List<String> drivers,
        @Schema(description = "一句话总结，不超过 50 字", example = "权重股带动指数温和上行，短期偏乐观。")
        String summary
) {
}
