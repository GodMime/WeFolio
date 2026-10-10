package com.jxc.wefolio.service.portfolioai;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** AI 任务与 Job 共用上海业务时间口径，不依赖 Runtime JVM 默认时区。 */
public final class PortfolioAiTime {
    /** 与 Job 执行器和数据库 DATETIME(3) 一致的业务时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** 当前时刻来源；转换业务时间时不使用此时钟的时区。 */
    private static final Clock CLOCK = Clock.systemUTC();

    /** 时间工具不允许实例化。 */
    private PortfolioAiTime() { }

    /** 为创建、取消、期限和保留期提供一致的当前业务时间。 */
    public static LocalDateTime now() { return now(CLOCK); }

    /** 使用指定时刻来源验证跨日边界及不同运行环境的同一时间口径。 */
    static LocalDateTime now(Clock clock) { return LocalDateTime.now(clock.withZone(BUSINESS_ZONE)); }
}
