package com.agentlab.persistence.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆装配配置 —— 把「决策层」和「存储层」显式接在一起。
 *
 * <p>为什么需要这个类：我们移除了 {@code spring-ai-starter-model-chat-memory-repository-jdbc}，
 * 它原本顺带带来 {@code spring-ai-autoconfigure-model-chat-memory} 里的
 * {@code ChatMemoryAutoConfiguration}。少了一个 starter 就少了一个自动配置，
 * 于是这里手工把 {@link ChatMemory} 声明出来。
 *
 * <p>显式声明的额外好处：整条依赖链一目了然 ——
 * 默认的 {@code InMemoryChatMemoryRepository} 被我们的
 * {@code MybatisChatMemoryRepository} 顶替，全部由构造器注入完成，没有隐式行为。
 */
@Configuration
public class ChatMemoryPersistenceConfig {

    /** 窗口大小，与 Spring AI 默认值保持一致。 */
    private static final int MAX_MESSAGES = 20;

    /**
     * @param chatMemoryRepository 容器里唯一的 {@link ChatMemoryRepository} 实现
     *                             —— 即 {@code MybatisChatMemoryRepository}
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
