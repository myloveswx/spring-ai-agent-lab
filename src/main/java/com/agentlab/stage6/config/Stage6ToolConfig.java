package com.agentlab.stage6.config;

import com.agentlab.stage6.tools.CrmTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stage 6 工具装配。
 *
 * <p>注意：带 {@code @Tool} 注解的 POJO 注册成普通 Bean 后<b>不会</b>被自动注册为工具，
 * 必须通过 {@code .defaultTools(...)} 或 {@code .tools(...)} 显式挂到请求上，
 * 或者包装成 {@code ToolCallback} Bean。这个边界在 2.0 里被刻意收紧，
 * 以避免「应用里随便一个 Bean 都能被模型调用」的安全隐患。
 */
@Configuration
public class Stage6ToolConfig {

    @Bean
    public CrmTools crmTools() {
        return new CrmTools();
    }
}
