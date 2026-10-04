# Spring AI Agent Lab

> 一个**渐进式**的 Spring AI 2.0 学习项目：7 个阶段，从「问一句答一句」一路走到「工具调用 → 自定义 Advisor → 结构化输出 → 渐进式工具披露 → MCP 客户端」。
> 每个阶段都有独立可跑的 REST 接口，边跑边看日志，就能把 Spring AI 2.0 的核心机制吃透。

[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)]()
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-blue)]()

---

## 为什么是 2.0

Spring AI 2.0 是一次「地基重造 + 面向 Agent 重构」，不是简单多接了几个模型：

| 维度 | 变化 |
|---|---|
| 平台基线 | 绑定 **Spring Boot 4.0/4.1 + Spring Framework 7.0**，编译需 **Java 21** |
| JSON | **Jackson 3**（`tools.jackson.*`） |
| 空安全 | 全代码库 **JSpecify** 注解 |
| Options | 改为 builder 构建 + **不可变**；`copy()` / `fromOptions()` 移除，改用 `.mutate()` |
| 供应商 | OpenAI / Anthropic / Google GenAI 各自收敛为**单一官方 SDK** |
| **Agent 能力** | **工具循环从 ChatModel 内部黑盒上移到 Advisor 链**，成为可组合、可拦截、可循环的一等公民 |
| MCP | 内置 **MCP Java SDK 2.0.0**，Streamable HTTP 成为默认传输（取代 SSE） |

> 一句话：**以前你只能「调用」工具，现在你能在工具调用之上「构建」东西。**

---

## 环境要求

