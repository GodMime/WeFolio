package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime 服务监听地址配置测试。 */
class ServerBindingConfigurationTest {

    /**
     * 验证未配置节点地址时仍默认仅监听本机回环地址。
     */
    @Test
    void serverAddressShouldDefaultToLoopback() throws IOException {
        StandardEnvironment environment = loadApplicationEnvironment(Map.of());

        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    /**
     * 验证部署节点可以通过环境变量覆盖服务监听地址。
     */
    @Test
    void serverAddressShouldResolveFromDeploymentEnvironment() throws IOException {
        StandardEnvironment environment = loadApplicationEnvironment(
                Map.of("SERVER_ADDRESS", "10.0.4.7"));

        assertThat(environment.getProperty("server.address")).isEqualTo("10.0.4.7");
    }

    /**
     * 加载真实 application.yml，并把部署环境变量置于最高优先级。
     *
     * @param deploymentProperties 部署环境变量
     * @return 可解析应用配置的 Spring 环境
     * @throws IOException application.yml 读取失败
     */
    private StandardEnvironment loadApplicationEnvironment(
            Map<String, Object> deploymentProperties) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("deploymentEnvironment", deploymentProperties));

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("applicationYaml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
