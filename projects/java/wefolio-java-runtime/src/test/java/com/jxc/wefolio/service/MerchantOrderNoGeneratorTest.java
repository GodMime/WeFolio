package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商户订单号生成器测试。
 */
class MerchantOrderNoGeneratorTest {

    /** 上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void generateShouldUseShanghaiTimeAndWechatCompatibleFixedLengthFormat() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T07:30:00.123Z"), SHANGHAI_ZONE);
        MerchantOrderNoGenerator generator = new MerchantOrderNoGenerator(clock, new SecureRandom());

        String merchantOrderNo = generator.generate();

        assertThat(merchantOrderNo)
                .hasSize(32)
                .startsWith("WFR20260717153000123")
                .matches("^WFR[0-9]{17}[A-HJ-NP-Z2-9]{12}$");
    }

    @Test
    void generateShouldCreateDifferentRandomSuffixes() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T07:30:00.123Z"), SHANGHAI_ZONE);
        MerchantOrderNoGenerator generator = new MerchantOrderNoGenerator(clock, new SecureRandom());

        assertThat(generator.generate()).isNotEqualTo(generator.generate());
    }
}
