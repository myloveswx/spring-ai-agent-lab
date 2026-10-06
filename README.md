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
- **Stage 8 额外需要**：本地 ONNX 嵌入模型（约 95MB）。DeepSeek 不提供 embedding 接口，
  RAG 必须另配一个嵌入模型，本项目用本地离线模型 `BAAI/bge-small-zh-v1.5`。

  ```bash
  # 走国内镜像，实测 ~1.7MB/s，约 1 分钟（直连 HuggingFace 只有 ~51KB/s，要等半小时）
  mkdir -p D:/workspace/.toolchain/models/bge-small-zh-v1.5 && cd $_
  curl -L -o model.onnx     https://hf-mirror.com/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
  curl -L -o tokenizer.json https://hf-mirror.com/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer.json
  ```

  想放别处就设环境变量 `AGENTLAB_RAG_MODEL_DIR`，或改 `application.yml` 里的
  `agentlab.rag.model-dir`。**暂时不想搞模型**的话，把 `agentlab.rag.enabled` 设为 `false`，
  Stage 8 的装配与接口会整块消失，Stage 1~7 照常可用。

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

启动日志里会有一行 `Stage 8 · 嵌入预热完成：维度 = 512，耗时 N ms`。
那 N 如果是 5 位数别慌 —— 首次调用 ONNX 要解压原生库并触发杀软首扫，之后稳态只要 20~90ms。
预热就是把这笔一次性开销从「用户第一次提问」挪到「启动期」。

### 3. 跑测试（不需要 API Key、不联网）

```bash
mvn test
```

### 4. 打开自带前端

启动后访问：

| 入口 | 地址 | 说明 |
|---|---|---|
| **智能助手**（主入口） | http://localhost:8080/ | 选一个场景直接对话，不同场景背后是不同的接口（见下节） |
| 接口实验室 | http://localhost:8080/lab.html | 逐个接口调试：每个端点一张卡片，适合对照参数与返回 |
| Swagger UI | http://localhost:8080/swagger-ui/index.html | 可视化界面，可直接「Try it out」 |
| OpenAPI JSON | http://localhost:8080/v3/api-docs | 机器可读，喂给 Postman / Apifox / 代码生成器 |

> 换了端口就把 `8080` 换掉；`OpenApiConfig` 里声明的 `servers` 只是给「Try it out」用的默认目标地址，不影响文档本身的生成。

### 5. 智能助手：选场景 = 换接口

根路径是一个**对话式助手**：左侧选一个阶段（场景），主区就是聊天框，发出去的消息会打到该场景绑定的接口上。

**不需要 npm install、不需要 build、不依赖任何外网 CDN** —— 整套前端就是 `src/main/resources/static/` 下的静态文件，由 Spring Boot 直接托管。

```text
src/main/resources/static/
├── index.html              智能助手（对话式）
├── lab.html                接口实验室（逐个端点调试）
└── assets/
    ├── chat.css            助手的样式
    ├── chat-core.js        请求封装 / SSE 手工解析 / Markdown 渲染
    ├── chat-stages.js      ★ 场景声明：每个 stage 绑定哪个接口、有哪些参数、怎么渲染  ← 想加场景只改这里
    ├── chat-app.js         主驱动：导航 + 配置条 + 对话流
    ├── app.css / core.js / registry.js / renderers.js / app.js   接口实验室的样式与逻辑
```

场景与接口的对应关系：

| 场景 | 绑定接口 | 说明 |
|---|---|---|
| 1 基础对话 | `GET /stage1/chat`、`/stage1/chat/template`、`/stage1/stream` | 可切「流式输出」，SSE 真逐字渲染 |
| 2 会话记忆 | `GET /stage2/chat` + `history` / `history/size` | 会话 ID 可改；一键看记忆条数、清空记忆 |
| 3 工具调用 | `GET /stage3/chat` | 时间 / 日期推算 / 指数行情三个工具 |
| 4 Advisor 链 | `GET /stage4/chat` | 价值在控制台日志（Timing 外层、ToolLoop 内层） |
| 5 结构化输出 | `GET /stage5/analyze`、`/analyze/validated` | 输入区是「指数选择器」；可切自纠错校验 |
| 6 多工具披露 | `GET /stage6/chat` | 13 个 CRM 工具，按会话缓存索引 |
| 6L 检索策略实验 | `GET /stage6/lab/{search,compare,catalog,chat}` | **纯检索不调模型**：单策略 / 四策略对比 / 工具画像 |
| 7 MCP 工具 | `GET /stage7/chat`、`/stage7/tools` | 默认未启用 → 404 属预期（用 `mcp` profile 启动） |
| 8 RAG 问答 | `GET /stage8/kb/{kbId}/chat`、`/chat/compare` | 知识库选择器 + 对照实验；带「载入示例语料 / 库现状 / 列出文档 / 纯检索 / 新建库」动作 |
| D 编码自检 | `GET /diagnostics/encoding/{json,text}` | 不花 token 的链路自检 |

