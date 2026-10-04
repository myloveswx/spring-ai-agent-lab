package com.agentlab.stage3;

import com.agentlab.stage3.tools.MarketTools;
import com.agentlab.stage3.tools.TimeTools;
import com.agentlab.stage6.tools.CrmTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具层的纯单元测试（不需要 Spring 上下文、不需要 API Key）。
 *
 * <p>这其实是一个很实用的工程习惯：<b>把业务逻辑从工具方法里抽出来，让工具方法只剩一层薄壳</b>，
 * 这样工具可以被确定性地单测覆盖。工具方法本身写错（比如参数描述误导模型、
 * 返回结构不稳定）是 Agent 项目最常见的线上事故来源。
 */
class ToolsTest {

    private final TimeTools timeTools = new TimeTools();
    private final MarketTools marketTools = new MarketTools();
    private final CrmTools crmTools = new CrmTools();

    @Test
    @DisplayName("TimeTools: 日期偏移应基于今天且格式稳定")
    void dateOffsetShouldBeRelativeToToday() {
        String result = timeTools.dateOffset(7);
        assertEquals(LocalDate.now().plusDays(7).toString(), result.substring(0, 10));
        assertTrue(result.contains("（"));
    }

    @Test
    @DisplayName("TimeTools: 当前时间格式应为 yyyy-MM-dd HH:mm:ss")
    void currentDateTimeShouldMatchPattern() {
        assertTrue(timeTools.currentDateTime().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    @DisplayName("MarketTools: 同一代码多次调用必须返回一致结果（工具必须确定性）")
    void indexQuoteShouldBeDeterministic() {
        MarketTools.IndexQuote first = marketTools.indexQuote("000001");
        MarketTools.IndexQuote second = marketTools.indexQuote("000001");
        assertEquals(first.point(), second.point());
        assertEquals("上证指数", first.name());
        assertEquals(first.direction(), second.direction());
    }

    @Test
    @DisplayName("MarketTools: 未知代码应优雅降级而不是抛异常")
    void unknownIndexShouldDegradeGracefully() {
        MarketTools.IndexQuote quote = marketTools.indexQuote("999999");
        assertEquals("未知指数", quote.name());
        assertNotNull(quote.asOf());
    }

    @Test
    @DisplayName("CrmTools: 客户查询应回显传入的客户 ID")
    void queryCustomerShouldEchoId() {
        CrmTools.CustomerProfile profile = crmTools.queryCustomer("C1001");
        assertEquals("C1001", profile.customerId());
        assertNotNull(profile.level());
    }

    @Test
    @DisplayName("CrmTools: listRecentOrders 的 limit 应被夹紧到 1~20")
    void listRecentOrdersShouldClampLimit() {
        assertEquals(1, crmTools.listRecentOrders("C1001", 0).size());
        assertEquals(20, crmTools.listRecentOrders("C1001", 999).size());
        assertEquals(3, crmTools.listRecentOrders("C1001", null).size());
    }
}
