package com.agentlab;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI 2.0 渐进式 Agent Lab 启动类。
 *
 * <p>7 个阶段对应 7 个 REST 包（stage1 ~ stage7），每个阶段可独立调用：
 * <ul>
 *   <li>Stage 1 {@code /stage1/**} —— ChatClient 基础对话与流式输出</li>
 *   <li>Stage 2 {@code /stage2/**} —— 多轮会话记忆（ChatMemory + Advisor）</li>
 *   <li>Stage 3 {@code /stage3/**} —— 工具调用（ToolCallingAdvisor 自动驱动）</li>
 *   <li>Stage 4 {@code /stage4/**} —— 自定义 Advisor 与工具循环观测</li>
 *   <li>Stage 5 {@code /stage5/**} —— 结构化输出与自纠错</li>
 *   <li>Stage 6 {@code /stage6/**} —— 多工具场景与渐进式工具披露</li>
 *   <li>Stage 7 {@code /stage7/**} —— MCP 客户端接入（进阶）</li>
 * </ul>
 *
 * <p>持久层：MyBatis-Plus（{@code com.agentlab.persistence}）。
 * {@code @MapperScan} 指定 Mapper 接口所在包，省去在每个接口上写 {@code @Mapper}。
 */
@SpringBootApplication
@MapperScan("com.agentlab.persistence.mapper")
public class AgentLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentLabApplication.class, args);
    }
}
