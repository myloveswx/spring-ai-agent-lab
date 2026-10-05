package com.agentlab.stage6.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Stage 6 的工具集：模拟一个 CRM/客服系统的工具面（13 个工具）。
 *
 * <p>为什么要这么多工具？因为当工具数量膨胀到几十上百个时，把「全部工具定义」
 * 塞进每一次请求会带来两个问题：
 * <ol>
 *   <li>Token 成本暴涨（每个工具的 JSON Schema 都要占 token）</li>
 *   <li>模型选择困难，容易选错工具</li>
 * </ol>
 *
 * <p>这就是 Spring AI 2.0 引入 {@code ToolSearchToolCallingAdvisor}（渐进式工具披露）的原因：
 * 它先对全量工具建一次索引，每轮只把<b>最相关的少数几个</b>工具定义发给模型。
 * 官方实测可节省 34% ~ 64% 的 token。
 *
 * <p>数据全部为演示用模拟数据。
 */
public class CrmTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String now() {
        return LocalDateTime.now(ZONE).format(DATE_TIME);
    }

    // ------------------------------------------------------------------
    // 客户域
    // ------------------------------------------------------------------

    public record CustomerProfile(String customerId, String name, String level, String phone, String city, String asOf) {
    }

    @Tool(description = "按客户 ID 查询客户档案，返回姓名、会员等级、手机号、所在城市")
    public CustomerProfile queryCustomer(
            @ToolParam(description = "客户 ID，例如 C1001") String customerId) {
        return new CustomerProfile(customerId, "演示客户-" + customerId, "黄金会员",
                "138****0000", "深圳", now());
    }

    @Tool(description = "按手机号查询客户 ID 与会员等级")
    public String queryCustomerByPhone(
            @ToolParam(description = "客户手机号，11 位") String phone) {
        return "手机号 " + phone + " 对应客户 C1001（黄金会员），查询时间 " + now();
    }

    @Tool(description = "查询客户的积分余额")
    public String queryPoints(
            @ToolParam(description = "客户 ID") String customerId) {
        return "客户 " + customerId + " 当前积分：12800 分（查询时间 " + now() + "）";
    }

    // ------------------------------------------------------------------
    // 订单域
    // ------------------------------------------------------------------

    public record OrderInfo(String orderId, String status, double amount, String createdAt, String logisticsNo) {
    }

    @Tool(description = "按订单号查询订单详情，返回订单状态、金额、下单时间、物流单号")
    public OrderInfo queryOrder(
            @ToolParam(description = "订单号，例如 SO202610010001") String orderId) {
        return new OrderInfo(orderId, "已发货", 399.00, now(), "SF" + Math.abs(orderId.hashCode() % 1000000));
    }

    @Tool(description = "查询某客户最近 N 笔订单的订单号与状态")
    public List<String> listRecentOrders(
            @ToolParam(description = "客户 ID") String customerId,
            @ToolParam(description = "返回条数，1~20", required = false) Integer limit) {
        int n = limit == null ? 3 : Math.max(1, Math.min(20, limit));
        return java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> "SO2026100100" + String.format("%02d", i) + " / 已发货 / ¥" + (100 * i))
                .toList();
    }

    @Tool(description = "查询订单对应的物流轨迹最新一条记录")
    public String queryLogistics(
            @ToolParam(description = "物流单号或订单号") String code) {
        return "运单 " + code + " 最新轨迹：快件已到达【深圳南山集散中心】（" + now() + "）";
    }

    // ------------------------------------------------------------------
    // 售后域
    // ------------------------------------------------------------------

    @Tool(description = "为指定订单发起退款申请，返回退款受理单号。属于写操作，调用前需向用户确认")
    public String applyRefund(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "退款原因") String reason) {
        return "已受理订单 " + orderId + " 的退款申请（原因：" + reason + "），退款单号 RF"
                + Math.abs(orderId.hashCode() % 100000) + "，预计 3 个工作日到账。";
    }

    @Tool(description = "创建客服工单，返回工单号")
    public String createTicket(
            @ToolParam(description = "工单标题") String title,
            @ToolParam(description = "问题详细描述") String detail,
            @ToolParam(description = "优先级：LOW / NORMAL / HIGH", required = false) String priority) {
        return "工单已创建，编号 TK" + System.currentTimeMillis() % 1000000
                + "，优先级 " + (priority == null ? "NORMAL" : priority)
                + "，标题：" + title;
    }

    // ------------------------------------------------------------------
    // 营销域
    // ------------------------------------------------------------------

    @Tool(description = "查询客户当前可用的优惠券列表")
    public List<String> queryCoupons(
            @ToolParam(description = "客户 ID") String customerId) {
        return List.of("满 200 减 30（有效期至 2026-12-31）",
                "免运费券 ×2",
                "生日双倍积分券");
    }

    @Tool(description = "查询指定商品当前的库存数量")
    public String queryStock(
            @ToolParam(description = "商品 SKU，例如 SKU-8899") String sku) {
        return "SKU " + sku + " 当前可用库存：" + (Math.abs(sku.hashCode()) % 500) + " 件（" + now() + "）";
    }

    // ------------------------------------------------------------------
    // 财务域
    // ------------------------------------------------------------------

    @Tool(description = "查询订单对应的电子发票状态与下载链接")
    public String queryInvoice(
            @ToolParam(description = "订单号") String orderId) {
        return "订单 " + orderId + " 的电子发票已开具，状态：可下载，链接：https://example.com/invoice/" + orderId;
    }

    @Tool(description = "查询客户的合同信息，返回合同编号与到期日")
    public String queryContract(
            @ToolParam(description = "客户 ID") String customerId) {
        return "客户 " + customerId + " 的当前合同：CT-2026-0088，服务期至 2027-03-31。";
    }

    @Tool(description = "查询客户的账户余额与最近一次账单金额")
    public String queryBalance(
            @ToolParam(description = "客户 ID") String customerId) {
        return "客户 " + customerId + " 账户余额：¥" + (Math.abs(customerId.hashCode()) % 10000)
                + "，最近账单：¥299.00（" + now() + "）";
    }
}
