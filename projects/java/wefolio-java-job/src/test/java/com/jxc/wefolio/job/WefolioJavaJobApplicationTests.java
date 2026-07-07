package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用启动测试 — 验证后台任务工程的 Spring 上下文可以正常加载。
 */
@SpringBootTest(classes = WefolioJavaJobApplication.class)
@ActiveProfiles("test")
class WefolioJavaJobApplicationTests {

    /**
     * Spring 上下文加载成功即视为通过。
     */
    @Test
    void contextLoads() {
    }
}
