package com.agentlab.persistence.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.agentlab.persistence.entity.ChatMemoryEntity;
import com.agentlab.persistence.mapper.ChatMemoryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * MyBatis-Plus 版的记忆存储层 —— 替换 Spring AI 自带的 {@code JdbcChatMemoryRepository}。
 *
 * <p>Spring AI 把「记忆」拆成两个正交抽象：{@code ChatMemory}（决策层，决定留哪些消息、
 * 窗口多大）与 {@link ChatMemoryRepository}（存储层，只管存取）。换持久层框架，
 * 只需要实现后者，决策层与上层代码一律不动 —— 这正是这个接口存在的意义。
 *
 * <h3>与官方 JdbcChatMemoryRepository 对齐的三处关键语义</h3>
 *
 * <p><b>① {@code saveAll} 是「全量覆盖」而非「追加」</b><br>
 * {@code MessageWindowChatMemory} 每次 {@code add} 之后，都会把<b>整个窗口</b>的消息
 * 列表交给 {@code saveAll}。如果这里做成增量插入，每轮对话都会把旧消息重复写一遍，
 * 表里的数据会指数级膨胀。所以正确做法是：先在<b>同一事务</b>内删掉该会话的全部旧行，
 * 再按顺序批量插入。
 *
 * <p><b>② tool 消息不落库</b><br>
 * {@code ToolResponseMessage} 和带 {@code toolCalls} 的 {@code AssistantMessage}
 * 无法用「一个 content 列」表达，官方实现直接过滤掉并打告警。这里保持一致，
 * 否则要么插入失败（content 为 NULL 违反 NOT NULL），要么丢失工具调用结构。
 *
 * <p><b>③ {@code sequence_id} 用列表下标</b><br>
 * 它是会话内的单调序号，只用于排序保序，没有业务含义。注意是<b>过滤之后</b>的下标。
 */
@Repository
public class MybatisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisChatMemoryRepository.class);

    private final ChatMemoryMapper chatMemoryMapper;

    public MybatisChatMemoryRepository(ChatMemoryMapper chatMemoryMapper) {
        this.chatMemoryMapper = chatMemoryMapper;
    }

    /** 全部会话 ID。 */
    @Override
    public List<String> findConversationIds() {
        return chatMemoryMapper.selectConversationIds();
    }

    /** 某个会话的全部消息，按 sequence_id 升序 —— 与官方 SQL 的 ORDER BY 一致。 */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");

        List<ChatMemoryEntity> rows = chatMemoryMapper.selectList(
                Wrappers.<ChatMemoryEntity>lambdaQuery()
                        .eq(ChatMemoryEntity::getConversationId, conversationId)
                        .orderByAsc(ChatMemoryEntity::getSequenceId));

        return rows.stream()
                .map(this::toMessage)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 全量覆盖保存：事务内「先删该会话全部行，再批量插入」。
     *
     * <p>加 {@code @Transactional} 是必须的 —— 删除与插入之间若失败，
     * 会话记忆会被清空且无法恢复。
     */
    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        List<Message> persistable = messages.stream()
                .filter(message -> !(message instanceof ToolResponseMessage))
                .filter(message -> !(message instanceof AssistantMessage assistant) || !assistant.hasToolCalls())
                .toList();

        if (persistable.size() < messages.size()) {
            log.warn("MyBatis 记忆仓库不支持 tool 消息，已过滤 {} 条（会话：{}）",
                    messages.size() - persistable.size(), conversationId);
        }

        chatMemoryMapper.delete(Wrappers.<ChatMemoryEntity>lambdaQuery()
                .eq(ChatMemoryEntity::getConversationId, conversationId));

        if (persistable.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<ChatMemoryEntity> entities = new ArrayList<>(persistable.size());
        for (int i = 0; i < persistable.size(); i++) {
            Message message = persistable.get(i);
            entities.add(new ChatMemoryEntity(
                    conversationId,
                    message.getText(),
                    // 必须用 name()（大写 USER），不能用 getValue()（小写 user）
                    message.getMessageType().name(),
                    now,
                    (long) i));
        }

        // BaseMapper#insert(Collection) 走 ExecutorType.BATCH，一次往返写完整个窗口
        chatMemoryMapper.insert(entities);
    }

    /** 清空某个会话的记忆。 */
    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        chatMemoryMapper.delete(Wrappers.<ChatMemoryEntity>lambdaQuery()
                .eq(ChatMemoryEntity::getConversationId, conversationId));
    }

    /**
     * 行 → 消息对象。
     *
     * <p>{@code TOOL} 类型理论上不会出现在表里（写入时已过滤），
     * 但手工往表里插数据时可能造出来，这里返回 {@code null} 并在调用处剔除，
     * 避免一条脏数据让整个会话读取失败。
     */
    private Message toMessage(ChatMemoryEntity entity) {
        MessageType type;
        try {
            type = MessageType.valueOf(entity.getType());
        } catch (IllegalArgumentException ex) {
            log.warn("记忆表出现无法识别的消息类型：{}，已跳过该行", entity.getType());
            return null;
        }
        String text = entity.getContent() == null ? "" : entity.getContent();
        return switch (type) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            case TOOL -> null;
        };
    }
}
