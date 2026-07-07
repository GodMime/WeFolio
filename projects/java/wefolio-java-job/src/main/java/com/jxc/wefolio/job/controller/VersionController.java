package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

/**
 * 系统接口控制器 — 提供后台任务服务的健康检查和版本信息。
 */
@RestController
public class VersionController {

    /** 服务名 */
    private static final String SERVICE_NAME = "wefolio-java-job";

    /** 健康状态 */
    private static final String HEALTH_STATUS_UP = "UP";

    /** 未知构建信息 */
    private static final String UNKNOWN_BUILD_VALUE = "unknown";

    /** 构建版本属性名 */
    private static final String BUILD_VERSION_PROPERTY = "build.version";

    /** 构建时间属性名 */
    private static final String BUILD_TIME_PROPERTY = "build.time";

    /**
     * 健康检查接口。
     *
     * @return 服务状态
     */
    @GetMapping("/api/health")
    public Response<Map<String, String>> health() {
        return Response.success(Map.of(
                "service", SERVICE_NAME,
                "status", HEALTH_STATUS_UP,
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
        Properties properties = loadBuildProperties();

        return Response.success(Map.of(
                "service", SERVICE_NAME,
                "version", properties.getProperty(BUILD_VERSION_PROPERTY, UNKNOWN_BUILD_VALUE),
                "buildTime", properties.getProperty(BUILD_TIME_PROPERTY, UNKNOWN_BUILD_VALUE)
        ));
    }

    /**
     * 读取构建属性文件。
     *
     * @return 构建属性；读取失败时返回空属性
     */
    private Properties loadBuildProperties() {
        Properties properties = new Properties();
        try {
            ClassPathResource resource = new ClassPathResource("version.properties");
            try (InputStream inputStream = resource.getInputStream()) {
                properties.load(inputStream);
            }
        } catch (Exception e) {
            // 构建信息读取失败不应影响健康检查和部署探活。
        }
        return properties;
    }
}
