package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态字典使用规范测试 — 防止业务服务重新引入硬编码状态值。
 */
class StatusDictionaryUsageTest {

    /**
     * 维护者登录服务不应硬编码 ACTIVE 状态，应通过字典常量读取。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void miniappAuthServiceUsesStatusDictionaries() throws Exception {
        String source = readSource("src/main/java/com/jxc/wefolio/service/MiniappAuthService.java");

        assertThat(source).doesNotContain("\"ACTIVE\"");
    }

    /**
     * 我的首页服务不应硬编码 ACTIVE 状态，也不应使用实体完全限定类名。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void mineDashboardServiceUsesStatusDictionariesAndImportsEntities() throws Exception {
        String source = readSource("src/main/java/com/jxc/wefolio/service/MineDashboardService.java");

        assertThat(source).doesNotContain("\"ACTIVE\"");
        assertThat(source).doesNotContain("Wrappers.lambdaQuery(com.jxc.wefolio.entity.WorkEntity.class)");
        assertThat(source).doesNotContain("Wrappers.lambdaQuery(com.jxc.wefolio.entity.PortfolioEntity.class)");
        assertThat(source).doesNotContain(".eq(com.jxc.wefolio.entity.WorkEntity::");
        assertThat(source).doesNotContain(".eq(com.jxc.wefolio.entity.PortfolioEntity::");
    }

    /**
     * 读取项目源码文件。
     *
     * @param path 源码相对路径
     * @return 源码内容
     * @throws Exception 读取源码失败时抛出异常
     */
    private String readSource(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
