package com.agentlab.stage4.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * 示例一：只做「观测」、不改写请求与响应的 Advisor。
 *
 * <p>实现 {@link BaseAdvisor} 最省事——它把 {@code adviseCall / adviseStream} 用
 * {@code before() / after()} 包好了，你只需要关心「进入前」和「返回后」。
 *
 * <p><b>顺序语义（务必记住）</b>：{@code getOrder()} 越小越靠外。链是按 order 升序执行的，
 * 所以 order 最小的 Advisor 最先拿到请求、最后拿到响应，相当于「最外层的洋葱皮」。
 * 因此用它测量总耗时（包含整个工具循环）是合适的。
 */
public class TimingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TimingAdvisor.class);

    private final String name;
    private final int order;
    /** 同步调用下，单次请求独占一个线程，ThreadLocal 足够 */
    private final ThreadLocal<Long> startNanos = new ThreadLocal<>();

    public TimingAdvisor(String name, int order) {
        this.name = name;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        startNanos.set(System.nanoTime());
        log.info("[{}] → 请求进入，当前 Prompt 消息数 = {}",
                name, request.prompt().getInstructions().size());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Long start = startNanos.get();
        long costMs = start == null ? -1L : (System.nanoTime() - start) / 1_000_000L;
        startNanos.remove();

        String promptTokens = "n/a";
        String completionTokens = "n/a";
        if (response.chatResponse() != null && response.chatResponse().getMetadata() != null
                && response.chatResponse().getMetadata().getUsage() != null) {
            var usage = response.chatResponse().getMetadata().getUsage();
            promptTokens = String.valueOf(usage.getPromptTokens());
            completionTokens = String.valueOf(usage.getCompletionTokens());
        }

        log.info("[{}] ← 响应返回，总耗时 = {} ms，promptTokens = {}，completionTokens = {}",
                name, costMs, promptTokens, completionTokens);
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return name;
    }
}
