package com.jxc.wefolio.config;

import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Fastjson2 配置 — 替代 Jackson 作为 Spring MVC 统一 JSON 序列化/反序列化库。
 * <p>
 * 采用 {@code extendMessageConverters} 方式：移除已有的 Jackson 转换器，
 * 普通 JSON 对象及响应使用 Fastjson；原始 JSON 字符串优先读取原文，保留显式 null 和重复键。
 * 其他默认转换器继续处理 XML、文本等内容。
 * <p>
 * Fastjson2 默认已忽略未知字段（等价于 Jackson {@code @JsonIgnoreProperties(ignoreUnknown=true)}），无需额外配置。
 */
@Configuration
public class FastJsonWebConfig implements WebMvcConfigurer {

    /** 配置 JSON 边界优先级，严格校验接口必须在 Service 层首次解析请求原文。 */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 移除 Jackson 转换器（如果存在）
        converters.removeIf(c -> c.getClass().getName().contains("Jackson")
                || c.getClass().getName().contains("MappingJackson"));

        // Fastjson 转换器，优先级最高
        FastJsonHttpMessageConverter converter = new FastJsonHttpMessageConverter();
        converter.setSupportedMediaTypes(Collections.singletonList(MediaType.APPLICATION_JSON));
        converters.add(0, converter);

        // Fastjson 读取对象为 String 时会重序列化并丢失 null；原文接口不能经过此转换。
        StringHttpMessageConverter rawJson = new StringHttpMessageConverter(StandardCharsets.UTF_8) {
            /** 此转换器只读取原文，JSON 响应仍由 Fastjson 序列化。 */
            @Override
            public boolean canWrite(Class<?> clazz, MediaType mediaType) {
                return false;
            }
        };
        rawJson.setSupportedMediaTypes(Collections.singletonList(MediaType.APPLICATION_JSON));
        converters.add(0, rawJson);
    }
}