为什么值得用它而不是只用 Swagger：

1. **中文参数** —— 参数由表单控件收集，浏览器统一按 UTF-8 发出，不会撞上「Git Bash 把中文转成 GBK」那个坑。
2. **每条回复都带「这一屏在学什么」** —— 场景引导卡解释这一阶段的机制，回复下方标注实际打的接口、耗时、HTTP 状态；检索命中带相似度条、对照实验并排显示两版回答、结构化输出按字段渲染（趋势按 A 股口径涨红跌绿）。
3. **一键动作** —— 「载入示例语料」「库现状」「工具画像」这类不该占用对话输入的操作放在配置条上，结果同样以「动作」卡片落进对话流。
4. **错误会告诉你怎么修** —— 401/404/500/400 分别给出针对性提示（例如假 Key 时直接给出带真实 Key 的重启命令）。

几个顺手的深链（也方便批量截图核对）：

| 用法 | 说明 |
|---|---|
| `#scene=stage8` | 直接进某个场景，例如 `http://localhost:8080/#scene=stage8` |
| `&run=1` | 顺便自动发送该场景的第一条示例问法 |
| `&act=<n>` | 顺便执行第 n 个「动作」按钮（`n` 从 0 开始） |
| `&cfg=mode:compare` | 覆盖场景参数，多个用逗号分隔 |

> 例：`http://localhost:8080/#scene=stage6lab&cfg=mode:compare&run=1` 打开就是四策略并排对比。
> 不需要大模型的组合：`6L` 的三个模式、`8` 的纯检索与库管理动作、`D` 编码自检 —— **没有 API Key 也能完整验证**；其余场景都需要真实的 `DEEPSEEK_API_KEY`。

---

## 8 个阶段

| 阶段 | 接口前缀 | 学到什么 |
|---|---|---|
| 1 | `/stage1/**` | ChatClient 基础对话、占位符模板、SSE 流式输出 |
| 2 | `/stage2/**` | 多轮会话记忆：ChatMemory / ChatMemoryRepository / conversationId |
| 3 | `/stage3/**` | 工具调用：`@Tool`、`FunctionToolCallback`、ToolCallingAdvisor 自动驱动 |
| 4 | `/stage4/**` | 自定义 Advisor、order 语义、**在工具循环内部观测每一轮迭代** |
| 5 | `/stage5/**` | 结构化输出 `.entity()` + `StructuredOutputValidationAdvisor` 自纠错 |
| 6 | `/stage6/**` | 12 个工具场景 + **渐进式工具披露**（ToolSearchToolCallingAdvisor） |
| 7 | `/stage7/**` | MCP 客户端接入（默认关闭，按下方步骤开启） |
| 8 | `/stage8/**` | **RAG 知识库（L1 朴素 RAG，支持多库）**：本地 ONNX 嵌入 + 向量检索 + QuestionAnswerAdvisor；一库一目录，物理隔离 |

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
curl "http://localhost:8080/stage6/chat?message=帮 C1001 查一下最近的订单、物流、发票状态和账户余额&conversationId=user-42"
```

**要点**：

- 本项目注册了 13 个 CRM 工具。工具一多，把**全部工具定义**塞进每次请求会带来 **token 成本暴涨** + **模型选错工具**两个问题。
- `ToolSearchToolCallingAdvisor` 提供**渐进式工具披露**：先对全量工具建一次索引，每轮只把最相关的少数几个发给模型。官方实测可省 **34% ~ 64%** token。
- 索引类型三选一（`spring.ai.chat.client.tool-search-advisor.tool-index-type`）：
  | 类型 | 额外依赖 |
  |---|---|
  | `regex`（默认） | 无 |
  | `lucene` | `org.apache.lucene:lucene-core` |
  | `vector` | 需要 `VectorStore` Bean |
- ⚠️ **必须传会话 ID，否则接口直接 500。** 这个 Advisor 按「会话」缓存工具索引，它要从请求 context 里取一个会话标识，key 是 `ChatMemory.CONVERSATION_ID`（值 `chat_memory_conversation_id`）。取不到就抛：
  ```
  IllegalArgumentException: context must contain a non-null value for 'chat_memory_conversation_id'
  ```
  解决办法是显式把它塞进 context：接口上收一个 `conversationId` 参数，再用
  `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))` 注入。
  **注意它并不要求你开对话记忆** —— Advisor 只读这个 key，跟有没有挂 `ChatMemoryAdvisor` 无关。
  若不想沿用这个名字，用 `tool-search-advisor.session-id-key-name` 换 key 即可。
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

**前置条件**：

1. **必须装 Node.js**（≥18），因为下面的 filesystem server 是通过 `npx` 拉起的。
   本机装在 `D:\workspace\.toolchain\node`（自解压的官方 win-x64 包），已加入用户 PATH。
   验证：`node -v` 与 `npx.cmd --version` 都能出结果。
2. **Windows 上 `command` 必须写 `npx.cmd`，不能写 `npx`**。这不是笔误 ——
   Java 的 `ProcessBuilder` 最终调 `CreateProcess`，它**不会**按 `PATHEXT` 补全 `.cmd`/`.bat` 后缀；
   而 Node 的 Windows 发行版里 `npx` 是一个**无扩展名的 shell 脚本**（给 Git Bash 用的）。
   写成 `npx` 会直接抛：
   ```
   java.io.IOException: Cannot run program "npx": CreateProcess error=2, 系统找不到指定的文件。
   ```
   实测：`npx` ❌ / `npx.cmd` ✅ / `cmd /c npx` ✅ / 绝对路径 `…\npx.cmd` ✅。

**开启步骤**：

```bash
# 1. 复制配置模板
cp src/main/resources/application-mcp.yml.example src/main/resources/application-mcp.yml

