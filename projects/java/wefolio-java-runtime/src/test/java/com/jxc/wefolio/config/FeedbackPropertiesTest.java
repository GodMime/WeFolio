package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 问题反馈配置测试，固定环境变量和超时契约。 */
class FeedbackPropertiesTest {

    /** Apache HttpClient5 必须显式声明且由 Spring Boot 管理版本。 */
    @Test
    void pomDeclaresBootManagedApacheHttpClient5()
            throws IOException, ParserConfigurationException, SAXException {
        NodeList dependencies = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile())
                .getElementsByTagName("dependency");

        Element httpClientDependency = null;
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            if ("org.apache.httpcomponents.client5".equals(textOf(dependency, "groupId"))
                    && "httpclient5".equals(textOf(dependency, "artifactId"))) {
                httpClientDependency = dependency;
                break;
            }
        }

        assertThat(httpClientDependency).isNotNull();
        assertThat(httpClientDependency.getElementsByTagName("version").getLength()).isZero();
    }

    /** 主配置必须映射飞书配置、状态接口公开地址和固定超时。 */
    @Test
    void yamlMapsFeedbackEnvironmentVariablesAndTimeouts() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String testYaml = Files.readString(Path.of("src/test/resources/application-test.yml"));

        assertThat(yaml)
                .contains("webhook-url: ${FEISHU_FEEDBACK_WEBHOOK_URL:}")
                .contains("webhook-secret: ${FEISHU_FEEDBACK_WEBHOOK_SECRET:}")
                .contains("status-api-base-url: ${FEEDBACK_STATUS_API_BASE_URL:")
                .contains("connect-timeout: 2s")
                .contains("read-timeout: 3s");
        assertThat(testYaml)
                .contains("webhook-url: https://open.feishu.cn/open-apis/bot/v2/hook/test-feedback-webhook")
                .contains("webhook-secret: test-feedback-webhook-secret")
                .contains("status-api-base-url: https://api.test.wefolio.example");
    }

    /** 配置对象默认值必须与生产固定超时一致。 */
    @Test
    void propertiesUseFixedDefaultTimeouts() {
        FeedbackProperties properties = new FeedbackProperties();

        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getStatusApiBaseUrl())
                .isEqualTo("https://api.we-folio.dingchenyong.top");
    }

    /** 配置对象的字符串表达不得包含任何反馈密钥。 */
    @Test
    void propertiesToStringDoesNotExposeSensitiveValues() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setWebhookUrl("https://open.feishu.cn/hook/sentinel-webhook-token");
        properties.setWebhookSecret("sentinel-webhook-secret");
        properties.setStatusApiBaseUrl("https://api.test.wefolio.example");

        assertThat(properties.toString())
                .doesNotContain("sentinel-webhook-token")
                .doesNotContain("sentinel-webhook-secret");
    }

    /** 连接和读取超时都必须为正数。 */
    @Test
    void propertiesRejectNonPositiveTimeouts() {
        FeedbackProperties properties = new FeedbackProperties();

        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setConnectTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setReadTimeout(Duration.ofMillis(-1)));
    }

    /** 读取 Maven 依赖节点的直接子元素文本。 */
    private String textOf(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        return children.getLength() == 0 ? null : children.item(0).getTextContent().trim();
    }

}
