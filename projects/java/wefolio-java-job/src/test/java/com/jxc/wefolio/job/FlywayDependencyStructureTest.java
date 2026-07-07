package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖结构测试 — 确认后台任务工程不引入 Flyway。
 */
class FlywayDependencyStructureTest {

    /** Maven 坐标中的 Flyway groupId */
    private static final String FLYWAY_GROUP_ID = "<groupId>org.flywaydb</groupId>";

    /** Spring Boot Flyway 配置前缀 */
    private static final String FLYWAY_CONFIGURATION_PREFIX = "flyway:";

    /**
     * pom.xml 不应声明 Flyway 依赖。
     *
     * @throws Exception 读取文件失败时抛出
     */
    @Test
    void pomDoesNotDeclareFlywayDependency() throws Exception {
        String pomContent = Files.readString(Path.of("pom.xml"));

        assertThat(pomContent).doesNotContain(FLYWAY_GROUP_ID);
    }

    /**
     * 主配置和测试配置都不应声明 Flyway 配置项。
     *
     * @throws Exception 读取文件失败时抛出
     */
    @Test
    void configurationsDoNotDeclareFlywayConfiguration() throws Exception {
        String mainConfiguration = Files.readString(Path.of("src/main/resources/application.yml"));
        String testConfiguration = Files.readString(Path.of("src/test/resources/application-test.yml"));

        assertThat(mainConfiguration).doesNotContain(FLYWAY_CONFIGURATION_PREFIX);
        assertThat(testConfiguration).doesNotContain(FLYWAY_CONFIGURATION_PREFIX);
    }
}
