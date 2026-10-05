package com.agentlab.stage6.lab;

import java.util.function.Supplier;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;

/**
 * Stage 6 实验室：四条可相互对照的工具检索策略。
 *
 * <p>它们并不是「哪个最好」的关系，而是<b>逐层加码</b>的关系 ——
 * 从 Spring AI 内置的通用策略出发，每一步只补一个能力，
 * 目的是让你看清「检索效果是哪个环节带来的」：
 *
 * <pre>
 *   REGEX     内置 RegexToolIndex               —— 基线（对中文基本失效）
 *     │  + 中文 n-gram 分词 + 工具名加权
 *   KEYWORD   自定义，但刻意不用同义词词典      —— 看纯分词能提升多少
 *     │  + CRM 领域同义词词典
 *   SYNONYM   自定义，含词典                    —— 看业务知识能提升多少
 *     │  + 分类前置过滤（装饰器）
 *   CATEGORY  自定义，含词典与分类硬过滤        —— 看结构约束能提升多少
 * </pre>
 *
 * <p>每一层都是独立、可替换的 {@link ToolIndex}，可以自由组合 ——
 * 这正是 {@code ToolSearchToolCallingAdvisor.Builder#toolIndex(ToolIndex)}
 * 这个扩展点设计得好的地方：<b>它把「检索」这件与模型无关的事彻底解耦出来了</b>。
 */
public enum SearchStrategy {

    /** 基线：Spring AI 自带实现，零额外代码。 */
    REGEX("regex", "内置 RegexToolIndex（英文停用词 + 正则）",
            RegexToolIndex::new),

    /** 只换分词与打分，不注入任何业务词典 —— 用来做「消融对照」。 */
    KEYWORD("keyword", "自定义：中文 2/3-gram + 工具名加权（无词典）",
            () -> new SynonymBoostedToolIndex(false)),

    /** 加上 CRM 领域同义词词典，口语说法可以直接命中工具。 */
    SYNONYM("synonym", "自定义：KEYWORD + CRM 领域同义词词典",
            () -> new SynonymBoostedToolIndex(true)),

    /** 最外层再套一个「分类前置过滤」装饰器，演示索引可组合。 */
    CATEGORY("category", "自定义：SYNONYM + 分类过滤装饰器（可组合）",
            () -> new CategoryFilteredToolIndex(new SynonymBoostedToolIndex(true)));

    private final String code;
    private final String label;
    private final Supplier<ToolIndex> factory;

    SearchStrategy(String code, String label, Supplier<ToolIndex> factory) {
        this.code = code;
        this.label = label;
        this.factory = factory;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 返回一个<b>全新</b>的索引实例。
     *
     * <p>必须是新实例：{@code ToolIndex} 是<b>有状态</b>的（内部按 sessionId 缓存索引），
     * 复用同一个实例会让不同策略的实验互相污染。真实项目里它应该是单例 Bean ——
     * 按会话隔离这件事由实现内部的 {@code Map<sessionId, ...>} 负责，
     * 不是靠「每个会话 new 一个」。
     */
    public ToolIndex newIndex() {
        return factory.get();
    }

    /** 宽松解析：认不出来一律回落到基线 {@link #REGEX}，避免把参数错误变成 500。 */
    public static SearchStrategy fromCode(String code) {
        if (code == null || code.isBlank()) {
            return REGEX;
        }
        String c = code.trim();
        for (SearchStrategy s : values()) {
            if (s.code.equalsIgnoreCase(c)) {
                return s;
            }
        }
        return REGEX;
    }
}
