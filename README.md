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

### 4. 打开接口文档（Swagger UI）

启动后访问：

| 入口 | 地址 | 说明 |
|---|---|---|
| Swagger UI | http://localhost:8080/swagger-ui/index.html | 可视化界面，可直接「Try it out」 |
| OpenAPI JSON | http://localhost:8080/v3/api-docs | 机器可读，喂给 Postman / Apifox / 代码生成器 |

> 换了端口就把 `8080` 换掉；`OpenApiConfig` 里声明的 `servers` 只是给「Try it out」用的默认目标地址，不影响文档本身的生成。

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

## 接口文档（Swagger / OpenAPI）

24 个接口分布在 7 个阶段里，靠 curl 手敲很容易记混。项目引入了 **springdoc-openapi 3.1.1** 自动生成 OpenAPI 3.1 文档。

### 为什么是 3.x，不是 2.x

springdoc 的版本线跟着 Spring Boot 的大版本走：

| springdoc | 对应 Spring Boot |
|---|---|
| 2.8.x | 3.x |
| **3.1.1** | **4.x**（官方 pom 的 parent 就是 `spring-boot-starter-parent:4.1.0`） |

本项目是 Boot 4.1.1，所以必须用 3.x。用 2.8.x 会在自动配置阶段就失败（Spring Framework 7 下条件注解与类签名不匹配），不是「跑起来功能不对」那么温柔。

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

`webmvc` vs `webflux` 别选错：本项目是 Spring MVC（Servlet），要 `-webmvc-ui`。

### 两个入口

```bash
# 可视化界面（可直接发请求）
curl "http://localhost:8080/swagger-ui/index.html"

# 机器可读的 OpenAPI JSON
curl "http://localhost:8080/v3/api-docs"
```

### 注解约定

| 注解 | 打在哪 | 作用 |
|---|---|---|
| `@Tag` | Controller 类 | 接口分组（对应 UI 上的折叠块） |
| `@Operation` | 方法 | 接口的 summary / description |
| `@Parameter` | 方法参数 | 参数含义、示例值（`defaultValue` 会自动带出） |
| `@Schema` | DTO 类 / 字段 | 字段含义、示例、取值约束 |

**分组名集中定义在 `OpenApiConfig` 里，控制器引用常量**：

```java
// OpenApiConfig
public static final String TAG_STAGE3 = "Stage 3 · 工具调用";

// 控制器
@Tag(name = OpenApiConfig.TAG_STAGE3)
```

这样做的原因是 springdoc 按 **Tag 名字符串**做匹配：只要控制器上写的名字和配置里声明的差一个空格，那个分组就会掉到列表末尾、描述丢失，而且**不报错**，纯靠肉眼发现。用常量能从编译期就杜绝这种漂移。

> 分组名称前缀是 `Stage 1 … Stage 7`。`application.yml` 里 `tags-sorter: alpha` 正好把它们排成 1→7（中文分组排在其后）。一旦出现两位数（比如 Stage 10），alpha 会把它排到 Stage 2 前面 —— 那时把 `tags-sorter` 去掉、改用配置类里 `tags` 的声明顺序。

### 四个已知边界（读文档时别被误导）

1. **SSE 流式接口在 Swagger UI 里不流式**。`/stage1/stream` 是 `text/event-stream`，UI 会等流结束才一次性显示。看逐字效果请用 `curl -N` 或浏览器 `EventSource`。
2. **对话接口需要真实 Key**。文档本身不依赖 Key（springdoc 只做静态扫描），但应用启动依赖它：`DEEPSEEK_API_KEY` 未设置时 DeepSeek 自动配置的 `Assert.hasText` 会直接让应用起不来。Key 设了但无效时，「Try it out」返回 401 —— 那是模型调用失败，与文档无关。
3. **Stage 7 默认不出现在文档里**。`McpClientController` 上有 `@ConditionalOnProperty`，`spring.ai.mcp.client.enabled=false` 时 Bean 根本不创建，springdoc 自然也扫不到。要它出现，需改配置并重启。
4. **`@Schema` 不会改变发给模型的 JSON Schema**。Stage 5 结构化输出时约束模型的是 `BeanOutputConverter` 依 record 结构推导出的 Schema + Prompt 文字，DTO 上的 `@Schema` 只影响 Swagger UI 的展示。

