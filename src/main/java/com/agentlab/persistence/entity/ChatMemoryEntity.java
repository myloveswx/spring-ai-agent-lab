package com.agentlab.persistence.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Agent 对话记忆表实体 —— 对应 {@code SPRING_AI_CHAT_MEMORY}。
 *
 * <p>表结构沿用 Spring AI 2.0.1 官方定义（见 mysql-setup/schema/agent-memory.sql），
 * 一行 = 一条消息。
 *
 * <h3>两个必须注意的映射细节</h3>
 *
 * <p><b>① 表没有主键</b>：官方 schema 只建了两个组合索引，没有主键列。
 * 于是这里<b>不能</b>声明 {@code @TableId}，也因此<b>用不了</b>
 * {@code selectById / updateById / deleteById} 这一族按主键操作的方法。
 * 好在 {@code ChatMemoryRepository} 的四个方法本来就不需要主键：
 * 增删查全部以 {@code conversation_id} 为条件，用 Wrapper 即可。
 *
 * <p><b>② 两个列名是 SQL 关键字</b>：{@code type} 与 {@code timestamp}。
 * 必须用反引号包住，否则 MyBatis-Plus 拼出来的 SQL 在 MySQL 上会语法报错。
 * 反引号写在 {@code @TableField} 里会被原样带进生成的 SQL，这是 MyBatis-Plus
 * 官方推荐的处理方式。
 */
@TableName("SPRING_AI_CHAT_MEMORY")
public class ChatMemoryEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话标识，对应 ChatMemory.CONVERSATION_ID。 */
    @TableField("conversation_id")
    private String conversationId;

    /** 消息正文。 */
    @TableField("content")
    private String content;

    /**
     * 消息角色，取值必须是 ENUM 允许的 {@code USER / ASSISTANT / SYSTEM / TOOL}。
     *
     * <p>注意写入时要用 {@code MessageType#name()}（大写得 {@code USER}），
     * <b>不能</b>用 {@code MessageType#getValue()} —— 后者返回小写的 {@code "user"}，
     * 与 ENUM 定义不匹配，严格模式下会直接插入失败。
     */
    @TableField("`type`")
    private String type;

    /** 写入时间。 */
    @TableField("`timestamp`")
    private LocalDateTime timestamp;

    /** 会话内单调递增序号，用于稳定排序（同一毫秒内的多条消息靠它保序）。 */
    @TableField("sequence_id")
    private Long sequenceId;

    public ChatMemoryEntity() {
    }

    public ChatMemoryEntity(String conversationId, String content, String type,
                            LocalDateTime timestamp, Long sequenceId) {
        this.conversationId = conversationId;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
        this.sequenceId = sequenceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Long getSequenceId() {
        return sequenceId;
    }

    public void setSequenceId(Long sequenceId) {
        this.sequenceId = sequenceId;
    }

    @Override
    public String toString() {
        return "ChatMemoryEntity{conversationId='" + conversationId + "', type='" + type
                + "', sequenceId=" + sequenceId + ", content='" + content + "'}";
    }
}
