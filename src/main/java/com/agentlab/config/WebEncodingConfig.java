package com.agentlab.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 HTTP 编码配置 —— 解决「接口返回中文乱码」（第二层）。
 *
 * <p><b>乱码根因</b>：Spring MVC 的 {@link StringHttpMessageConverter}
 * 默认字符集是 {@code ISO-8859-1}（单字节编码），并不是 UTF-8。
 * 只要 Controller 直接返回 {@code String}，Spring 就会写出
 * {@code Content-Type: text/plain;charset=ISO-8859-1}，
 * 于是中文被按单字节序列写出，客户端无论怎么解都是乱码。
 *
 * <p><b>为什么用 extendMessageConverters 而不是 configureMessageConverters</b>：
 * <ul>
 *   <li>{@code configureMessageConverters(List)} —— <b>整体替换</b>默认转换器列表，
 *       一不小心就把 Jackson、ByteArray 等一并清空，属于高频踩坑点。</li>
 *   <li>{@code extendMessageConverters(List)} —— <b>在默认列表基础上做增删改</b>，
 *       我们只改 StringHttpMessageConverter 的默认字符集，其余原样保留。</li>
 * </ul>
 *
 * <p>注意：返回对象（如 POJO / Map）时走的是 Jackson，其默认已是 UTF-8，
 * 所以只返回 JSON 的接口通常不会乱码 —— 这也是为什么乱码往往
 * 只在「返回纯文本 String」的接口上暴露。
 */
@Configuration
public class WebEncodingConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(StringHttpMessageConverter.class::isInstance)
                .map(StringHttpMessageConverter.class::cast)
                .forEach(converter -> converter.setDefaultCharset(StandardCharsets.UTF_8));
    }
}
