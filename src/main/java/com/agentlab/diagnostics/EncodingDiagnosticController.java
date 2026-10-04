package com.agentlab.diagnostics;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentlab.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编码自检端点 —— 不依赖大模型，专门用来验证请求 / 响应链路的字符集。
 *
 * <p>为什么需要它：调用大模型的接口要花 token、要联网、还要等模型返回，
 * 排查编码问题时不划算。这里用纯本地字符串做「不变量」：
 * 只要这两个端点返回的中文正常，就说明 Spring MVC 侧的编码配置没问题，
 * 剩下的乱码一律来自客户端（终端代码页、IDE 控制台等）。
 *
 * <pre>
 * 验证响应编码：
 *   curl -s -D - "http://localhost:8090/diagnostics/encoding/text?text=测试中文"
 *
 * 验证响应头是否带 charset（关键看这里）：
 *   Content-Type: text/plain;charset=UTF-8
 * </pre>
 */
@RestController
@RequestMapping("/diagnostics")
@Tag(name = OpenApiConfig.TAG_DIAGNOSTICS)
public class EncodingDiagnosticController {

    private static final String SAMPLE = "中文编码自检：春眠不觉晓，处处闻啼鸟。";

    /**
     * 返回纯文本 String —— 最容易暴露 ISO-8859-1 默认字符集问题的路径。
     */
    @GetMapping("/encoding/text")
    @Operation(summary = "纯文本响应（最易暴露乱码）",
            description = "返回 text/plain。重点看响应头里的 Content-Type 是否带 charset=UTF-8 —— "
                    + "没带（或被写成 ISO-8859-1）就会乱码。这是不花 Token 的编码自检通道。")
    public String text(
            @Parameter(description = "任意文本，回显在响应里", example = "测试中文")
            @RequestParam(defaultValue = "你好，世界") String text) {
        return SAMPLE + System.lineSeparator() + "收到参数：" + text;
    }

    /**
     * 返回 JSON 对象 —— 走 Jackson（默认 UTF-8），用于对比。
     * 顺带把运行时的字符集信息一并返回，方便定位问题出在哪一层。
     */
    @GetMapping("/encoding/json")
    @Operation(summary = "JSON 响应 + 运行时字符集信息",
            description = "走 Jackson（本身默认 UTF-8），用于与上一条对照定位问题出在哪一层；"
                    + "同时回传 JVM 的 file.encoding 与默认字符集，方便判断是不是运行环境的问题。")
    public Map<String, Object> json(
            @Parameter(description = "任意文本，回显在响应的 received 字段", example = "你好，世界")
            @RequestParam(defaultValue = "你好，世界") String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", text);
        result.put("sample", SAMPLE);
        result.put("expectedCharset", StandardCharsets.UTF_8.name());
        result.put("jvmFileEncoding", System.getProperty("file.encoding"));
        result.put("jvmDefaultCharset", java.nio.charset.Charset.defaultCharset().name());
        return result;
    }
}
