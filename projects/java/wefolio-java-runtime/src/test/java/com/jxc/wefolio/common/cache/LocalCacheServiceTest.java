package com.jxc.wefolio.common.cache;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCacheServiceTest {

    @Test
    void productionServiceShouldNotExposeNoArgumentConstructor() {
        assertThat(LocalCacheService.class.getDeclaredConstructors())
                .noneMatch(constructor -> constructor.getParameterCount() == 0);
    }

    @Test
    void returnsCachedValueBeforeTtlExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T12:00:00Z"));
        LocalCacheService cacheService = new LocalCacheService(clock);

        cacheService.put("wechat:access-token", "token-123", Duration.ofMinutes(5));

        assertThat(cacheService.get("wechat:access-token", String.class)).contains("token-123");
    }

    @Test
    void removesCachedValueAfterTtlExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T12:00:00Z"));
        LocalCacheService cacheService = new LocalCacheService(clock);
        cacheService.put("wechat:access-token", "token-123", Duration.ofSeconds(1));

        clock.advance(Duration.ofSeconds(2));

        assertThat(cacheService.get("wechat:access-token", String.class)).isEmpty();
    }

    @Test
    void returnsEmptyWhenCachedValueTypeDoesNotMatch() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T12:00:00Z"));
        LocalCacheService cacheService = new LocalCacheService(clock);

        cacheService.put("count", 1L, Duration.ofMinutes(5));

        assertThat(cacheService.get("count", String.class)).isEmpty();
    }

    @Test
    void expiresEachEntryUsingItsOwnTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T12:00:00Z"));
        LocalCacheService cacheService = new LocalCacheService(clock);
        cacheService.put("short", "short-value", Duration.ofSeconds(1));
        cacheService.put("long", "long-value", Duration.ofMinutes(5));

        clock.advance(Duration.ofSeconds(2));

        assertThat(cacheService.get("short", String.class)).isEmpty();
        assertThat(cacheService.get("long", String.class)).contains("long-value");
    }

    @Test
    void boundsEntryCountByConfiguredMaximumSize() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T12:00:00Z"));
        LocalCacheService cacheService = new LocalCacheService(2L, clock);

        cacheService.put("one", "1", Duration.ofMinutes(5));
        cacheService.put("two", "2", Duration.ofMinutes(5));
        cacheService.put("three", "3", Duration.ofMinutes(5));
        cacheService.cleanUp();

        assertThat(cacheService.estimatedSize()).isLessThanOrEqualTo(2L);
    }

    /**
     * 可推进的测试时钟 — 用于验证缓存过期行为
     */
    private static class MutableClock extends Clock {

        /** 当前时刻 */
        private Instant instant;

        /**
         * 创建测试时钟
         *
         * @param instant 初始时刻
         */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 推进当前时刻
         *
         * @param duration 推进时长
         */
        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /**
         * 获取时区
         *
         * @return UTC 时区
         */
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        /**
         * 切换时区
         *
         * @param zone 目标时区
         * @return 当前测试时钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 获取当前时刻
         *
         * @return 当前时刻
         */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
