package com.agentlab.stage8.config;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

/**
 * RAG 的「胶水」Advisor 工厂 —— 中文提示词模板 + 按库构造 {@link QuestionAnswerAdvisor}。
 *
 * <h2>为什么是工厂，而不是一个 {@code @Bean}</h2>
 * 单知识库时代，{@code QuestionAnswerAdvisor} 可以直接做成 Bean：全局只有一个库，
 * 一个 Advisor 绑死一个 {@code VectorStore} 就够了。
 *
 * <p>到了多知识库，这条路走不通了 —— <b>Advisor 与 VectorStore 是「一对一绑定」的</b>
 * （构造器 {@code QuestionAnswerAdvisor.builder(vectorStore)} 就把库焊死了）。
 * 于是要么注册 N 个 Bean 再按名字挑，要么改成「按需要临时造一个」。
 * 这里选后者：{@code QuestionAnswerAdvisor} 本身是个纯内存的轻量对象
 * （只持有 store 引用 + 模板 + 检索参数），构造它没有任何 I/O，
 * 所以「每个请求现造一个」完全不需要缓存，也就顺带躲开了
 * 「库被清空/重建之后缓存里的 Advisor 还指向旧库」这类失效问题。
 *
 * <h2>提示词为什么要换成中文</h2>
 * 默认模板是英文祈使句（"If the answer is not in the context, inform the user
 * that you can't answer the question."）。对中文知识库 + 中文提问来说，
 * 中文模板能让模型更稳定地「只依据给定资料作答、并说明依据来自哪一段」，
 * 也顺手把「不许编」这条硬规则写死在模板里 —— 而不是指望模型自觉。
 *
 * <h2>order 为什么是 -100</h2>
 * Advisor 的 order 越小越靠外层。让 RAG 排在
 * {@code SimpleLoggerAdvisor}（默认 order = 0）<b>外面</b>，
 * 日志里打出来的才是「已经被注入过检索片段」的最终 Prompt。
 * 调过来写的话，你只能看到用户原始那句提问，
 * 而「到底检索到了什么」这件事就看不见了 —— 那正是这一阶段最需要观察的东西。
 */
public final class RagAdvisors {

    private RagAdvisors() {
    }

    /** RAG Advisor 的 order：排在日志 Advisor 之外，便于观察「注入后」的 Prompt。 */
    public static final int RAG_ORDER = -100;

    /** 中文 RAG 提示词模板。{@code {query}} 与 {@code {question_answer_context}} 由框架填充。 */
    public static final String RAG_PROMPT_TEMPLATE = """
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
            """;

    /**
     * 为指定向量库装配一个 RAG Advisor。
     *
     * @param store 该知识库自己的向量库（多库方案里每个库一个实例）
     * @param props 检索参数（topK / similarityThreshold）来源，改配置即可生效
     */
    public static QuestionAnswerAdvisor forStore(SimpleVectorStore store, RagProperties props) {
        return QuestionAnswerAdvisor.builder(store)
                .promptTemplate(new PromptTemplate(RAG_PROMPT_TEMPLATE))
                .searchRequest(SearchRequest.builder()
                        .topK(props.getTopK())
                        .similarityThreshold(props.getSimilarityThreshold())
                        .build())
                .order(RAG_ORDER)
                .build();
    }
}
