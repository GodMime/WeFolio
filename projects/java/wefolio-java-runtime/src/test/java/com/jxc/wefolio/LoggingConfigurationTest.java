package com.jxc.wefolio;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志配置测试 — 固定 runtime 工程错误日志单独落盘并按日归档。
 */
class LoggingConfigurationTest {

    /** Logback 主配置文件路径 */
    private static final Path LOGBACK_CONFIG_PATH = Path.of("src/main/resources/logback-spring.xml");

    /** 错误日志 appender 名称 */
    private static final String ERROR_FILE_APPENDER_NAME = "ERROR_FILE";

    /** appender 节点名 */
    private static final String APPENDER_TAG = "appender";

    /** root 节点名 */
    private static final String ROOT_TAG = "root";

    /** appender-ref 节点名 */
    private static final String APPENDER_REF_TAG = "appender-ref";

    /** filter 节点名 */
    private static final String FILTER_TAG = "filter";

    /** level 节点名 */
    private static final String LEVEL_TAG = "level";

    /** file 节点名 */
    private static final String FILE_TAG = "file";

    /** rollingPolicy 节点名 */
    private static final String ROLLING_POLICY_TAG = "rollingPolicy";

    /** fileNamePattern 节点名 */
    private static final String FILE_NAME_PATTERN_TAG = "fileNamePattern";

    /** maxHistory 节点名 */
    private static final String MAX_HISTORY_TAG = "maxHistory";

    /** Logback 滚动文件 appender 类名 */
    private static final String ROLLING_FILE_APPENDER_CLASS = "ch.qos.logback.core.rolling.RollingFileAppender";

    /** Logback 按时间滚动策略类名 */
    private static final String TIME_BASED_ROLLING_POLICY_CLASS = "ch.qos.logback.core.rolling.TimeBasedRollingPolicy";

    /** Logback 阈值过滤器类名 */
    private static final String THRESHOLD_FILTER_CLASS = "ch.qos.logback.classic.filter.ThresholdFilter";

    /** 当前错误日志文件 */
    private static final String ERROR_LOG_FILE = "${LOG_PATH}/error.log";

    /** 按日归档的错误日志文件名模式 */
    private static final String DAILY_ERROR_LOG_FILE_PATTERN = "${LOG_PATH}/error.%d{yyyy-MM-dd}.log";

    /** 日志保留天数 */
    private static final String RETENTION_DAYS = "30";

    /** 错误日志级别 */
    private static final String ERROR_LEVEL = "ERROR";

    /**
     * 错误日志应单独写入 error.log，只接收 ERROR 级别，并按天切分。
     *
     * @throws Exception 解析 Logback 配置失败时抛出
     */
    @Test
    void errorAppenderShouldWriteOnlyErrorAndRollByDay() throws Exception {
        Document document = readLogbackConfig();
        Element errorAppender = findNamedAppender(document, ERROR_FILE_APPENDER_NAME);
        Element filter = findSingleChild(errorAppender, FILTER_TAG);
        Element rollingPolicy = findSingleChild(errorAppender, ROLLING_POLICY_TAG);

        assertThat(errorAppender.getAttribute("class")).isEqualTo(ROLLING_FILE_APPENDER_CLASS);
        assertThat(childText(errorAppender, FILE_TAG)).isEqualTo(ERROR_LOG_FILE);
        assertThat(filter.getAttribute("class")).isEqualTo(THRESHOLD_FILTER_CLASS);
        assertThat(childText(filter, LEVEL_TAG)).isEqualTo(ERROR_LEVEL);
        assertThat(rollingPolicy.getAttribute("class")).isEqualTo(TIME_BASED_ROLLING_POLICY_CLASS);
        assertThat(childText(rollingPolicy, FILE_NAME_PATTERN_TAG)).isEqualTo(DAILY_ERROR_LOG_FILE_PATTERN);
        assertThat(childText(rollingPolicy, MAX_HISTORY_TAG)).isEqualTo(RETENTION_DAYS);
        assertThat(rootAppenderRefs(document)).contains(ERROR_FILE_APPENDER_NAME);
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

    /**
     * 读取根日志引用的 appender 名称。
     *
     * @param document Logback 配置文档
     * @return 根日志 appender 引用名称集合
     */
    private Iterable<String> rootAppenderRefs(Document document) {
        Element root = (Element) document.getElementsByTagName(ROOT_TAG).item(0);
        NodeList refs = root.getElementsByTagName(APPENDER_REF_TAG);
        List<String> names = new ArrayList<>();
        for (int index = 0; index < refs.getLength(); index++) {
            Element ref = (Element) refs.item(index);
            names.add(ref.getAttribute("ref"));
        }
        return names;
    }
}
