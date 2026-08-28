package com.jxc.wefolio.service;

import com.jxc.wefolio.constant.WorkManualAuditConstants;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 作品最终轮人工审核编号生成器。 */
@Component
public class WorkManualAuditNoGenerator {

    /** 人工审核编号长度。 */
    private static final int MANUAL_AUDIT_NO_LENGTH = 22;

    /** 上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 时间段格式。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(SHANGHAI_ZONE);

    /** 排除易混淆字符后的随机字符表。 */
    private static final char[] RANDOM_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 随机段长度。 */
    private static final int RANDOM_LENGTH = 6;

    /** 业务时钟。 */
    private final Clock clock;

    /** 密码学安全随机数生成器。 */
    private final SecureRandom secureRandom;

    /** 创建生产环境生成器。 */
    public WorkManualAuditNoGenerator() {
        this(Clock.system(SHANGHAI_ZONE), new SecureRandom());
    }

    /**
     * 创建可测试生成器。
     *
     * @param clock 业务时钟
     * @param secureRandom 密码学安全随机数生成器
     */
    WorkManualAuditNoGenerator(Clock clock, SecureRandom secureRandom) {
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    /**
     * 生成固定 22 位人工审核编号。
     *
     * @return 人工审核编号
     */
    public String generate() {
        StringBuilder result = new StringBuilder(MANUAL_AUDIT_NO_LENGTH)
                .append(WorkManualAuditConstants.MANUAL_AUDIT_NO_PREFIX)
                .append(TIME_FORMATTER.format(clock.instant()));
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            result.append(RANDOM_ALPHABET[secureRandom.nextInt(RANDOM_ALPHABET.length)]);
        }
        return result.toString();
    }
}
