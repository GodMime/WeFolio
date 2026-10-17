package com.jxc.wefolio.service.portfolioai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止 Runtime 与 Job 在不同 JVM 时区下提前超时或延后过期。 */
class PortfolioAiTimeTest {
    /** UTC、上海及纽约时钟在同一瞬间必须得到相同的上海跨日时间。 */
    @Test
    void businessTimeIgnoresClockZoneAcrossMidnight() {
        Instant instant = Instant.parse("2026-10-09T18:00:00Z");
        LocalDateTime expected = LocalDateTime.of(2026, 10, 10, 2, 0);
        for (ZoneId zone : List.of(ZoneOffset.UTC, ZoneId.of("Asia/Shanghai"), ZoneId.of("America/New_York"))) {
            assertThat(PortfolioAiTime.now(Clock.fixed(instant, zone))).isEqualTo(expected);
        }
    }
}
