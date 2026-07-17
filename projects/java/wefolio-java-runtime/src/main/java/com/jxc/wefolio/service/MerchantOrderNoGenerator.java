package com.jxc.wefolio.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 微信支付商户订单号生成器。
 */
@Component
public class MerchantOrderNoGenerator {

    /** 商户订单号固定前缀。 */
    private static final String ORDER_PREFIX = "WFR";

    /** 上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 时间段格式。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(SHANGHAI_ZONE);

    /** 排除易混淆字符后的随机字符表。 */
    private static final char[] RANDOM_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 随机段长度。 */
    private static final int RANDOM_LENGTH = 12;

    /** 业务时钟。 */
    private final Clock clock;

    /** 密码学安全随机数生成器。 */
    private final SecureRandom secureRandom;

    /**
     * 创建生产环境生成器。
     */
    public MerchantOrderNoGenerator() {
        this(Clock.system(SHANGHAI_ZONE), new SecureRandom());
    }

    /**
     * 创建可测试生成器。
     *
     * @param clock 业务时钟
     * @param secureRandom 密码学安全随机数生成器
     */
    MerchantOrderNoGenerator(Clock clock, SecureRandom secureRandom) {
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    /**
     * 生成固定 32 位商户订单号。
     *
     * @return 商户订单号
     */
    public String generate() {
        StringBuilder result = new StringBuilder(32)
                .append(ORDER_PREFIX)
                .append(TIME_FORMATTER.format(clock.instant()));
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            result.append(RANDOM_ALPHABET[secureRandom.nextInt(RANDOM_ALPHABET.length)]);
        }
        return result.toString();
    }
}
