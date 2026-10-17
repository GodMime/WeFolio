package com.jxc.wefolio.service;

import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

/** 系统探活与构建信息，字体故障不改变既有整站健康语义。 */
@Service
@RequiredArgsConstructor
public class SystemStatusService {
    /** 包内构建信息资源。 */
    private static final String VERSION_RESOURCE = "version.properties";
    /** 未提供构建属性时保留的旧返回值。 */
    private static final String UNKNOWN = "unknown";
    /** 节点字体就绪状态。 */
    private final PortfolioFontSources fontSources;

    /** 保留原 status 与 ISO 本地时间，增量附加字体能力。 */
    public Map<String, Object> health() {
        return Map.of("status", "UP", "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "portfolioFonts", fontSources.diagnostics());
    }

    /** 版本资源缺失时沿用原 unknown 表达。 */
    public Map<String, String> version() {
        Properties properties = new Properties();
        try (InputStream input = new ClassPathResource(VERSION_RESOURCE).getInputStream()) { properties.load(input); }
        catch (Exception ignored) { /* 版本资源缺失不阻断探活。 */ }
        return Map.of("version", properties.getProperty("build.version", UNKNOWN),
                "buildTime", properties.getProperty("build.time", UNKNOWN));
    }
}
