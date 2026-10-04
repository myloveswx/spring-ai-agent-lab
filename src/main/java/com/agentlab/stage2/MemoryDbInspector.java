package com.agentlab.stage2;

import java.util.List;
import java.util.Map;

import com.agentlab.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agentlab.persistence.entity.ChatMemoryEntity;
import com.agentlab.persistence.mapper.ChatMemoryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * Stage 2 进阶 —— 直接读写 MySQL 里的记忆表，用来证明「记忆真的落库了」。
 *
 * <p>持久层走 {@link ChatMemoryMapper}（MyBatis-Plus），与业务侧用的
 * {@code MybatisChatMemoryRepository} 是同一套映射，因此这里查到什么，
 * 就是记忆层实际存了什么。
 *
 * <p>为什么不复用 {@code ChatMemory#get()} 来验证：那条路径会经过
 * {@code MessageWindowChatMemory} 的窗口裁剪（默认只保留最近 20 条），
 * 看到的是「记忆层认为该保留的」，而不是「数据库里实际存的」。
 * 想确认落库效果，必须绕开记忆层直查表。
 *
 * <pre>
 * 1) 不经过大模型，直接往记忆里写两条（验证写入链路）
 *    curl -X POST "http://localhost:8080/stage2/db/seed?conversationId=demo:db&amp;text=你好"
 *
 * 2) 直查数据库，看到刚写进去的两条 —— 说明底层存储确实生效
 *    curl "http://localhost:8080/stage2/db/rows?conversationId=demo:db"
 *
 * 3) 全库会话概览 / 表级统计
 *    curl "http://localhost:8080/stage2/db/conversations"
 *    curl "http://localhost:8080/stage2/db/stats"
 * </pre>
 */
@RestController
@RequestMapping("/stage2/db")
@Tag(name = OpenApiConfig.TAG_STAGE2)
public class MemoryDbInspector {

    private static final String TABLE = "SPRING_AI_CHAT_MEMORY";

    private final ChatMemoryMapper chatMemoryMapper;
    private final ChatMemory chatMemory;

    public MemoryDbInspector(ChatMemoryMapper chatMemoryMapper, ChatMemory chatMemory) {
        this.chatMemoryMapper = chatMemoryMapper;
        this.chatMemory = chatMemory;
    }

    /**
     * 往 {@link ChatMemory} 里写一对 USER / ASSISTANT 消息。
     *
     * <p>刻意<b>不</b>经过大模型：如果这样写完之后数据库里查得到，
     * 就证明注入的 {@link ChatMemory} 底层确实是 MySQL 存储，
     * 而不是默认的 {@code InMemoryChatMemoryRepository}。
     */
    @PostMapping("/seed")
    @Operation(summary = "写入一对消息（不经过大模型）",
            description = "直接往 ChatMemory 里 add 一条 USER + 一条 ASSISTANT，"
                    + "刻意不调用模型 —— 如果这样写完之后数据库里能查到，"
                    + "就证明注入的 ChatMemory 底层确实是 MySQL，而不是默认的内存实现。")
    public String seed(
            @Parameter(description = "会话分区键", example = "demo:db")
            @RequestParam String conversationId,
            @Parameter(description = "写入的消息文本（可含中文，用于顺带验证落库编码）",
                    example = "你好，中文落库验证")
            @RequestParam String text) {
        chatMemory.add(conversationId, new UserMessage(text));
        chatMemory.add(conversationId, new AssistantMessage("[seed] 已收到：" + text));
        return "seeded 2 messages into conversation: " + conversationId;
    }

    /**
     * 某个会话的全部消息（按 sequence_id 排序，不受窗口裁剪影响）。
     *
     * <p>这里直接返回实体列表 —— 演示 MyBatis-Plus 的自动列名映射：
     * 下划线列名 {@code conversation_id} 自动落到驼峰属性 {@code conversationId}。
     */
    @GetMapping("/rows")
    @Operation(summary = "查询某会话的全部消息行",
            description = "按 sequence_id 升序返回，**不受窗口裁剪影响** —— "
                    + "这是与 /stage2/history 的关键区别：后者看到的是记忆层认为该保留的，"
                    + "这里看到的是数据库里实际存的。")
    public List<ChatMemoryEntity> rows(
            @Parameter(description = "会话分区键", example = "demo:db")
            @RequestParam String conversationId) {
        return chatMemoryMapper.selectList(
                Wrappers.<ChatMemoryEntity>lambdaQuery()
                        .eq(ChatMemoryEntity::getConversationId, conversationId)
                        .orderByAsc(ChatMemoryEntity::getSequenceId));
    }

    /** 全库会话概览：每个会话多少条、最后写入时间。 */
    @GetMapping("/conversations")
    @Operation(summary = "全库会话概览",
            description = "每个会话的条数与最后写入时间，用于确认记忆分区是否符合预期。")
    public List<Map<String, Object>> conversations() {
        return chatMemoryMapper.selectConversationSummaries();
    }

    /** 表级统计 + 确认库名与字符集。 */
    @GetMapping("/stats")
    @Operation(summary = "表级统计与库信息",
            description = "顺带回传当前连接的库名与字符集 —— 排查中文乱码时，"
                    + "先确认服务端看到的 charset 是 utf8mb4 还是别的。")
    public Map<String, Object> stats() {
        Long total = chatMemoryMapper.selectCount(Wrappers.emptyWrapper());
        String db = chatMemoryMapper.selectCurrentDatabase();
        String charset = chatMemoryMapper.selectCurrentCharset();
        int conversations = chatMemoryMapper.selectConversationIds().size();
        return Map.of(
                "database", db == null ? "(unknown)" : db,
                "charset", charset == null ? "(unknown)" : charset,
                "table", TABLE,
                "persistence", "MyBatis-Plus (MybatisChatMemoryRepository)",
                "totalMessages", total == null ? 0L : total,
                "totalConversations", conversations);
    }
}
