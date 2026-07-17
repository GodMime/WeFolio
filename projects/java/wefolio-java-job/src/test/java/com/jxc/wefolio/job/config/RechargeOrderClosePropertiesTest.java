package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期充值订单关闭配置测试。
 */
class RechargeOrderClosePropertiesTest {

    @Test
    void shouldProvideSafeDefaultLimits() {
        RechargeOrderCloseProperties properties = new RechargeOrderCloseProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getCron()).isEqualTo("30 * * * * ?");
        assertThat(properties.getZone()).isEqualTo("Asia/Shanghai");
        assertThat(properties.getBatchSize()).isEqualTo(200);
        assertThat(properties.getMaxBatches()).isEqualTo(10);
    }

    @Test
    void applicationYamlShouldBindAllCloseTaskEnvironmentVariables() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml)
                .contains("enabled: ${RECHARGE_ORDER_CLOSE_ENABLED:true}")
                .contains("cron: \"${RECHARGE_ORDER_CLOSE_CRON:30 * * * * ?}\"")
                .contains("zone: ${RECHARGE_ORDER_CLOSE_ZONE:Asia/Shanghai}")
                .contains("batch-size: ${RECHARGE_ORDER_CLOSE_BATCH_SIZE:200}")
                .contains("max-batches: ${RECHARGE_ORDER_CLOSE_MAX_BATCHES:10}");
    }
}