# 2. 编辑 application-mcp.yml，把 stdio 那段里的目录换成你自己的
#    （默认已配好 npx.cmd + 国内镜像；Windows 上注意 command 要带 .cmd）

# 3. 建议先把 MCP server 包拉进 npx 缓存（首次下载可能超过 20s 初始化上限）
npx.cmd -y @modelcontextprotocol/server-filesystem D:/workspace
# 看到 "Secure MCP Filesystem Server running on stdio" 就是拉好了，Ctrl+C 退出

# 4. 用 mcp profile 启动
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
- **stdio server 起不来会拖垮整个应用**，不只是 stage7：`mcpSyncClients` 是全局 Bean，
  喂给 `toolCallbackResolver`，而 `toolCallingManager` → `deepSeekChatModel` → 所有 Controller 都依赖它。
  所以「npx 找不到」的报错栈最外层是 `basicChatController`，真正的根因在最后一行 `Caused by`。
- Spring AI 的 MCP 初始化超时是 **20s**（`McpSyncClient.initialize()` 内部），
  `spring.ai.mcp.client.request-timeout` 管不到它 —— 首次 `npx -y` 要下载包，很容易超时，
  所以第 3 步的缓存预热不是可选项。

---

### Stage 8 — RAG 知识库（L1 朴素 RAG）

前面 7 个阶段的模型都只能靠「训练时记住的东西 + 你当场给的提示」回答。
Stage 8 补上第三种信息源：**你自己的资料**。

#### 先说清楚「L1 朴素」是什么意思

整条链路只有三步，没有任何技巧：

```
① 入库（离线，一次性）
   文本 ──切块──▶ 若干 Document ──EmbeddingModel 逐块转向量──▶ VectorStore

② 检索 + 增强（每次提问）
   用户问题 ──EmbeddingModel 转向量──▶ VectorStore 取 topK 相似片段
            ──把片段拼进 Prompt──▶ 大模型 ──▶ 带依据的回答
```

「朴素」指的是：**检索只做一次向量相似度，查询原样使用、不改写、不重排、
也不判断「到底要不要检索」**。后面所有优化（L2 调参、L3 模块化、L4 混合检索 + 重排、
L5 Agentic 自主检索）都是在往这三个环节里加料。所以 L1 的目标不是效果好，
而是**先把链路跑通、并且能直接看见「检索到底命中了什么」**。

#### 三个组件，各自解决什么问题、由谁创建

| 组件 | 职责 | 谁创建 | 为什么 |
|---|---|---|---|
| `TransformersEmbeddingModel` | 文本 → 向量 | `Stage8RagConfig`（**单例**） | DeepSeek **没有** embedding 接口，嵌入只能另找一家；这里用本机 ONNX 模型，完全离线。它无状态，所有知识库共用同一个实例 |
| `SimpleVectorStore` | 存向量 + 相似度检索 | `KnowledgeBaseRegistry`（**每个库一个**） | 本机无 Docker，不引外部向量库；它是「一个 Map + 遍历算余弦」，千级片段够用 |
| `QuestionAnswerAdvisor` | 「检索 → 拼 Prompt」这段胶水 | `RagAdvisors.forStore(store, props)`（**按库现造**） | `spring-ai-vector-store-advisor` 里**只有类、没有自动配置**；而且它与 store 是一对一绑定的，多库只能现造 |

> 一条通用的重构信号：**当某个 Bean 从「全局唯一」变成「每份数据一个」时，
> 它就该从 `@Configuration` 里搬出去，交给管理那份数据的人去创建。**
> 把 `SimpleVectorStore` 硬留在配置类里，得到的是「所有知识库共用一份向量」——
> 而且它不会抛异常，只会静默地把数据混在一起。

