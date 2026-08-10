package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Redis 集成测试 Maven 隔离结构测试。 */
class RedisIntegrationProfileStructureTest {

    /** runtime 模块 Maven 配置路径。 */
    private static final Path POM = Path.of("pom.xml");

    /** 验证真实 Redis 用例只由显式 Failsafe profile 执行。 */
    @Test
    void failsafeShouldOnlyRunRedisItFromExplicitProfile() throws IOException {
        String pom = Files.readString(POM);

        assertThat(pom)
                .contains("<id>redis-integration-test</id>")
                .contains("<artifactId>maven-failsafe-plugin</artifactId>")
                .contains("<include>**/RedisConnectionIT.java</include>")
                .contains("<goal>integration-test</goal>")
                .contains("<goal>verify</goal>");
    }
}
