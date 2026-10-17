package com.jxc.wefolio.job.repo;

/** AI 任务 SQL 统一业务时钟表达式，短事务和租约使用同一口径。 */
public final class PortfolioAiDatabaseTime {
    /** 使用 UTC 加固定八小时，不依赖 MySQL 会话时区或时区表；上海无夏令时。 */
    public static final String NOW_SQL = "TIMESTAMPADD(HOUR, 8, UTC_TIMESTAMP(3))";

    /** 时钟表达式无需实例化。 */
    private PortfolioAiDatabaseTime() { }
}