> 顺带一个踩过一次的坑：Swagger UI 会帮你把中文参数 URL 编码，没问题；但用 curl 手敲时如果直接在 URL 里写中文（`?text=测试中文`），Tomcat 10 会因为请求行含非 ASCII 字节直接返回 **400**，与业务代码无关。命令行请用 `--data-urlencode` 或 `%E6%B5%8B…` 形式。

### 验证测试

`src/test/java/com/agentlab/OpenApiDocsTest.java` 会真实发 HTTP 请求校验：

- `/v3/api-docs` 返回 200 且含项目标题、7 个分组、14 条抽查路径
- `/stage7/**` **不**出现（反向验证条件装配）
- `/swagger-ui/index.html` 可访问（验证 webjar 静态资源完整）

之所以必须发真实请求：**springdoc 的 OpenAPI 模型是懒生成的**，只有真正有人来取文档那一刻才去扫描 Controller。注解写错、Tag 名对不上这类问题，在「应用能启动」阶段完全看不出来。

---

## 进阶：把对话记忆落库到 MySQL（持久层 = MyBatis-Plus）

默认的 `ChatMemoryRepository` 是 `InMemoryChatMemoryRepository`，进程一重启记忆就没了。
本项目已改为 **MyBatis-Plus 持久化**，落到本机 MySQL 8.0.28。

### 为什么换持久层可以不动业务代码

Spring AI 把「记忆」拆成两个正交抽象：

| 抽象 | 职责 |
|---|---|
| `ChatMemory` | **决策层** —— 保留哪些消息、窗口多大（默认 `MessageWindowChatMemory`，窗口 20 条） |
| `ChatMemoryRepository` | **存储层** —— 只管存取，不关心业务 |

换持久化框架只需要换后者。`MemoryChatController` 始终只依赖 `ChatMemory` 接口，
**一行都没改** —— 这正是这个抽象存在的意义。

### 改了什么

| 层 | 内容 |
|---|---|
| 依赖 | 引入 `mybatis-plus-spring-boot4-starter:3.5.17`；**移除** `spring-ai-starter-model-chat-memory-repository-jdbc` |
| 实体 | `persistence/entity/ChatMemoryEntity.java` —— `@TableName("SPRING_AI_CHAT_MEMORY")` |
| Mapper | `persistence/mapper/ChatMemoryMapper.java` —— `extends BaseMapper`，零 XML |
| 仓储 | `persistence/repository/MybatisChatMemoryRepository.java` —— 实现 `ChatMemoryRepository` |
| 装配 | `persistence/config/ChatMemoryPersistenceConfig.java` —— 显式声明 `ChatMemory` Bean |
| 业务 | `stage2/MemoryChatController.java` —— **零改动** |

> 移除官方 starter 的连带影响：它原本顺带带来 `spring-ai-autoconfigure-model-chat-memory`
> （提供 `ChatMemory` 自动装配）。少一个 starter 就少一个自动配置，所以我们在
> `ChatMemoryPersistenceConfig` 里手工声明 `ChatMemory`。显式的装配链比隐式的更容易学。

### 必须对齐官方实现的三处语义

这三条不对齐就会出问题，且症状都不直观：

1. **`saveAll` 是「全量覆盖」，不是「追加」**
   `MessageWindowChatMemory` 每次 `add` 后，会把**整个窗口**的消息交给 `saveAll`。
   若做成增量插入，每轮对话都会把旧消息重复写一遍，表会指数级膨胀。
   正确做法：**同一事务内**先删该会话全部行，再批量插入。

2. **tool 消息不落库**
   `ToolResponseMessage` 和带 `toolCalls` 的 `AssistantMessage` 无法用「单个 content 列」表达。
   官方实现直接过滤 + 打告警，这里保持一致 —— 否则要么插入失败（content 为 NULL），
   要么丢失工具调用结构。

3. **`type` 列必须写 `MessageType#name()`，不能写 `getValue()`**
   这是最阴的一个坑：`MessageType.USER.name()` 是 **`USER`**，
   而 `MessageType.USER.getValue()` 是 **`user`**（小写）。
   数据库 ENUM 定义是 `('USER','ASSISTANT','SYSTEM','TOOL')`，写小写会直接插入失败。

