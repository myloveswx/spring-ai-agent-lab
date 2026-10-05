package com.agentlab;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI 2.0 渐进式 Agent Lab 启动类。
 *
 * <p>8 个阶段对应 8 个 REST 包（stage1 ~ stage8），每个阶段可独立调用：
 * <ul>
 *   <li>Stage 1 {@code /stage1/**} —— ChatClient 基础对话与流式输出</li>
 *   <li>Stage 2 {@code /stage2/**} —— 多轮会话记忆（ChatMemory + Advisor）</li>
 *   <li>Stage 3 {@code /stage3/**} —— 工具调用（ToolCallingAdvisor 自动驱动）</li>
 *   <li>Stage 4 {@code /stage4/**} —— 自定义 Advisor 与工具循环观测</li>
 *   <li>Stage 5 {@code /stage5/**} —— 结构化输出与自纠错</li>
 *   <li>Stage 6 {@code /stage6/**} —— 多工具场景与渐进式工具披露</li>
 *   <li>Stage 7 {@code /stage7/**} —— MCP 客户端接入（进阶，默认关闭）</li>
 *   <li>Stage 8 {@code /stage8/**} —— RAG 知识库（本地 ONNX 嵌入 + 向量检索）</li>
 *   Swagger UI	http://localhost:8080/swagger-ui/index.html
 * </ul>
 *
 * <p><b>Stage 8 的前置条件</b>：本地需存在 ONNX 嵌入模型（默认在
 * {@code D:/workspace/.toolchain/models/bge-small-zh-v1.5}）。
 * 之所以要有这个前置：DeepSeek 不提供 embedding 接口，RAG 必须另配一个嵌入模型，
 * 而本机直连 HuggingFace 太慢（实测 ~51KB/s），所以改成读本地文件。
 * 模型没准备好时把 {@code agentlab.rag.enabled} 设为 {@code false}，
 * 这一整块装配会退出，Stage 1~7 不受任何影响。
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
