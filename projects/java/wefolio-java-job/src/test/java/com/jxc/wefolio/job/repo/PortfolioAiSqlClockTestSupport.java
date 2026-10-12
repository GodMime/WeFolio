package com.jxc.wefolio.job.repo;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** H2 不提供 MySQL UTC_TIMESTAMP，用独立 UTC 时钟模拟该函数以验证不同时区会话。 */
public final class PortfolioAiSqlClockTestSupport {
    /** 测试时钟函数无需实例化。 */
    private PortfolioAiSqlClockTestSupport() { }

    /** SQL 只调用毫秒精度，返回不受 JVM 或数据库会话时区影响的 UTC 日期时间。 */
    public static LocalDateTime utcTimestamp(int precision) {
        if (precision != 3) { throw new IllegalArgumentException("测试 UTC 时钟仅支持毫秒精度"); }
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