### 三个 MyBatis-Plus 适配坑

1. **官方表结构没有主键**（只有两个组合索引）→ 实体里**不能**声明 `@TableId`，
   也就用不了 `selectById / updateById / deleteById` 这一族方法。
   好在 `ChatMemoryRepository` 的四个方法本来就以 `conversation_id` 为条件，
   用 `Wrapper` 完全够用。这里刻意**不改表结构**，好处是随时能切回官方实现。
2. **`type` 与 `timestamp` 是 SQL 关键字** → 必须用 `` @TableField("`type`") `` 反引号包住，
   否则拼出来的 SQL 在 MySQL 上语法报错。
3. **Spring Boot 4 必须用 `mybatis-plus-spring-boot4-starter`**，不是 `spring-boot3-starter`；
   版本 ≥ 3.5.13 才有这个 artifact（它依赖 `mybatis-spring:4.0.0`，适配 Spring Framework 7）。

### 表结构

`agent_lab.SPRING_AI_CHAT_MEMORY`，沿用 Spring AI 官方定义
（见 `../mysql-setup/schema/agent-memory.sql`），**未做任何 DDL 变更**。

### 验证落库（不需要 API Key、不联网）

```bash
# 0. 先启动 MySQL：D:\workspace\mysql-setup\2-start-mysql.cmd

# 1. 不经过大模型，直接往记忆里写两条（POST 表单体；中文需 URL 编码）
curl -X POST -d "conversationId=verify:mp&text=%E4%BD%A0%E5%A5%BD" \
     "http://localhost:8080/stage2/db/seed"

# 2. 直查数据库 —— 查得到就证明底层确实是 MySQL 存储
curl "http://localhost:8080/stage2/db/rows?conversationId=verify:mp"
# [{"conversationId":"verify:mp","content":"你好","type":"USER","sequenceId":0},
#  {"conversationId":"verify:mp","content":"[seed] 已收到：你好","type":"ASSISTANT","sequenceId":1}]

# 3. 统计 / 会话概览（stats 会回显当前持久层实现）
curl "http://localhost:8080/stage2/db/stats"
# {"persistence":"MyBatis-Plus (MybatisChatMemoryRepository)","charset":"utf8mb4", ...}
curl "http://localhost:8080/stage2/db/conversations"
```

> **为什么 seed 两次仍是 4 条？** 这正是上面「全量覆盖」语义的可见证据：
> 第 2 次 seed 时窗口是 `[U1,A1,U2,A2]`，`saveAll` 先删掉旧的 2 条再写入 4 条。
> 若实现成追加，这里会是 6 条甚至更多。
>
> 想看 MyBatis 实际执行的 SQL：`application.yml` 里 `log-impl` 已设为 `StdOutImpl`，
> 控制台会打印 `Preparing:` 与 `Parameters:` 两行。上面那次写入会看到：
> ```sql
> DELETE FROM SPRING_AI_CHAT_MEMORY WHERE (conversation_id = ?)
> INSERT INTO SPRING_AI_CHAT_MEMORY ( conversation_id, content, `type`, `timestamp`, sequence_id ) VALUES ( ?, ?, ?, ?, ? )
> ```

> 为什么不用 `ChatMemory#get()` 验证：那条路径会经过 `MessageWindowChatMemory`
> 的窗口裁剪（默认只保留最近 20 条），你看到的是「记忆层认为该保留的」，
> 而不是「数据库里实际存的」。确认落库效果必须绕开记忆层直查表。

### 数据库信息

