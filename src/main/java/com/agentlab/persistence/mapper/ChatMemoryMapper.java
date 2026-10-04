package com.agentlab.persistence.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Select;

import com.agentlab.persistence.entity.ChatMemoryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 记忆表 Mapper —— 继承 {@link BaseMapper} 即自动获得单表 CRUD 能力，
 * 无需写一行 XML。
 *
 * <p>这里刻意演示 MyBatis-Plus 的两种风格并存：
 * <ul>
 *   <li>{@code BaseMapper} 通用方法（{@code selectList} / {@code insert} / {@code delete}）
 *       —— 配合 {@code Wrapper} 使用，覆盖绝大多数单表操作；</li>
 *   <li>{@code @Select} 注解 SQL —— 处理聚合、DISTINCT、函数调用等
 *       Wrapper 表达不出来的场景。</li>
 * </ul>
 *
 * <p>MyBatis-Plus 完全兼容原生 MyBatis 注解，两者可以混用，不存在「用了
 * MyBatis-Plus 就不能写 SQL」的限制。
 */
public interface ChatMemoryMapper extends BaseMapper<ChatMemoryEntity> {

    /** 全库会话 ID 列表（对应 ChatMemoryRepository#findConversationIds）。 */
    @Select("SELECT DISTINCT conversation_id FROM SPRING_AI_CHAT_MEMORY ORDER BY conversation_id")
    List<String> selectConversationIds();

    /** 当前库名，仅用于诊断端点展示。 */
    @Select("SELECT DATABASE()")
    String selectCurrentDatabase();

    /** 当前库的默认字符集，仅用于诊断端点确认中文没有乱码风险。 */
    @Select("SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA "
            + "WHERE SCHEMA_NAME = DATABASE()")
    String selectCurrentCharset();

    /**
     * 会话概览：每个会话多少条消息、最后写入时间。
     *
     * <p>别名刻意写成<b>不带下划线</b>的驼峰形式：MyBatis 在 resultType 为 Map 时，
     * key 是结果集的列标签，而 {@code map-underscore-to-camel-case} 对 Map 结果是否生效
     * 在不同版本/配置下并不一致。用无下划线别名可以让返回的 key 完全确定。
     */
    @Select("SELECT conversation_id AS conversationId, COUNT(*) AS msgCount, "
            + "MAX(`timestamp`) AS lastAt FROM SPRING_AI_CHAT_MEMORY "
            + "GROUP BY conversation_id ORDER BY lastAt DESC")
    List<Map<String, Object>> selectConversationSummaries();
}
