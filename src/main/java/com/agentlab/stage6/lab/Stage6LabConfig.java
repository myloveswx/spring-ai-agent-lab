package com.agentlab.stage6.lab;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可选教学配置：<b>用自定义检索策略全局替换内置策略</b>。
 *
 * <p>默认<b>不生效</b>（{@code agentlab.stage6.lab.override-global-index} 未设置）。
 * 打开方式：
 * <pre>
 *   # 临时验证
 *   -Dagentlab.stage6.lab.override-global-index=true
 *
 *   # 或写进 application.yml
 *   agentlab:
 *     stage6:
 *       lab:
 *         override-global-index: true
 * </pre>
 *
 * <h2>为什么「声明一个 ToolIndex Bean」就能全局换掉策略</h2>
 * Spring AI 的 {@code ToolSearchAdvisorAutoConfiguration} 里，
 * regex / lucene / vector 三个内层配置类都带 {@code @ConditionalOnMissingBean}，
 * 而 {@code toolCallingAdvisorBuilder(...)} 又是按类型注入 {@code ToolIndex} 的。
 * 于是：<b>用户配置类中声明的 ToolIndex 会先注册，自动配置随后的条件判断发现
 * 「已经有 ToolIndex 了」便整体退让</b> —— 这是 Spring Boot 里
 * 「用户配置优先于自动配置」的标准行为，不需要 {@code @Primary}。
 *
 * <h2>两个必须注意的坑</h2>
 * <ol>
 *   <li><b>容器里只能有一个 ToolIndex Bean</b>。声明了这个就不要同时打开
 *       {@code tool-index-type: lucene}（那条分支虽然会退让，但语义上已经自相矛盾）。</li>
 *   <li>{@code tool-index-type} 的属性校验<b>只打日志告警、不抛异常</b>
 *       （白名单仅 regex/lucene/vector）。所以填一个自定义名字不会报错，
 *       但也<b>不会</b>生效 —— yml 里那个字段永远无法指向你自己写的类。
 *       换实现只有两条路：声明 Bean，或者 {@code Builder.toolIndex(...)} 手工装配。</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "agentlab.stage6.lab.override-global-index", havingValue = "true")
public class Stage6LabConfig {

    /**
     * 全局生效的自定义索引：自定义分词 + 领域词典 + 分类过滤。
     *
     * <p>注意这里<b>故意</b>不加 {@code @Primary} —— 加了反而掩盖了
     * 「自动配置退让」这个真正起作用的机制，不利于理解。
     */
    @Bean
    public ToolIndex labToolIndex() {
        return new CategoryFilteredToolIndex(new SynonymBoostedToolIndex(true));
    }
}
