package com.agentlab.stage6.lab;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.agentlab.stage6.lab.ToolCategory.AFTER_SALES;
import static com.agentlab.stage6.lab.ToolCategory.CUSTOMER;
import static com.agentlab.stage6.lab.ToolCategory.FINANCE;
import static com.agentlab.stage6.lab.ToolCategory.LOGISTICS;
import static com.agentlab.stage6.lab.ToolCategory.ORDER;
import static com.agentlab.stage6.lab.ToolCategory.PRODUCT;

/**
 * CRM 工具的「业务词典」—— 检索增强真正的注入点。
 *
 * <p>这是本实验室最想传达的一个观点：
 * <b>让检索变准，靠的通常不是更聪明的算法，而是补上模型不知道的业务常识。</b>
 * 工具描述里写的是「为指定订单发起退款申请」，而用户嘴上说的是
 * 「钱什么时候能退回来」。中间这段鸿沟，任何纯词法 / 纯向量的通用索引都很难自己填上，
 * 但一张五行代码的同义词表就能填上。
 *
 * <p>词典与工具实现是<b>松耦合</b>的：这里只按工具名登记，
 * 工具本身（{@link com.agentlab.stage6.lab.ToolDictionary} 完全不感知自己的同义词）。
 * 好处是新增工具时不必改工具类，坏处是容易漏登记 ——
 * 所以 {@link #isRegistered(String)} 允许索引层在启动时告警「有工具没进词典」。
 */
public final class ToolDictionary {

    /** 一个工具的业务画像：所属域 + 用户可能用到的口语说法。 */
    public record Entry(ToolCategory category, List<String> synonyms) {

        public Entry(ToolCategory category, String... synonyms) {
            this(category, List.of(synonyms));
        }
    }

    private static final Map<String, Entry> TOOLS = build();

    private ToolDictionary() {
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> m = new HashMap<>();

        // ---- 客户域 ----
        m.put("queryCustomer", new Entry(CUSTOMER,
                "客户档案", "客户资料", "会员信息", "这个人是谁", "查一下客户"));
        m.put("queryCustomerByPhone", new Entry(CUSTOMER,
                "手机号", "电话号码", "手机号码", "根据手机号找", "号码查客户"));
        m.put("queryPoints", new Entry(CUSTOMER,
                "积分", "积分余额", "多少分", "我的分", "攒了多少"));

        // ---- 订单域 ----
        m.put("queryOrder", new Entry(ORDER,
                "订单详情", "订单状态", "这笔单子", "查单号", "订单信息"));
        m.put("listRecentOrders", new Entry(ORDER,
                "最近订单", "历史订单", "买过什么", "最近买", "消费记录", "近期订单"));

        // ---- 物流域 ----
        m.put("queryLogistics", new Entry(LOGISTICS,
                "物流", "快递", "到哪了", "发货了吗", "什么时候到", "什么时候能到",
                "运单", "配送进度", "包裹", "快递单号", "在路上了吗"));

        // ---- 售后域 ----
        m.put("applyRefund", new Entry(AFTER_SALES,
                "退款", "退钱", "退货", "退单", "退回来", "把钱退回来", "申请退款", "退一下",
                "钱什么时候退", "能不能退", "我要退"));
        m.put("createTicket", new Entry(AFTER_SALES,
                "工单", "报修", "投诉", "提个问题", "反馈", "找人工", "客服工单"));
        m.put("queryCoupons", new Entry(AFTER_SALES,
                "优惠券", "券", "折扣", "满减", "有什么券", "优惠"));

        // ---- 商品域 ----
        m.put("queryStock", new Entry(PRODUCT,
                "库存", "还有货吗", "有没有货", "缺货", "存货", "还有没有"));

        // ---- 财务域 ----
        m.put("queryInvoice", new Entry(FINANCE,
                "发票", "开票", "电子发票", "报销凭证", "开个票"));
        m.put("queryContract", new Entry(FINANCE,
                "合同", "协议", "签约", "合同到期", "合同信息"));
        m.put("queryBalance", new Entry(FINANCE,
                "余额", "账户余额", "还有多少钱", "欠费", "账单", "账户还有"));

        return Map.copyOf(m);
    }

    /** 工具名 -> 业务画像；未登记返回 null。 */
    public static Entry entryOf(String toolName) {
        return toolName == null ? null : TOOLS.get(toolName);
    }

    /** 工具名 -> 分类；未登记返回 null。 */
    public static ToolCategory categoryOf(String toolName) {
        Entry e = entryOf(toolName);
        return e == null ? null : e.category();
    }

    /** 工具名 -> 口语同义词列表（可能为空，不返回 null）。 */
    public static List<String> synonymsOf(String toolName) {
        Entry e = entryOf(toolName);
        return e == null ? List.of() : e.synonyms();
    }

    /** 是否已登记。索引层可以用它发现「新加了工具但忘了配词典」的情况。 */
    public static boolean isRegistered(String toolName) {
        return TOOLS.containsKey(toolName);
    }

    /** 登记的工具数量，便于日志与测试断言。 */
    public static int size() {
        return TOOLS.size();
    }
}