- **JDK 21+**（Spring AI 2.0 无法在 Spring Boot 3.x 上下文中运行）
- Maven 3.9+
- 一个 DeepSeek API Key（[platform.deepseek.com](https://platform.deepseek.com)）

---

## 快速开始

### 1. 配置 API Key（不要写进代码/配置文件）

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-你的key"

# Windows CMD
set DEEPSEEK_API_KEY=sk-你的key

# macOS / Linux
export DEEPSEEK_API_KEY=sk-你的key
```

### 2. 启动

```bash
mvn spring-boot:run
```

服务默认监听 `http://localhost:8080`。

> 如果启动报 `Port xxxx was already in use`，说明你的环境里有 `SERVER_PORT` / `SERVER__PORT` 之类的环境变量覆盖了配置，用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8090` 显式指定即可。

### 3. 跑测试（不需要 API Key、不联网）

```bash
mvn test
```

---

## 7 个阶段

| 阶段 | 接口前缀 | 学到什么 |
|---|---|---|
| 1 | `/stage1/**` | ChatClient 基础对话、占位符模板、SSE 流式输出 |
| 2 | `/stage2/**` | 多轮会话记忆：ChatMemory / ChatMemoryRepository / conversationId |
| 3 | `/stage3/**` | 工具调用：`@Tool`、`FunctionToolCallback`、ToolCallingAdvisor 自动驱动 |
| 4 | `/stage4/**` | 自定义 Advisor、order 语义、**在工具循环内部观测每一轮迭代** |
| 5 | `/stage5/**` | 结构化输出 `.entity()` + `StructuredOutputValidationAdvisor` 自纠错 |
| 6 | `/stage6/**` | 12 个工具场景 + **渐进式工具披露**（ToolSearchToolCallingAdvisor） |
| 7 | `/stage7/**` | MCP 客户端接入（默认关闭，按下方步骤开启） |

---

### Stage 1 — ChatClient 基础

```bash
curl "http://localhost:8080/stage1/chat?message=什么是虚拟线程"
curl "http://localhost:8080/stage1/chat/template?topic=Spring%20AI&level=高级"
curl -N "http://localhost:8080/stage1/stream?message=写一首关于编译器的五言绝句"
```

**要点**：2.0 明确把 `ChatClient` 定为唯一推荐入口，`ChatModel` 降级为底层构件。

---

### Stage 2 — 多轮会话记忆

```bash
curl "http://localhost:8080/stage2/chat?conversationId=u1:demo&message=我叫追光者"
curl "http://localhost:8080/stage2/chat?conversationId=u1:demo&message=我叫什么名字？"   # 应该答得出
curl "http://localhost:8080/stage2/history?conversationId=u1:demo"
curl "http://localhost:8080/stage2/history/size?conversationId=u1:demo"
curl -X DELETE "http://localhost:8080/stage2/history?conversationId=u1:demo"
```

**要点**：

- `ChatMemory`（决策层：留哪些、何时淘汰）与 `ChatMemoryRepository`（存储层）是**正交**的两个抽象。
- 默认实现：`MessageWindowChatMemory`（窗口 20 条）+ `InMemoryChatMemoryRepository`，Spring Boot 自动装配。
- `ChatMemory.CONVERSATION_ID` 是**必填**参数，没有默认值。多用户场景务必按「用户 ID + 会话 ID」派生，绝不能跨用户复用固定值。
- 2.0 新增 **turn-boundary 淘汰**：淘汰时总是整轮移除，不会把一轮对话从中间切断。

---

### Stage 3 — 工具调用

```bash
curl "http://localhost:8080/stage3/chat?message=现在几点？"
curl "http://localhost:8080/stage3/chat?message=今天往后 10 天是几号？"
curl "http://localhost:8080/stage3/chat?message=查一下上证指数和创业板指现在多少点，哪个涨得多？"
curl "http://localhost:8080/stage3/chat?message=2026-10-01 是节假日吗？"
```

**要点**：

- `ToolCallingAdvisor` 由 ChatClient **自动注册**，完整负责「模型请求工具 → 执行 → 回填 → 再推理」的往返，**不需要任何配置**。
- 三种工具定义方式最终都产出 `ToolCallback`，可以混用：
  | 方式 | 用法 | 本项目示例 |
  |---|---|---|
  | 声明式 `@Tool` | 在方法上加注解 | `TimeTools` / `MarketTools` |
  | `FunctionToolCallback` | 包装 lambda / 方法引用 | `Stage3ToolConfig#chineseHolidayChecker` |
  | `MethodToolCallback` | 程序化精细控制 | 见官方文档 |
- ⚠️ **2.0 破坏性变更**：1.x 的 `toolNames()` + 裸 `Function` Bean 按名解析机制（`SpringBeanToolCallbackResolver`）**已移除**，工具必须注册为显式 `ToolCallback` Bean，或随请求下发。
- 想看到完整的 Prompt 与模型响应？本项目已把 `SimpleLoggerAdvisor` 的日志级别设为 DEBUG。

---

### Stage 4 — 自定义 Advisor 与工具循环观测

```bash
curl "http://localhost:8080/stage4/chat?message=先告诉我今天日期，再查上证指数和深证成指的行情，最后比较涨跌幅"
```

**要点** —— 这是 2.0 最值得反复体会的一段。**order 越小越靠外**：

```
请求 ──▶ TimingAdvisor            (order = MIN+100)   ← 最外层：测「含工具循环」的总耗时
            └─▶ ToolCallingAdvisor (order = MIN+300)   ← 框架自动注册，负责工具循环
                  └─▶ ToolLoopObserverAdvisor (MIN+310) ← 循环内部：每轮迭代都被调用
                        └─▶ ChatModel
```

- 把 Advisor 放在 `ToolCallingAdvisor.DEFAULT_ORDER` **之后**（order 更大），就能观测到工具循环的**每一轮**动作。
- 这也解释了记忆 Advisor 为什么默认在循环**外面**（`DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = MIN+200`）：**大多数 `ChatMemoryRepository` 实现不支持 tool 消息类型**，所以默认只落库最终的 user/assistant 一轮交换。要「循环内记忆」得显式调 `disableInternalConversationHistory()` 并把 order 调到循环内部。
- 只做观测、不改写请求时，实现 `BaseAdvisor` 最省事（`before()` / `after()`）。

---

### Stage 5 — 结构化输出与自纠错

```bash
curl "http://localhost:8080/stage5/analyze?indexCode=000001"
curl "http://localhost:8080/stage5/analyze/validated?indexCode=000001"
```

**要点**：

- `.call().entity(IndexAnalysis.class)` 直接拿到 Java 对象。Spring AI 会在发请求前把目标类型的 **JSON Schema + 格式指令**拼进 Prompt，收到回复后用 `BeanOutputConverter` 反序列化。
- 即便开了原生结构化输出，模型仍可能吐出不合法 JSON（多包一层 ` ```json ` 围栏、字段类型错……）。`StructuredOutputValidationAdvisor` 会在**校验失败时自动重试纠正**，`maxRepeatAttempts` 控制重试上限。
- 想更稳，还可以用 `@JsonPropertyOrder` 固定 Schema 里的字段顺序。

---

### Stage 6 — 多工具与渐进式工具披露

```bash
curl "http://localhost:8080/stage6/chat?message=客户 C1001 还有多少积分？"
curl "http://localhost:8080/stage6/chat?message=客户 C1001 的订单 SO202610010001 到哪了？顺便看看他有哪些优惠券"
curl "http://localhost:8080/stage6/chat?message=帮 C1001 查一下最近的订单、物流、发票状态和账户余额"
```

**要点**：

- 本项目注册了 12 个 CRM 工具。工具一多，把**全部工具定义**塞进每次请求会带来 **token 成本暴涨** + **模型选错工具**两个问题。
- `ToolSearchToolCallingAdvisor` 提供**渐进式工具披露**：先对全量工具建一次索引，每轮只把最相关的少数几个发给模型。官方实测可省 **34% ~ 64%** token。
- 索引类型三选一（`spring.ai.chat.client.tool-search-advisor.tool-index-type`）：
  | 类型 | 额外依赖 |
  |---|---|
  | `regex`（默认） | 无 |
  | `lucene` | `org.apache.lucene:lucene-core` |
  | `vector` | 需要 `VectorStore` Bean |
- 对比实验：把 `tool-search-advisor.enabled` 改成 `false` 重启，再问同样的问题，观察下发到模型的工具数量差异。
- ⚠️ 注意一个容易踩的坑：这个开关**只作用于 Spring Boot 自动装配的那个 `ChatClient.Builder`**。Stage 1–5 用 `ChatClient.builder(chatModel)` 手工构建，因此完全不受影响 —— 这也顺带说明了「自动装配默认值」与「手工构建」两条路线的边界。

---

### Stage 7 — MCP 客户端（进阶，默认关闭）

MCP（Model Context Protocol）正在成为 AI 与外部系统对接的通用协议。Spring 团队自己维护官方 MCP Java SDK，所以 Spring AI 内置 **MCP Java SDK 2.0.0**，对齐 **2025-11-25 规范**。

最大好处：**你不需要为 MCP 工具写任何适配代码**。

```
MCP Server (stdio / streamable-http)
     ↓ Spring AI 自动发现并包装
SyncMcpToolCallbackProvider : ToolCallbackProvider
     ↓ .defaultToolCallbacks(provider)
ChatClient → ToolCallingAdvisor 统一驱动
```

**开启步骤**：

```bash
# 1. 复制配置模板
cp src/main/resources/application-mcp.yml.example src/main/resources/application-mcp.yml

# 2. 编辑 application-mcp.yml，把 stdio 那段里的目录换成你自己的（默认已配好 npx + 国内镜像）

# 3. 用 mcp profile 启动
mvn spring-boot:run -Dspring-boot.run.profiles=mcp
```

**验证**：

```bash
curl "http://localhost:8080/stage7/tools"                                     # 列出 MCP Server 暴露的工具
curl "http://localhost:8080/stage7/chat?message=列出 D:/workspace 下的文件"    # 用自然语言调用 MCP 工具
```

**要点**：

- **Streamable HTTP 已成为默认传输方式**，被弃用的 SSE 传输不再推荐；STDIO 保留用于本地进程集成。
- 注解式服务端模型（`@McpTool` / `@McpResource` / `@McpPrompt`）在 2.0 已从社区并入主仓库，一个方法注解就能把 Spring Service 暴露成 MCP 工具。
- 国内网络注意：`registry.npmjs.org` 可能不通，模板里已加 `npm_config_registry=https://registry.npmmirror.com`。

---

## 项目结构

```
spring-ai-agent-lab/
├── pom.xml                                  # Spring Boot 4.1.1 + Spring AI BOM 2.0.1
├── src/main/java/com/agentlab/
│   ├── AgentLabApplication.java
│   ├── stage1/BasicChatController.java       # ChatClient 基础
│   ├── stage2/MemoryChatController.java      # 会话记忆
│   ├── stage3/                               # 工具调用
│   │   ├── ToolChatController.java
│   │   ├── config/Stage3ToolConfig.java
│   │   └── tools/{TimeTools,MarketTools}.java
│   ├── stage4/                               # 自定义 Advisor
│   │   ├── AdvisorChatController.java
│   │   └── advisor/{TimingAdvisor,ToolLoopObserverAdvisor}.java
│   ├── stage5/                               # 结构化输出
│   │   ├── StructuredOutputController.java
│   │   └── dto/IndexAnalysis.java
│   ├── stage6/                               # 渐进式工具披露
│   │   ├── ToolSearchChatController.java
│   │   ├── config/Stage6ToolConfig.java
│   │   └── tools/CrmTools.java
│   └── stage7/McpClientController.java       # MCP 客户端（条件装配）
├── src/main/resources/
│   ├── application.yml
│   └── application-mcp.yml.example
└── src/test/java/com/agentlab/
    ├── AgentLabApplicationTests.java         # 上下文装配冒烟测试
    └── stage3/ToolsTest.java                 # 工具单测
```

---

## 从 1.1.x 迁移时最容易踩的 8 个坑

| 变更 | 处理方式 |
|---|---|
| `internalToolExecutionEnabled` 移除 | 删掉所有调用；用 `ToolCallingAdvisor`（自动）或 `AdvisorParams.toolCallingAdvisorAutoRegister(false)` 手动驱动 |
| `ToolCallAdvisor` 改名 | → `ToolCallingAdvisor` |
| `.functions()` / `toolNames()` 移除 | 改用显式 `ToolCallback` Bean 或 `FunctionToolCallback.builder(...)` |
| `ChatOptions#copy()` / `fromOptions()` | 改用 `.mutate()` |
| `ChatOptions` 不可变 | 必须走 builder，setter 已废弃 |
| 模块改名 | `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor` |
| MCP 包名 / 坐标 | `...ai.mcp.spring.annotations` → `...ai.mcp.annotation`；`io.modelcontextprotocol.sdk:mcp-spring-*` → `org.springframework.ai:mcp-spring-*` |
| `disableMemory()` | → `disableInternalConversationHistory()` |

官方还提供 OpenRewrite recipe 可自动完成大部分迁移：

```bash
mvn org.openrewrite.maven:org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.springframework.ai.migration.MigrateToSpringAI200M3
```

---

## 上传到你的 GitHub

```bash
# 0. 先确认 git 身份（提交记录的作者信息）
git config --local user.name  "你的GitHub用户名"
git config --local user.email "你的GitHub邮箱"

# 1. 在 GitHub 网页上新建一个空仓库，例如 spring-ai-agent-lab
#    注意：不要勾选 "Add a README / .gitignore / license"，保持空仓库

# 2. 关联远程仓库（把 <你的用户名> 换成实际值）
git remote add origin https://github.com/<你的用户名>/spring-ai-agent-lab.git

# 3. 推送
git branch -M main
git push -u origin main
```

如果本地提交已经生成、但想改作者信息：

```bash
git rebase --root --exec 'git commit --amend --no-edit --reset-author'
```

---

## 参考

- [Spring AI 2.0.0 GA 发布公告](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now)
- [Spring AI 官方参考文档](https://docs.spring.io/spring-ai/reference/index.html)
- [升级说明（Upgrade Notes）](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- [Tool Calling in Spring AI 2.0：可组合的 Agent 架构](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling)
- [官方示例仓库 spring-ai-examples](https://github.com/spring-projects/spring-ai-examples)

---

## License

MIT