#### 快速体验（按这个顺序最有感知）

```bash
# 启动（RAG 的模型没配好时会被拖住，所以先确认本地模型存在）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8090

# ① 先证明「模型原本不知道」—— 示例语料里的公司是虚构的
curl --noproxy '*' "http://localhost:8090/stage8/chat?message=追光科技的年假是怎么规定的？"
#    → 没有任何依据，模型只能编，或者说不知道

# ② 一键载入内置示例语料（resources/rag/*.md，3 篇虚构企业文档）
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb/ingest-sample"

# ③ 看检索层命中了什么 —— 这个接口不经过大模型，结果完全可复现
curl --noproxy '*' "http://localhost:8090/stage8/kb/search?query=%E5%B9%B4%E5%81%87%E6%9C%89%E5%87%A0%E5%A4%A9"

# ④ 再问同一个问题，看回答如何变成「有依据」
curl --noproxy '*' "http://localhost:8090/stage8/chat?message=追光科技的年假是怎么规定的？"

# ⑤ 最强的一个接口：一次调用并排返回「无 RAG / 有 RAG」两版回答 + 检索命中
curl --noproxy '*' "http://localhost:8090/stage8/chat/compare?message=%E5%80%BC%E7%8F%AD%E8%A1%A5%E8%B4%B4%E5%A4%9A%E5%B0%91%E9%92%B1%E4%B8%80%E5%A4%A9"
```

#### 接口一览

**库管理**（只动元数据，不碰向量）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stage8/kb` | 列出全部知识库（id / 名称 / 备注 / 创建时间 / 是否已加载） |
| POST | `/stage8/kb` | 建库，body `{id, name, description}`；id 只允许 `[A-Za-z0-9_-]`，且不能用 `docs`/`search` 等保留字 |
| GET | `/stage8/kb/{kbId}` | 查**这一个**库：名称、备注、目录、文档数、片段数 |
| PUT | `/stage8/kb/{kbId}` | 改名称 / 备注，body `{name, description}` —— **不传的字段保持不变** |
| DELETE | `/stage8/kb/{kbId}` | **删除**知识库（连目录一起）；默认库不允许删 |

**库内操作**（`{kbId}` 换成具体库 id；**不带 kbId 的老路径等价于 `default` 库**）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/stage8/kb/{kbId}/ingest` | 传 `{title, content, source?}` 切块入库。**默认追加**；加 `?mode=upsert` 才按 `source` 覆盖 |
| POST | `/stage8/kb/{kbId}/ingest-sample` | 载入 `resources/rag/*.md`（幂等，可重复调用） |
| GET | `/stage8/kb/{kbId}/search` | **纯向量检索，不调模型**（可选 `topK` / `threshold`） |
| GET | `/stage8/kb/{kbId}/stats` | 文档数、块数、维度、参数、落盘状态、清单 |
| DELETE | `/stage8/kb/{kbId}/clear`<br>或 `DELETE /stage8/kb?kbId=...` | **清空**该库内容（库本身保留）。两种写法等价 |
| POST | `/stage8/kb/{kbId}/save` `/load` | 向量库落盘 / 载入 |

**文档级**（先列出来拿 `docId`，再精确操作「一篇」）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stage8/kb/{kbId}/docs` | 列出库内每篇文档：`docId` / 标题 / 来源 / 块数 / 入库时间 |
| GET | `/stage8/kb/{kbId}/docs/{docId}` | 看这一篇的片段清单（**含块 id 与序号，不含正文**） |
| PUT | `/stage8/kb/{kbId}/docs/{docId}` | **覆盖更新**，body `{title?, content}` —— 重新切块入库，`docId` 不变 |
| DELETE | `/stage8/kb/{kbId}/docs/{docId}` | 删这一篇 |
| DELETE | `/stage8/kb/{kbId}/docs?source=...` | 按来源删一批（`source` 必传） |

**问答**（`kbId` 可走 query，也可走路径）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stage8/chat?kbId=kb1&message=...` | 只依据 `kb1` 的资料作答 |
| GET | `/stage8/chat/compare?kbId=kb1&message=...` | 无 RAG vs 有 RAG 对照（附 `kb1` 的检索命中） |
| GET | `/stage8/kb/{kbId}/chat?message=...` | 与上一行等价，库标识走路径 |
| GET | `/stage8/kb/{kbId}/chat/compare?message=...` | 同上 |

> 老路径 `/stage8/kb/search`、`/stage8/kb/ingest`、`DELETE /stage8/kb` 等**全部依然可用**，行为一个字没变。
> 升级一次就作废用户所有既有命令，是很差劲的体验 —— 所以新老两套指向同一段代码。