| 项 | 值 |
|---|---|
| 地址 | `127.0.0.1:3306` |
| 账号 | `root` / `123456` |
| 库 | `agent_lab` |
| 表 | `SPRING_AI_CHAT_MEMORY` |
| 字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` |

数据库的安装、启停与卸载步骤见 `D:\workspace\mysql-setup\README.md`。

---

## 项目结构

```
spring-ai-agent-lab/
├── pom.xml                                  # Spring Boot 4.1.1 + Spring AI BOM 2.0.1 + MyBatis-Plus 3.5.17 + springdoc 3.1.1
├── src/main/java/com/agentlab/
│   ├── AgentLabApplication.java
│   ├── stage1/BasicChatController.java       # ChatClient 基础
│   ├── stage2/                               # 会话记忆
│   │   ├── MemoryChatController.java         # ChatMemory + MessageChatMemoryAdvisor（换持久层时零改动）
│   │   └── MemoryDbInspector.java            # 直查 MySQL，验证落库（走 Mapper）
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
│   ├── stage7/McpClientController.java       # MCP 客户端（条件装配）
│   ├── persistence/                          # 持久层（MyBatis-Plus）
│   │   ├── entity/ChatMemoryEntity.java      # @TableName 映射（无主键、关键字列名转义）
│   │   ├── mapper/ChatMemoryMapper.java      # extends BaseMapper，零 XML
│   │   ├── repository/MybatisChatMemoryRepository.java  # 实现 ChatMemoryRepository
│   │   └── config/ChatMemoryPersistenceConfig.java      # 显式装配 ChatMemory
│   ├── config/
│   │   ├── WebEncodingConfig.java            # 全局 UTF-8（中文乱码根治）
│   │   └── OpenApiConfig.java                # Swagger 元数据 + 分组常量（@Tag 引用它）
│   └── diagnostics/EncodingDiagnosticController.java  # 编码自检端点
├── src/main/resources/
│   ├── application.yml
│   └── application-mcp.yml.example
└── src/test/java/com/agentlab/
    ├── AgentLabApplicationTests.java         # 上下文装配冒烟测试
    ├── OpenApiDocsTest.java                  # 真实 HTTP 校验 /v3/api-docs 与 Swagger UI
    └── stage3/ToolsTest.java                 # 工具单测
```

---

## 中文乱码排查手册

接口返回中文乱码，绝大多数是下面三层里的一层出了问题，**按顺序查可一次定位**。

### 三层编码（本项目均已显式声明）

| 层 | 生效位置 | 本项目做法 |
|---|---|---|
| ① 请求 / 响应 | Servlet 容器 | `server.servlet.encoding.force: true`（`application.yml`） |
| ② 消息转换器 | Spring MVC | `StringHttpMessageConverter.setDefaultCharset(UTF_8)`（`WebEncodingConfig`） |
| ③ 编译期 | javac | `project.build.sourceEncoding` + `maven-compiler-plugin/encoding`（`pom.xml`） |

### 根因：StringHttpMessageConverter 默认是 ISO-8859-1

Spring MVC 的 `StringHttpMessageConverter` 默认字符集是 **ISO-8859-1**（单字节），不是 UTF-8。
只要 Controller 直接 `return String`，响应头就会写成 `text/plain;charset=ISO-8859-1`，中文必然乱码。

> 为什么返回 JSON 的接口往往没事？因为走的是 Jackson，它默认就是 UTF-8。
> 所以乱码通常只在「返回纯文本 `String`」的接口上暴露 —— 这也是它容易被忽略的原因。

修复要点是**改默认字符集，而不是换掉转换器列表**：

```java
@Configuration
public class WebEncodingConfig implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(StringHttpMessageConverter.class::isInstance)
                .map(StringHttpMessageConverter.class::cast)
                .forEach(c -> c.setDefaultCharset(StandardCharsets.UTF_8));
    }
}
```

⚠️ 用 `extendMessageConverters`（在默认列表上增删改），**不要**用 `configureMessageConverters`（整体替换），
后者会把 Jackson 等默认转换器一并清空 —— 这是高频踩坑点。

### 一键自检（不需要 API Key、不联网）

```bash
# ① 看响应头有没有 charset=UTF-8
curl -s -D - "http://localhost:8080/diagnostics/encoding/text" -o /dev/null | grep -i content-type

