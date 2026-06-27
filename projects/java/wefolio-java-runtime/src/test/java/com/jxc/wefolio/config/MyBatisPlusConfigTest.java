package com.jxc.wefolio.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis Plus 配置测试 — 固定 SQL 日志开关默认关闭，可按配置开启。
 */
class MyBatisPlusConfigTest {

    @Test
    void sqlLoggingCustomizerDisablesSqlStdoutByDefault() {
        MyBatisPlusConfig config = new MyBatisPlusConfig();
        MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();

        config.mybatisSqlLoggingCustomizer(false).customize(mybatisConfiguration);

        assertThat(mybatisConfiguration.getLogImpl()).isEqualTo(NoLoggingImpl.class);
    }

    @Test
    void sqlLoggingCustomizerCanEnableStdoutSqlLogs() {
        MyBatisPlusConfig config = new MyBatisPlusConfig();
        MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();

        config.mybatisSqlLoggingCustomizer(true).customize(mybatisConfiguration);

        assertThat(mybatisConfiguration.getLogImpl()).isEqualTo(StdOutImpl.class);
    }
}