##### 三个删除接口的粒度

容易混，放一起对比：

| 想删什么 | 用哪个 |
|---|---|
| 一篇文档 | `DELETE /stage8/kb/{kbId}/docs/{docId}` |
| 一批同来源的文档 | `DELETE /stage8/kb/{kbId}/docs?source=...` |
| 整库的内容（库还在） | `DELETE /stage8/kb/{kbId}/clear` |
| 连库一起删掉 | `DELETE /stage8/kb/{kbId}` |

##### 为什么「改一篇」不能靠「再入库一次」

`POST /kb/{kbId}/ingest` 是**追加**语义：同一篇内容灌两次，库里就有两份，
检索时它们会互相挤占 `topK` 名额（同一段文字占掉两个位置）。

改一篇的正确姿势：

```bash
# 1. 先列文档，拿到 docId
curl --noproxy '*' "http://localhost:8090/stage8/kb/kb1/docs"

# 2. 覆盖更新（title 可省，content 必传）
printf '%s' '{"content":"新版本：单人审批上限 80000 元。"}' > doc.json
curl --noproxy '*' -X PUT "http://localhost:8090/stage8/kb/kb1/docs/<docId>" \
     -H 'Content-Type: application/json; charset=UTF-8' --data-binary @doc.json
```

`content` 为什么必传、不能只改标题？因为**正文原文并不在向量库里** ——
`VectorStore` 接口只有 `add / delete / similaritySearch`，**没有 `get(id)`**。
它存的是「片段 → 向量」，用途是「拿 query 找相似片段」，不是「按 id 读出原文」。
想回显原文得自己另存一份（这正是 L2 要补的 MySQL 正文表）。

> 顺带说明为什么默认是「追加」而不是「覆盖」：接口入库的 `source` 默认都是 `api`，
> 如果默认按 source 覆盖，连着灌三篇不同文档就会互相把对方删掉。
> **默认行为要对最常见的那种用法是安全的**，而不是对最省事的那次调用安全。

#### 多知识库：怎么创建「知识库1 / 知识库2」

##### 一库一目录

支持多库之后，`agentlab.rag.store-root` 指的是一个**目录**（不再是单个文件）：

```
<store-root>/                    # 默认 D:/workspace/.toolchain/rag-store
├── kb-index.tsv                 # 全部库的元数据（id / 名称 / 备注 / 创建时间）
├── default/                     # 默认库：不带 kbId 的老接口都落在这里
│   ├── store.json               # 向量本体
│   └── manifest.tsv             # 账本：哪几篇、各几块
├── kb1/
│   ├── store.json
│   └── manifest.tsv
└── kb2/
    └── ...
```

选**物理隔离**（一库一目录一向量库）而不是「单库 + metadata 过滤」，主要理由就是这张图：
`ls` 一眼看清有几个库、每个库多大，删库就是删目录，A 库的操作碰不到 B 库的文件。

顺带一个刻意的设计：**向量的加载是懒的**。启动时只恢复「有哪些库」这份元数据，
某个库的向量要等它第一次被访问（检索 / 入库 / 问答）才读盘。
20 个库的元数据读起来几毫秒，20 个库的向量读起来可能是几秒 + 几百 MB ——
「列个表」不该产生这种副作用。所以 `GET /stage8/kb` 里 `loaded=false` 的库，
文档数会显示 `-1`（未知），而不是为了填这个数字把所有库都读进内存。

##### 建库 / 列库 / 删库

```bash
# 建库。id 走 URL 和目录名，只允许 [A-Za-z0-9_-]；name 是给人看的标签。
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb" \
     -H "Content-Type: application/json" -d '{"id":"kb1","name":"kb-1"}'
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb" \
     -H "Content-Type: application/json" -d '{"id":"kb2","name":"kb-2"}'

# 列库：能看到 root 目录、库数量、每个库的名称与「是否已加载」
curl --noproxy '*' "http://localhost:8090/stage8/kb"

# 删库（连同目录）。默认库不允许删，只能清空。
curl --noproxy '*' -X DELETE "http://localhost:8090/stage8/kb/kb2"
```

**⚠️ 中文不要放进命令行参数。** Windows + Git Bash 下，argv 里的中文交给原生 `curl.exe`
时会被按 GBK 转换，服务端以非法 UTF-8 拒绝（400，`Invalid UTF-8 start byte 0xb2`）。
想给中文 `name`、或者入库中文 `title` / `content`，就把请求体写成 UTF-8 文件再发：

```bash
# 先用编辑器把这个文件另存为 UTF-8（不要用命令行 echo 中文）
# 文件内容：{"id":"kb1","name":"知识库1"}
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb" \
     -H "Content-Type: application/json" --data-binary @D:/workspace/.toolchain/_logs/kb1.json
```

