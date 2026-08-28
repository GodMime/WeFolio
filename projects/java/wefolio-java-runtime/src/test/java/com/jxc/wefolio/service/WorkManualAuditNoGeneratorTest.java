package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 作品人工审核编号生成器测试。 */
class WorkManualAuditNoGeneratorTest {

    /** 上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 生成结果必须使用上海时间和指定随机字符表。 */
    @Test
    void generateShouldUseShanghaiTimeAndExpectedRandomAlphabet() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T07:30:42Z"), SHANGHAI_ZONE);
        SecureRandom secureRandom = mock(SecureRandom.class, withSettings().withoutAnnotations());
        when(secureRandom.nextInt(32)).thenReturn(0, 1, 2, 3, 4, 5);
        WorkManualAuditNoGenerator generator = new WorkManualAuditNoGenerator(clock, secureRandom);

        assertThat(generator.generate()).isEqualTo("WA20260827153042ABCDEF");
    }

    /** 同一秒内连续生成时必须每次重新生成随机段。 */
    @Test
    void generateShouldRefreshRandomSuffixForEachCallWithinSameSecond() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T07:30:42Z"), SHANGHAI_ZONE);
        SecureRandom secureRandom = mock(SecureRandom.class, withSettings().withoutAnnotations());
        when(secureRandom.nextInt(32)).thenReturn(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        WorkManualAuditNoGenerator generator = new WorkManualAuditNoGenerator(clock, secureRandom);

        assertThat(generator.generate()).isEqualTo("WA20260827153042ABCDEF");
        assertThat(generator.generate()).isEqualTo("WA20260827153042GHJKLM");
    }

    /** 随机字符表末尾索引必须生成字符 9。 */
    @Test
    void generateShouldUseLastRandomAlphabetCharacter() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T07:30:42Z"), SHANGHAI_ZONE);
        SecureRandom secureRandom = mock(SecureRandom.class, withSettings().withoutAnnotations());
        when(secureRandom.nextInt(32)).thenReturn(31, 31, 31, 31, 31, 31);
        WorkManualAuditNoGenerator generator = new WorkManualAuditNoGenerator(clock, secureRandom);

        assertThat(generator.generate()).isEqualTo("WA20260827153042999999");
    }
}
