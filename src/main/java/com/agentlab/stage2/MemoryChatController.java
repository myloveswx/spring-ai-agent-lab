package com.agentlab.stage2;

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
    public String chat(@RequestParam String conversationId,
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
    public int historySize(@RequestParam String conversationId) {
        return chatMemory.get(conversationId).size();
    }

    /**
     * 打印某个会话的原始消息列表（能看到 USER / ASSISTANT 交替）。
     * <pre>curl "http://localhost:8080/stage2/history?conversationId=u1:demo"</pre>
     */
    @GetMapping("/history")
    public List<String> history(@RequestParam String conversationId) {
        return chatMemory.get(conversationId).stream()
                .map(m -> m.getMessageType() + " -> " + m.getText())
                .toList();
    }

    /**
     * 清空某个会话的记忆。
     * <pre>curl -X DELETE "http://localhost:8080/stage2/history?conversationId=u1:demo"</pre>
     */
    @DeleteMapping("/history")
    public String clear(@RequestParam String conversationId) {
        chatMemory.clear(conversationId);
        return "cleared: " + conversationId;
    }
}
