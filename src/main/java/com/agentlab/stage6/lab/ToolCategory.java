package com.agentlab.stage6.lab;

/**
 * 工具的业务分域。
 *
 * <p>存在的意义是演示 {@code ToolSearchRequest.categoryFilter()} 这条通道：
 * 当工具数量膨胀到上百个时，纯语义检索偶尔会「跨域误召回」
 * （问物流却召回退款工具），而业务上我们往往<b>事先就知道</b>这一轮该在哪个域里找。
 * 把「域」作为一层硬过滤前置，比事后调打分权重更可靠。
 *
 * <p>注意一个设计事实：{@code ToolIndex} 接口拿到的只有
 * {@code ToolReference(toolName, relevanceScore, summary)}，
 * <b>没有任何业务元数据通道</b>。所以分类信息必须由索引实现自己维护
 * （本实验室的做法是查 {@link ToolDictionary}），这本身就是「扩展索引」时
 * 必须面对的第一个问题。
 */
public enum ToolCategory {

    CUSTOMER("customer", "客户域", "客户 会员 档案 等级 手机号 积分"),
    ORDER("order", "订单域", "订单 下单 交易 采购"),
    LOGISTICS("logistics", "物流域", "物流 快递 发货 运单 轨迹 配送 包裹"),
    AFTER_SALES("aftersales", "售后域", "售后 退款 退货 退钱 工单 投诉 维修"),
    FINANCE("finance", "财务域", "财务 发票 合同 余额 账单 支付 报销"),
    PRODUCT("product", "商品域", "商品 货品 库存 仓库 sku");

    private final String code;
    private final String label;
    private final String aliases;

    ToolCategory(String code, String label, String aliases) {
        this.code = code;
        this.label = label;
        this.aliases = aliases;
    }

    /** 英文 code，作为 categoryFilter 的规范写法。 */
    public String code() {
        return code;
    }

    /** 中文短名，用于展示与日志。 */
    public String label() {
        return label;
    }

    /** 该域的典型词面线索，供实验室接口做「自然语言 -> 分类」的兜底推断。 */
    public String aliases() {
        return aliases;
    }

    /**
     * 解析 categoryFilter：支持 {@code customer}（code）、{@code 客户域}（label）
     * 以及随便一句含该域线索词的话（如「物流相关」）。
     *
     * @return 命中的分类；识别不出来时返回 {@code null}（调用方应当视作「不过滤」）
     */
    public static ToolCategory fromAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String a = alias.trim().toLowerCase();
        for (ToolCategory c : values()) {
            if (c.code.equals(a) || c.label.equals(alias.trim())) {
                return c;
            }
        }
        // 词面兜底：出现该域任一别名即算命中。
        for (ToolCategory c : values()) {
            for (String clue : c.aliases.split("\\s+")) {
                if (!clue.isEmpty() && a.contains(clue.toLowerCase())) {
                    return c;
                }
            }
        }
        return null;
    }
}
