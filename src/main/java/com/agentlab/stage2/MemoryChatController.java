package com.agentlab.stage2;

import com.agentlab.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stage 2 —— 多轮会话记忆。
 *
 * <p>Spring AI 把「记忆」拆成两个正交的抽象：
 * <ul>
 *   <li>{@link ChatMemory} —— 决策层：保留哪些消息、何时淘汰（默认 {@code MessageWindowChatMemory}，窗口 20 条）</li>
 *   <li>{@code ChatMemoryRepository} —— 存储层：只管存取（默认 {@code InMemoryChatMemoryRepository}）</li>
 * </ul>
 *
 * <p>Spring Boot 会自动装配一个 {@link ChatMemory} Bean，直接用即可。
 *
 * <p><b>必须显式传 conversationId</b>：{@code ChatMemory.CONVERSATION_ID} 是必需参数，
 * 没有默认值，缺失会抛 {@code IllegalArgumentException}。这也是多用户隔离的关键——
 * 服务端按「用户 ID + 会话 ID」派生，绝不要跨用户复用固定值。
 */
@RestController
@RequestMapping("/stage2")
@Tag(name = OpenApiConfig.TAG_STAGE2)
public class MemoryChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public MemoryChatController(ChatModel chatModel, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一位有长期记忆的助手，请自然地把历史对话纳入回答。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 带记忆的对话。同一个 conversationId 连续调用即可验证「记得住」。
     * <pre>
     * curl "http://localhost:8080/stage2/chat?conversationId=u1:demo&message=我叫追光者"
     * curl "http://localhost:8080/stage2/chat?conversationId=u1:demo&message=我叫什么名字？"
     * </pre>
     */
    @GetMapping("/chat")
    @Operation(summary = "带记忆的对话",
            description = "同一个 conversationId 连续调用即可验证「记得住」。"
                    + "conversationId 是记忆的唯一分区键，必须显式传入 —— "
                    + "真实系统里应按「用户 ID + 会话 ID」派生，绝不能跨用户复用固定值，"
                    + "否则 A 用户的对话历史会串进 B 用户的上下文。")
    public String chat(
            @Parameter(description = "会话分区键，建议格式「用户ID:会话ID」", example = "u1:demo")
            @RequestParam String conversationId,
            @Parameter(description = "用户消息", example = "我叫追光者")
            @RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /**
     * 查看某个会话当前记忆里保留的消息条数（教学用途）。
     * <pre>curl "http://localhost:8080/stage2/history/size?conversationId=u1:demo"</pre>
     */
    @GetMapping("/history/size")
    @Operation(summary = "查看记忆条数（教学用）",
            description = "返回记忆层当前**保留**的消息条数，不是数据库里的总条数。"
                    + "MessageWindowChatMemory 默认窗口 20 条，超出部分会被淘汰，"
                    + "所以这个数字始终 ≤ 数据库里的实际行数。")
    public int historySize(
            @Parameter(description = "会话分区键", example = "u1:demo")
            @RequestParam String conversationId) {
        return chatMemory.get(conversationId).size();
    }

    /**
     * 打印某个会话的原始消息列表（能看到 USER / ASSISTANT 交替）。
     * <pre>curl "http://localhost:8080/stage2/history?conversationId=u1:demo"</pre>
     */
    @GetMapping("/history")
    @Operation(summary = "打印会话消息列表（教学用）",
            description = "把消息渲染成「USER -> 内容」的字符串数组，方便肉眼确认上下文装配顺序；"
                    + "受窗口裁剪影响，与数据库内容可能不一致。")
    public List<String> history(
            @Parameter(description = "会话分区键", example = "u1:demo")
            @RequestParam String conversationId) {
        return chatMemory.get(conversationId).stream()
                .map(m -> m.getMessageType() + " -> " + m.getText())
                .toList();
    }

    /**
     * 清空某个会话的记忆。
     * <pre>curl -X DELETE "http://localhost:8080/stage2/history?conversationId=u1:demo"</pre>
     */
    @DeleteMapping("/history")
    @Operation(summary = "清空会话记忆",
            description = "删除该 conversationId 下的全部消息（数据库真实删除，不可撤销）。")
    public String clear(
            @Parameter(description = "会话分区键", example = "u1:demo")
            @RequestParam String conversationId) {
        chatMemory.clear(conversationId);
        return "cleared: " + conversationId;
    }
}