# ② 看运行时的 JVM 字符集
curl -s "http://localhost:8080/diagnostics/encoding/json"
```

正常输出：

```
Content-Type: text/plain;charset=UTF-8
{"received":"你好，世界","expectedCharset":"UTF-8","jvmFileEncoding":"UTF-8","jvmDefaultCharset":"UTF-8"}
```

### 如果服务端正常、终端里仍显示乱码

那问题在**客户端**，与服务端无关：

| 环境 | 处理方式 |
|---|---|
| Windows CMD | 先 `chcp 65001` 切到 UTF-8 代码页，再执行 curl |
| Windows PowerShell | `[Console]::OutputEncoding=[Text.Encoding]::UTF8`；且要用 `curl.exe`，别用 `curl`（那是 `Invoke-WebRequest` 的别名，编码行为不同） |
| Git Bash | 默认 UTF-8，一般无需处理 |
| IDEA 控制台 | Settings → Editor → File Encodings 全设 UTF-8；Run Configuration 加 VM 参数 `-Dfile.encoding=UTF-8` |

### 判断口诀

- 响应头带 `charset=UTF-8`、字节也是 UTF-8，**但屏幕仍乱** → 客户端显示问题，改终端
- 响应头是 `ISO-8859-1` → 第 ①② 层没配好
- 中文变成 `?` 或只有部分字乱 → 编译期编码问题（第 ③ 层），检查 pom 的 `sourceEncoding`

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

> 本仓库的远程已经配好：`origin = git@github.com:myloveswx/spring-ai-agent-lab.git`，分支 `main` 已绑定上游。
> 换到别的账号请先 `git remote set-url origin <新地址>`。
>
> 仓库地址：https://github.com/myloveswx/spring-ai-agent-lab

### 为什么用 SSH 而不是 HTTPS

HTTPS 每次都要处理凭据：GitHub 早已取消密码认证，要么手动生成 Personal Access Token、
要么依赖凭据管理器弹窗。**SSH 配一次，之后永久免密，也永远不会把令牌写进 `.git/config` 里。**

### 一次性配置（每台机器只需做一次）

```bash
# 1. 生成密钥对（ed25519，GitHub 当前推荐的算法）
#    -f 指定文件名，-N "" 表示不设密码短语（设了的话每次 push 要输，可用 ssh-agent 缓存）
ssh-keygen -t ed25519 -C "你的GitHub邮箱" -f ~/.ssh/id_ed25519 -N ""

# 2. 把公钥复制到剪贴板（Windows）
cat ~/.ssh/id_ed25519.pub | clip

# 3. 粘贴到 GitHub：
#    https://github.com/settings/ssh/new
#    Title 随便写（如 "Windows-工作机"），Key 类型保持 Authentication Key，粘贴 → Add SSH key

# 4. 验证（看到 "Hi <用户名>! You've successfully authenticated" 就成功了）
ssh -T git@github.com
```

> `Permission denied (publickey)` = 连接正常但密钥还没登记到 GitHub（第 3 步没做完）；
> `Connection timed out` = 网络/防火墙挡了 22 端口，改用 `ssh.github.com:443`，见下方「22 端口被挡」。

### 建仓库并推送

```bash
# 1. 在 GitHub 网页新建**空**仓库：https://github.com/new
#    名字填 spring-ai-agent-lab
#    ⚠️ 不要勾选 "Add a README / .gitignore / license"，否则远程会有初始提交，push 会被拒

# 2. 关联远程（只做一次）
git remote add origin git@github.com:<你的用户名>/spring-ai-agent-lab.git

# 3. 确认分支名并推送
git branch -M main
git push -u origin main
```

`-u` 的作用是把本地 `main` 和 `origin/main` 绑定，**之后直接 `git push` / `git pull` 就行，不用再带参数**。

### 常用后续操作

```bash
git push                      # 推新提交（已绑定上游后）
git log --oneline --graph     # 看提交图
git remote -v                 # 看远程地址（fetch/push 两行都应是 git@github.com:...）
```

### 22 端口被挡怎么办

公司网络常封 22 端口。改用 GitHub 的 HTTPS 备用通道（走 443，协议仍是 SSH）：

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  HostName ssh.github.com
  Port 443
  User git
EOF

ssh -T git@github.com   # 再验一次
```

### 想改本地提交的作者信息

```bash
# 改全局默认身份
git config --global user.name  "你的GitHub用户名"
git config --global user.email "你的GitHub邮箱"

# 重写全部历史提交的作者（谨慎：会改写 commit hash，已推送的仓库不要用）
git rebase --root --exec 'git commit --amend --no-edit --reset-author'
```

> 邮箱建议用 GitHub 的 `<用户名>@users.noreply.github.com`：既能正确归属到你的账号，
> 又不会把你的真实邮箱暴露在公开提交记录里。

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