（Swagger UI 里直接打字没有这个毛病 —— 那是浏览器发请求，不经过 shell。）

##### 验证「互不干扰」

```bash
# 往 kb1 灌一份只有它才有的资料（这里用 ASCII 便于直接粘进终端）
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb/kb1/ingest" \
     -H "Content-Type: application/json" \
     -d '{"title":"AAA-project","content":"AAA project acceptance: green-light rate 97%, owner Zhang San."}'

# 同一个 query，两个库各搜一次
curl --noproxy '*' "http://localhost:8090/stage8/kb/kb1/search?query=AAA%20project%20acceptance"
#   → 命中刚入库那条
curl --noproxy '*' "http://localhost:8090/stage8/kb/kb2/search?query=AAA%20project%20acceptance"
#   → []   —— kb2 里根本没有这条，物理隔离成立

# 再各载入一遍内置示例语料，然后清空 kb1：
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb/kb1/ingest-sample"
curl --noproxy '*' -X POST "http://localhost:8090/stage8/kb/kb2/ingest-sample"
curl --noproxy '*' -X DELETE "http://localhost:8090/stage8/kb/kb1/clear"
curl --noproxy '*' "http://localhost:8090/stage8/kb/kb2/stats"
#   → documents 仍然是 3 —— 清空 kb1 没动到 kb2
```

##### 两种隔离方案怎么选

| | A · 物理隔离（本项目） | B · 逻辑隔离 |
|---|---|---|
| 做法 | 每库一个 `SimpleVectorStore` + 一个目录 | 共用一个 store，片段 metadata 打 `kbId`，检索时 `filterExpression("kbId == 'kb1'")` |
| 建库 / 删库 | 建目录 / 删目录，物理上彻底隔开 | 只改元数据，不动存储 |
| 内存占用 | 随「活跃库数」线性增长（配合懒加载可控） | 一份，与库数无关 |
| 检索开销 | 只在本库的向量里算相似度 | 若实现是「全量算完再过滤」，库越多、单库越小，浪费越大 |
| 并发 | 锁按库隔离：灌 kb1 不阻塞 kb2 检索 | 同一个 store，写操作容易互相阻塞 |
| 误伤风险 | 几乎为零 | filter 写错一个字符，就可能读到别的库的内容 |

`SimpleVectorStore` **是支持 `filterExpression` 的**（它内部有
`SimpleVectorStoreFilterExpressionEvaluator`），所以 B 方案在本项目里也跑得通。
这里选 A，是因为「知识库」在用户脑中的语义就是「一个独立的东西」，
用文件系统来表达最自然、最好观察。

但要说清楚：**生产环境更常用 B**。专业向量库（Milvus / Qdrant / PGVector…）的
`filterExpression` 是**走索引**的 —— 先把候选集收敛到这个小分区，再算相似度，
而不是内存里逐条判断。所以「一张表存所有租户、用 metadata 过滤」反而是标准做法，运维成本也低得多。
换句话说：**A 是「用文件系统做隔离」的教学版，B 是「用数据库做隔离」的生产版**。
往下走时把 `VectorStore` 换成自研 MySQL 实现、顺手把 kbId 做成一列或一个分区，
就自然变成了 B —— 接口不用改，只改实现。

#### 要点

- **`/stage8/kb/search` 是最该先用的接口**。RAG 答不准时，第一步永远是确认
  「检索回来的片段里到底有没有答案」。这一步不掺模型、可复现，
  是唯一能把「检索没召回」和「模型没用好上下文」区分开的地方。
  如果这里就没召回到关键块，再怎么调提示词都是白费。

- **默认提示词必须换掉**。`QuestionAnswerAdvisor` 内置模板是英文的
  （`If the answer is not in the context, inform the user that you can't answer the question.`）。
  本项目用中文模板，并把「资料里没有就说没有」「遇到矛盾要指出而不是挑一个」写成硬规则 ——
  这些约束不该指望模型自觉。

- **Advisor order 给的是 `-100`**。Advisor 的 order 越小越靠外层。
  让 RAG 排在 `SimpleLoggerAdvisor`（默认 order = 0）**外面**，
  日志里打印出来的才是「已被注入检索片段」的最终 Prompt。
  反过来写的话，你只能看到用户原始那句提问，而「检索到了什么」就看不见了。

- **`QuestionAnswerAdvisor` 替换的是 user message，不是 system message**。
  反编译 `before()` 可以确认：它拿用户原文当 query 去检索，
  然后把 user message 整体换成渲染后的模板。所以 `defaultSystem(...)` 仍然生效，两者叠加。

