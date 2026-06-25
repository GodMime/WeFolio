package com.jxc.wefolio.controller;

import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.annotation.SystemAccess;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

/**
 * 系统接口控制器 — 提供健康检查和版本信息，供负载均衡器和监控系统调用。
 */
@SystemAccess
@RestController
public class VersionController {

    /**
     * 健康检查接口。
     *
     * @return 服务状态
     */
    @GetMapping("/api/health")
    public Response<Map<String, String>> health() {
        return Response.success(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ));
    }

    /**
     * 版本信息接口。
     *
     * @return 当前构建版本信息
     */
    @GetMapping("/api/version")
    public Response<Map<String, String>> version() {
        String version = "unknown";
        String buildTime = "unknown";

        try {
            ClassPathResource resource = new ClassPathResource("version.properties");
            try (InputStream is = resource.getInputStream()) {
                Properties props = new Properties();
                props.load(is);
                version = props.getProperty("build.version", "unknown");
                buildTime = props.getProperty("build.time", "unknown");
            }
        } catch (Exception e) {
            // 读取失败时保留默认 unknown，避免健康探测受构建信息影响。
        }

        return Response.success(Map.of(
                "version", version,
                "buildTime", buildTime
        ));
    }
}
