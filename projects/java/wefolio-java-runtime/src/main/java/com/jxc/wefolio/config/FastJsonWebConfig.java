package com.jxc.wefolio.config;

import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
import java.util.List;

/**
 * Fastjson2 配置 — 替代 Jackson 作为 Spring MVC 统一 JSON 序列化/反序列化库。
 * <p>
 * 采用 {@code extendMessageConverters} 方式：移除已有的 Jackson 转换器，
 * 在最前面插入 Fastjson 转换器，保留 StringHttpMessageConverter 等其他默认转换器。
 * <p>
 * Fastjson2 默认已忽略未知字段（等价于 Jackson {@code @JsonIgnoreProperties(ignoreUnknown=true)}），无需额外配置。
 */
@Configuration
public class FastJsonWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 移除 Jackson 转换器（如果存在）
        converters.removeIf(c -> c.getClass().getName().contains("Jackson")
                || c.getClass().getName().contains("MappingJackson"));

        // Fastjson 转换器，优先级最高
        FastJsonHttpMessageConverter converter = new FastJsonHttpMessageConverter();
        converter.setSupportedMediaTypes(Collections.singletonList(MediaType.APPLICATION_JSON));
        converters.add(0, converter);
    }
}