- **向量库不管「清单」**。`VectorStore` 接口只有 `add / delete / similaritySearch` 四个方法，
  没有 `count()`、没有 `list()`。所以「我一共存了哪几篇、每篇几块」必须自己维护
  （本项目的 `*.manifest.tsv` 就是干这个的）。真实项目里的标准做法是
  **MySQL 存业务元数据 + 向量库存向量**，用同一个 documentId 关联 —— 这是 L2 最该先做的一件事。

- **切换向量库的工作量比想象中小**。接口只有 4 个方法，所以
  「换成 MySQL 自研实现」和「换成 PGVector / Milvus」的差别只是换一个 `@Bean`。

#### 踩过的坑（都写进代码注释了）

1. **`@ConditionalOnMissingBean` 是按「`@Bean` 方法返回类型」匹配的**。
   `TransformersEmbeddingModelAutoConfiguration` 上也声明了一个 `EmbeddingModel`。
   如果手工 Bean 的返回类型写成接口 `EmbeddingModel`，自动配置的条件匹配不上，
   会再建一个（且默认从 HuggingFace 在线拉模型的）`TransformersEmbeddingModel`，
   结果两个同类型 Bean → 注入 `VectorStore` 时直接
   `NoUniqueBeanDefinitionException`。
   **结论：覆盖框架的自动配置 Bean 时，返回类型要写得尽量具体、与自动配置的保持一致。**
   同样的道理让 `vectorStore` 的返回类型写成 `SimpleVectorStore` ——
   因为 `save(File)` / `load(File)` 只存在于实现类上，接口里没有。

2. **Spring Boot 4 用的是 Jackson 3（包名 `tools.jackson`）**。
   容器里**没有** `com.fasterxml.jackson.databind.ObjectMapper` 这个 Bean
   （Jackson 2 只是被 springdoc 之类顺带带进来的库，不受容器管理）。
   一开始想注入 `ObjectMapper` 读写清单，启动直接失败：
   `required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found`。
   最后清单改成最朴素的 TSV —— 自定格式的代价是要自己处理分隔符转义（制表符/换行替换成空格）。

3. **`TokenTextSplitter` 不支持重叠（overlap）**。
   它的构造函数里根本没有这个参数，相邻块不共享内容。
   于是关键句正好落在切口上时会被劈成两半，两个块各拿半句、谁也检索不爽。
   这不是配置问题，是 L1 用现成切块器的固有代价 ——
   **自己写一个带重叠的 `TextSplitter` 是 L2 的第一个升级点。**

4. **首次嵌入要 60 秒以上**。花在 onnxruntime 解压原生库 + Windows Defender 首次扫描上。
   实测：首次 `embed()` 60s+，之后稳态 20~90ms/条。
   不预热的话这 60 秒会精确砸在用户第一次提问上，现象非常像「服务挂了」。
   （本机现在原生库已解压过，实测预热只要 **1.5 秒**。）

5. **`clear()` 之后还能检索到内容 —— 孤儿向量**。这是调试过程中真实撞到的设计缺陷：
   `清单` 每次入库/清空都写盘，`向量文件` 原本只在手动 `/kb/save` 时才写，
   于是两者处在**不同的「代际」**。重启后把旧代际向量灌进内存，
   而清单里的 id 一个都对不上 → `clear()` 按清单 id 去删，删的是「不存在的 id」，
   旧向量永远留在库里。修正两条，缺一不可：**① 清单即为真相**（清单为空就不加载向量文件）；
   **② 变更即落盘**（入库/清空后清单与向量一起写）。
   通用教训：**「谁是权威」必须在设计时讲清楚** —— 让缓存当真相就会产生删不掉的残留状态。

6. **中文参数经 Git Bash 传给 `curl.exe` 会被转成 GBK**。这个是纯 Windows 环境坑，
   跟服务端无关，但排查起来很费时间。现象：
   ```bash
   # ❌ 中文写在命令行参数里
   curl -X POST localhost:8090/stage8/kb/ingest -H 'Content-Type: application/json' \
        -d '{"title":"差旅报销","content":"..."}'
   # → 400
   ```
   服务端日志给出真因：
   ```
   HttpMessageNotReadableException: JSON parse error: Invalid UTF-8 start byte 0xb2
   ```
   `0xb2` 正是 GBK 里「差」的首字节 —— <b>Git Bash 把 argv 交给原生 `curl.exe` 时按 ANSI（GBK）做了转换</b>。
   同样地，`curl -G --data-urlencode "query=年假"` 也会因此 400（curl 会把 GBK 字节原样百分号编码）。
   **正确做法**：中文一律不放进命令行参数 ——
   查询串先百分号编码好再拼进 URL，请求体写成 UTF-8 文件用 `--data-binary @file` 发。
   （本次验证就是按这个规矩写脚本的；脚本含本机绝对路径，所以没入库。）

   > 顺带一提：服务端拒收非法 UTF-8 是**正确行为**，别去改它。
   > 要改的是客户端怎么发。

