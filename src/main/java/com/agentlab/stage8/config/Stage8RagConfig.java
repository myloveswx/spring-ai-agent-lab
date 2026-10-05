package com.agentlab.stage8.config;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import io.micrometer.observation.ObservationRegistry;

/**
 * Stage 8 —— RAG 知识库（L1 朴素 RAG）的装配。
 *
 * <h2>L1「朴素 RAG」到底朴素在哪</h2>
 * 整条链路只有三步，没有任何「技巧」：
 * <pre>
 *   ① 入库（离线，一次性）
 *        文本 ──切块──▶ 若干 Document ──EmbeddingModel 逐块转向量──▶ VectorStore
 *
 *   ② 检索 + 增强（每次提问）
 *        用户问题 ──EmbeddingModel 转向量──▶ VectorStore 取 topK 相似片段
 *                 ──把片段拼进 Prompt──▶ 大模型 ──▶ 带出处依据的回答
 * </pre>
 * 这里的「朴素」指的是：<b>检索只用一次向量相似度，查询原样使用、不改写、不重排、
 * 也不判断「到底要不要检索」</b>。后续 L2~L5 的全部工作，本质上都是在往这三个环节里
 * 加料（改切块、改 topK、加查询改写、加混合检索、加重排、让 Agent 自己决定检索几轮）。
 * 所以先把 L1 跑通、并且<b>能直接看到检索命中了什么</b>，后面每一步优化才有基线可比。
 *
 * <h2>三个 Bean 各自解决什么问题</h2>
 * <table border="1">
 *   <caption>Stage 8 核心 Bean</caption>
 *   <tr><th>Bean</th><th>职责</th><th>为什么需要自己声明</th></tr>
 *   <tr>
 *     <td>{@link EmbeddingModel}</td>
 *     <td>文本 → 向量</td>
 *     <td>DeepSeek <b>没有</b> embedding 接口，必须另找一家；
 *         这里用本机 ONNX 模型，完全离线</td>
 *   </tr>
 *   <tr>
 *     <td>{@link VectorStore}</td>
 *     <td>存向量 + 相似度检索</td>
 *     <td>无 Docker、不引外部向量库，用内置 {@link SimpleVectorStore}（内存 + 可落盘）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link QuestionAnswerAdvisor}</td>
 *     <td>「检索 → 拼 Prompt」这段胶水</td>
 *     <td>spring-ai-vector-store-advisor 这个 artifact 里只有类、没有自动配置</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么整块配置带开关</h2>
 * {@code @ConditionalOnProperty(agentlab.rag.enabled)} + {@code matchIfMissing=true}
 * 的组合是刻意的：默认开启，让 Stage 8 开箱可用；但一旦模型文件缺失、或者你只想跑
 * Stage 1~7，把 {@code agentlab.rag.enabled} 设为 {@code false} 就能让这一整块
 * （含下面那个会在启动期加载 ONNX 的 Bean）彻底不参与装配，
 * <b>不会像 Stage 7 的 MCP stdio 那样把整个应用拖下水</b>。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "agentlab.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Stage8RagConfig {

    private static final Logger log = LoggerFactory.getLogger(Stage8RagConfig.class);

    /** 模型目录内必须存在的两个文件：分词器 + ONNX 权重。 */
    private static final String TOKENIZER_FILE = "tokenizer.json";
    private static final String MODEL_FILE = "model.onnx";

    /**
     * 嵌入模型：本机 ONNX 推理，不联网。
     *
     * <p>这里用 {@code new TransformersEmbeddingModel()} 手工装配，而不是靠
     * {@code spring.ai.embedding.transformer.*} 那套自动配置，原因有两个：
     * <ol>
     *   <li><b>可控</b>：自动配置的默认 URI 指向 HuggingFace 线上地址，
     *       本机实测只有 ~51KB/s，95MB 要下近半小时；显式指向本地文件则零网络依赖。</li>
     *   <li><b>可解释</b>：把「分词器 + 权重」两个资源显式写出来，
     *       一眼能看清「一次 embed 到底加载了什么」。</li>
     * </ol>
     *
     * <p>{@code TransformersEmbeddingModelAutoConfiguration} 上带
     * {@code @ConditionalOnMissingBean}，而自动配置恒晚于用户配置执行，
     * 所以这个手工 Bean 会稳稳胜出，不需要 {@code @Primary}。
     *
     * <p>⚠️ <b>这里返回类型必须写成 {@link TransformersEmbeddingModel}，不能偷懒写成
     * {@link EmbeddingModel}。</b> 因为自动配置里的 {@code @ConditionalOnMissingBean}
     * 一个属性都没写 —— 此时 Spring Boot 判定「已存在同类型 Bean 吗」用的是
     * <b>{@code @Bean} 方法的声明返回类型</b>。若这里声明成接口 {@code EmbeddingModel}，
     * 条件匹配不上，内置自动配置会再建一个（且默认从 HuggingFace 在线拉模型的）
     * {@code TransformersEmbeddingModel}，结果就是两个 {@code EmbeddingModel} Bean，
     * 注入 {@code VectorStore} 时直接 {@code NoUniqueBeanDefinitionException}。
     * <p>实践结论：<b>覆盖框架的自动配置 Bean 时，返回类型要尽量与自动配置的
     * {@code @Bean} 方法返回类型逐字一致</b>，别图省事只写接口。
     *
     * <p>注意：这里<b>不做</b>预热。预热放在 {@link #ragWarmupRunner}，
     * 因为 {@code afterPropertiesSet()} 要等 {@code @Bean} 方法返回后
     * 才由容器调用，此时模型还没初始化完。
     */
    @Bean
    public TransformersEmbeddingModel embeddingModel(RagProperties props) {
        File dir = new File(props.getModelDir());
        File tokenizer = new File(dir, TOKENIZER_FILE);
        File model = new File(dir, MODEL_FILE);

        // 失败要「响得清楚」：模型缺失是最常见的启动问题，
        // 报错信息必须直接给出「去哪拿、放哪里」，而不是抛一句 FileNotFoundException。
        if (!tokenizer.isFile() || !model.isFile()) {
            throw new IllegalStateException("""
                    Stage 8 启动失败：本地嵌入模型文件不完整。

                      期望目录 : %s
                      %s : %s
                      %s : %s

                    获取方式（任选其一）：
                      1) 直接从别人机器拷贝整个目录到上面这个路径；
                      2) 走国内镜像下载（实测 ~1.7MB/s，约 1 分钟）：
                         curl -L -o %s https://hf-mirror.com/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
                         curl -L -o %s https://hf-mirror.com/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer.json

                    只想先跑通 Stage 1~7？在 application.yml 里把
                      agentlab.rag.enabled: false
                    打开，这一整块装配和它的接口都会消失，其余阶段不受影响。
                    """.formatted(
                    dir.getAbsolutePath(),
                    TOKENIZER_FILE, tokenizer.isFile() ? "OK" : "缺失",
                    MODEL_FILE, model.isFile() ? "OK" : "缺失",
                    tokenizer.getAbsolutePath(),
                    model.getAbsolutePath()));
        }

        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        embeddingModel.setTokenizerResource(new FileSystemResource(tokenizer));
        embeddingModel.setModelResource(new FileSystemResource(model));
        log.info("Stage 8 · 嵌入模型已装配：{}（tokenizer {} bytes / model {} bytes），"
                        + "真实加载发生在首次调用时",
                dir.getAbsolutePath(), tokenizer.length(), model.length());
        return embeddingModel;
    }

    /**
     * 向量库：内置的 {@link SimpleVectorStore}。
     *
     * <p>它是「一个 ConcurrentHashMap + 遍历算余弦」，没有任何索引结构 ——
     * 千级片段完全够用，几十万条就会慢。之所以先用它：
     * 没有 Docker（本机实测镜像源不可用），而 {@code VectorStore} 接口只有
     * {@code add / delete / similaritySearch} 四个方法，
     * <b>先摸清接口契约，后面换成 MySQL 自研实现、或者换 PGVector / Milvus 都只是换一个 Bean</b>。
     *
     * <p>注意 {@code observationRegistry}：不显式传会退化成 {@code ObservationRegistry.NOOP}，
     * 那样 Stage 4 学到的 Micrometer 指标就看不到向量检索这段耗时了。
     *
     * <p>返回类型照旧写<b>具体类 {@link SimpleVectorStore}</b> 而不是接口 {@code VectorStore} ——
     * 因为 {@code save(File)} / {@code load(File)} 这两个落盘方法只存在于实现类上，
     * 接口里没有。写成接口的话，调用方要么被迫强转、要么拿不到持久化能力。
     * 这与 {@link #embeddingModel} 那条返回类型规则是同一个道理：
     * <b>能写具体类就别图省事写接口</b>。
     */
    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel,
                                         ObjectProvider<ObservationRegistry> observationRegistry) {
        return SimpleVectorStore.builder(embeddingModel)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    /**
     * RAG 的「胶水」Advisor：提问进来 → 检索 → 改写 Prompt。
     *
     * <p>反编译 {@code QuestionAnswerAdvisor#before} 可以看到它干了三件事（顺序固定）：
     * <pre>
     *   1. query = 当前用户消息原文（不做任何改写）
     *   2. docs  = vectorStore.similaritySearch(query, topK, threshold)
     *      context.put("qa_retrieved_documents", docs)   ← 检索命中的原文就挂在这里
     *   3. 用 promptTemplate 渲染 {query} + {question_answer_context}，
     *      然后把「用户消息」整体替换成渲染结果
     * </pre>
     * 第 3 点很关键：它替换的是 <b>user message</b>，不是 system message，
     * 所以你在 ChatClient 上设的 {@code defaultSystem(...)} 仍然生效，两者是叠加关系。
     *
     * <h3>为什么必须换掉默认提示词</h3>
     * 默认模板是英文祈使句（"If the answer is not in the context, inform the user
     * that you can't answer the question."）。对中文知识库 + 中文提问来说，
     * 中文模板能让模型更稳定地「只依据给定资料作答、并说明依据来自哪一段」，
     * 也顺手把「不许编」这条硬规则写死在模板里 —— 而不是指望模型自觉。
     *
     * <h3>order 为什么给 -100</h3>
     * Advisor 的 order 越小越靠外层。这里让 RAG 排在
     * {@link SimpleLoggerAdvisor}（默认 order = 0）**外面**，
     * 于是日志里打出来的就是「已经被注入过检索片段」的最终 Prompt。
     * 调过来写的话，你只能看到用户原始那句提问，
     * 而「到底检索到了什么」这件事就看不见了 —— 那正是 L1 最需要观察的东西。
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore, RagProperties props) {
        PromptTemplate ragPrompt = new PromptTemplate("""
                {query}

                ===== 以下是知识库中检索到的资料 =====
                ---------------------
                {question_answer_context}
                ---------------------
                ===== 资料结束 =====

                回答要求：
                1. 只依据上面「检索到的资料」作答。资料里没有写的内容，直接说「知识库中没有相关信息」，
                   绝不使用你自己的先验知识补充或猜测。
                2. 资料中若出现互相矛盾的说法，指出矛盾并分别列出，不要自行选一个。
                3. 引用到具体条款/数字时，把「来自哪一段资料」一并说明。
                4. 用简洁的中文回答，不要复述这段指令本身。
                """);

        return QuestionAnswerAdvisor.builder(vectorStore)
                .promptTemplate(ragPrompt)
                .searchRequest(SearchRequest.builder()
                        .topK(props.getTopK())
                        .similarityThreshold(props.getSimilarityThreshold())
                        .build())
                // 外层：先检索、先改写 Prompt，再交给内层的日志 Advisor 打印
                .order(-100)
                .build();
    }

    /**
     * 启动预热：把首次嵌入的一次性开销提前到启动期。
     *
     * <p>实测数据（本机，bge-small-zh-v1.5）：
     * <pre>
     *   首次 embed()  ................ 60 秒以上
     *   之后稳态       ............... 20 ~ 90 ms / 条
     * </pre>
     * 那 60 秒花在一次性动作上：onnxruntime 把原生库解压到临时目录 + Windows Defender
     * 首次扫描该 dll。如果不预热，这 60 秒会精确地砸在「用户第一次提问」上，
     * 现象是接口长时间无响应 —— 排查起来非常像「服务挂了」。
     *
     * <p>用 {@link ApplicationRunner} 而不是在 {@code @Bean} 方法里直接 embed：
     * 前者在容器刷新完成后执行，那时 {@code afterPropertiesSet()} 已经跑过，
     * 模型是真就绪的；后者会让模型被初始化两次。
     */
    @Bean
    public ApplicationRunner ragWarmupRunner(EmbeddingModel embeddingModel, RagProperties props) {
        return args -> {
            if (!props.isWarmup()) {
                log.info("Stage 8 · 已跳过预热（agentlab.rag.warmup=false），"
                        + "注意首次调用会额外等待约 60 秒");
                return;
            }
            long start = System.currentTimeMillis();
            float[] vector = embeddingModel.embed("预热：这是一条用于触发原生库加载的临时文本");
            long cost = System.currentTimeMillis() - start;
            log.info("Stage 8 · 嵌入预热完成：维度 = {}，耗时 {} ms。"
                            + "（这条耗时只在启动时出现一次，后续单条嵌入通常 20~90ms）",
                    vector.length, cost);
            log.info("Stage 8 · 接口入口：GET /stage8/kb/search?query=...  |  "
                    + "GET /stage8/chat?message=...  |  GET /stage8/chat/compare?message=...");
        };
    }
}
