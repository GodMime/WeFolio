package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志配置测试 — 固定 job 工程日志文件按日归档并只保留 30 天。
 */
class LoggingConfigurationTest {

    /** Logback 主配置文件路径 */
    private static final Path LOGBACK_CONFIG_PATH = Path.of("src/main/resources/logback-spring.xml");

    /** 文件日志 appender 名称 */
    private static final String FILE_APPENDER_NAME = "FILE";

    /** appender 节点名 */
    private static final String APPENDER_TAG = "appender";

    /** rollingPolicy 节点名 */
    private static final String ROLLING_POLICY_TAG = "rollingPolicy";

    /** fileNamePattern 节点名 */
    private static final String FILE_NAME_PATTERN_TAG = "fileNamePattern";

    /** maxHistory 节点名 */
    private static final String MAX_HISTORY_TAG = "maxHistory";

    /** cleanHistoryOnStart 节点名 */
    private static final String CLEAN_HISTORY_ON_START_TAG = "cleanHistoryOnStart";

    /** Logback 滚动文件 appender 类名 */
    private static final String ROLLING_FILE_APPENDER_CLASS = "ch.qos.logback.core.rolling.RollingFileAppender";

    /** Logback 按时间滚动策略类名 */
    private static final String TIME_BASED_ROLLING_POLICY_CLASS = "ch.qos.logback.core.rolling.TimeBasedRollingPolicy";

    /** 按日归档的日志文件名模式 */
    private static final String DAILY_LOG_FILE_PATTERN = "${LOG_PATH}/application.%d{yyyy-MM-dd}.log";

    /** 日志保留天数 */
    private static final String RETENTION_DAYS = "30";

    /** 启动时清理过期归档的开关值 */
    private static final String CLEAN_HISTORY_ON_START_VALUE = "true";

    /**
     * 文件日志应按天切分，并限制只保留 30 天归档。
     *
     * @throws Exception 解析 Logback 配置失败时抛出
     */
    @Test
    void fileAppenderShouldRollByDayAndRetainThirtyDays() throws Exception {
        Document document = readLogbackConfig();
        Element fileAppender = findNamedAppender(document, FILE_APPENDER_NAME);
        Element rollingPolicy = findSingleChild(fileAppender, ROLLING_POLICY_TAG);

        assertThat(fileAppender.getAttribute("class")).isEqualTo(ROLLING_FILE_APPENDER_CLASS);
        assertThat(rollingPolicy.getAttribute("class")).isEqualTo(TIME_BASED_ROLLING_POLICY_CLASS);
        assertThat(childText(rollingPolicy, FILE_NAME_PATTERN_TAG)).isEqualTo(DAILY_LOG_FILE_PATTERN);
        assertThat(childText(rollingPolicy, MAX_HISTORY_TAG)).isEqualTo(RETENTION_DAYS);
        assertThat(childText(rollingPolicy, CLEAN_HISTORY_ON_START_TAG)).isEqualTo(CLEAN_HISTORY_ON_START_VALUE);
    }

    /**
     * 读取 Logback XML 配置。
     *
     * @return Logback 配置文档
     * @throws Exception 读取或解析 XML 失败时抛出
     */
    private Document readLogbackConfig() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(LOGBACK_CONFIG_PATH.toFile());
    }

    /**
     * 按名称查找 appender 节点。
     *
     * @param document Logback 配置文档
     * @param name     appender 名称
     * @return 匹配的 appender 节点
     */
    private Element findNamedAppender(Document document, String name) {
        NodeList appenders = document.getElementsByTagName(APPENDER_TAG);
        for (int index = 0; index < appenders.getLength(); index++) {
            Element appender = (Element) appenders.item(index);
            if (name.equals(appender.getAttribute("name"))) {
                return appender;
            }
        }
        throw new IllegalStateException("未找到日志 appender: " + name);
    }

    /**
     * 查找指定父节点下的唯一子节点。
     *
     * @param parent  父节点
     * @param tagName 子节点名称
     * @return 唯一子节点
     */
    private Element findSingleChild(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        assertThat(children.getLength()).isEqualTo(1);
        return (Element) children.item(0);
    }

    /**
     * 读取指定子节点文本。
     *
     * @param parent  父节点
     * @param tagName 子节点名称
     * @return 子节点文本
     */
    private String childText(Element parent, String tagName) {
        return findSingleChild(parent, tagName).getTextContent().trim();
    }
}