#### ⚠️ 示例语料里有一处「故意矛盾」，别当 bug

示例语料是 3 篇**虚构**企业文档（`resources/rag/*.md`）。之所以用虚构内容：
大模型对这些条款零先验，「答对了 = 真的检索到了」才能被严格证明。

其中《员工手册》写「工作日值班 **200** 元/天」，《运维值班与故障响应规范》写 **300** 元/天 ——
**这是刻意设计的**。RAG 的价值不只是「答得出来」，还包括**把知识库自身的矛盾暴露出来**。
提示词里明确要求「遇到矛盾必须指出并分别列出」，所以问值班补贴时，
正确表现是「指出两份文档不一致」，而不是随便挑一个数字。

#### 示例语料长什么样

| 文件 | 内容 |
|---|---|
| `01-员工手册.md` | 考勤与远程办公、年假分档、加班与调休、值班补贴、报销、保密 |
| `02-产品与定价.md` | 三条产品线、标准报价、折扣政策、SLA、退订与续费 |
| `03-运维值班与故障响应.md` | 发布窗口与冻结期、故障等级、值班安排与补贴、监控、变更管理 |

#### 与前面阶段的缝合点

- **Stage 2（会话记忆）+ Stage 8**：RAG 只负责往「当前这一轮」注入资料，
  多轮追问时历史里并没有资料原文 —— 想让它「接着上文问」就需要把两者串起来。
- **Stage 3（工具调用）**：把「检索」包装成一个 `@Tool`，模型就能自己决定要不要检索、
  检索几次、换什么关键词 —— 这就是通往 L5 Agentic RAG 的自然路径。
- **Stage 6（渐进式披露）+ Stage 8**：一个是「工具太多要筛选」，一个是「资料太多要筛选」，
  本质是同一个问题：**上下文窗口是稀缺资源，进来之前先按相关度筛一遍。**

#### 验证测试

```bash
mvn test -Dtest=Stage8RagTest
```

9 个用例，**全程离线**（嵌入用本机 ONNX、检索用内存向量库、DeepSeek Key 是假的且不会被调用）。
它验证的是检索侧那些**确定性**的行为：命中来源是否正确、分数是否降序、
阈值能否过滤、重复入库是否幂等、清空是否干净、落盘能否往返。
—— 把 Agent 链路里可以确定化的部分确定化，是让 Agent 可回归的前提。

---

## 接口文档（Swagger / OpenAPI）

30+ 个接口分布在 8 个阶段里，靠 curl 手敲很容易记混。项目引入了 **springdoc-openapi 3.1.1** 自动生成 OpenAPI 3.1 文档。

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
│   ├── stage8/                               # RAG 知识库（L1 朴素 RAG，支持多库）
│   │   ├── config/RagProperties.java         # agentlab.rag.* 可调参数（topK/阈值/切块/预热/store-root）
│   │   ├── config/Stage8RagConfig.java       # 嵌入模型（单例，所有库共用）+ 启动预热
│   │   ├── config/RagAdvisors.java           # 中文提示词 + 按库现造 QuestionAnswerAdvisor
│   │   ├── KnowledgeBase.java                # 一个库：切块 + 入库 + 文档级增删改查 + 检索 + 清单 + 落盘
│   │   ├── KnowledgeBaseMeta.java            # 库的元数据（id / 名称 / 备注 / 创建时间）
│   │   ├── KnowledgeBaseRegistry.java        # 多库注册表：建/查/改/列/删 + kb-index.tsv 账本 + 保留字
│   │   └── Stage8RagController.java          # /stage8/kb[/{kbId}]/**（含 /docs）、/stage8/chat[/compare]
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
│   ├── application-mcp.yml.example
│   └── rag/{01-员工手册,02-产品与定价,03-运维值班与故障响应}.md   # Stage 8 内置示例语料（虚构）
└── src/test/java/com/agentlab/
    ├── AgentLabApplicationTests.java         # 上下文装配冒烟测试
    ├── OpenApiDocsTest.java                  # 真实 HTTP 校验 /v3/api-docs 与 Swagger UI
    ├── stage3/ToolsTest.java                 # 工具单测
    ├── stage6/lab/ToolIndexLabTest.java      # 四条检索策略的纯单测（可复现）
    └── stage8/
        ├── Stage8RagTest.java                # 默认库基线：检索侧单测（全程离线，不调用 DeepSeek）
        ├── Stage8MultiKnowledgeBaseTest.java # 多库：库间隔离、删库不影响别的库、边界状态码
        └── Stage8KbCrudTest.java             # 库级/文档级更新与查询：部分更新、docId 稳定、三种删除粒度
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
