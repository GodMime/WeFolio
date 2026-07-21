package com.jxc.wefolio.service;

import com.jxc.wefolio.config.PortfolioOpenPerformanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 作品集打开分段耗时汇总日志测试。 */
class PortfolioOpenPerformanceLoggerTest {

    @Test
    void maintenanceShouldKeepUnexecutedWechatAndVisitPhasesNull() {
        List<PortfolioOpenPerformanceLogger.Report> reports = new ArrayList<>();
        PortfolioOpenPerformanceLogger logger = logger(1D, reports);
        PortfolioOpenPerformanceLogger.Trace trace = logger.start(
                PortfolioOpenPerformanceLogger.PortfolioType.TEAM);

        trace.measure(PortfolioOpenPerformanceLogger.Phase.PORTFOLIO_LOOKUP, () -> "portfolio");
        trace.measure(PortfolioOpenPerformanceLogger.Phase.POINT_GATE, () -> true);
        trace.measure(PortfolioOpenPerformanceLogger.Phase.RENDER, () -> "maintenance-render");
        trace.outcome(PortfolioOpenPerformanceLogger.Outcome.MAINTENANCE);
        trace.finish(null);

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.outcome()).isEqualTo(PortfolioOpenPerformanceLogger.Outcome.MAINTENANCE);
            assertThat(report.portfolioLookupMs()).isNotNull().isGreaterThanOrEqualTo(0L);
            assertThat(report.pointGateMs()).isNotNull().isGreaterThanOrEqualTo(0L);
            assertThat(report.renderMs()).isNotNull().isGreaterThanOrEqualTo(0L);
            assertThat(report.wechatLoginMs()).isNull();
            assertThat(report.visitorPersistMs()).isNull();
            assertThat(report.visitWriteMs()).isNull();
        });
    }

    @Test
    void timeoutFailureShouldAlwaysEmitWarnOutcomeWithoutChangingException() {
        List<PortfolioOpenPerformanceLogger.Report> reports = new ArrayList<>();
        PortfolioOpenPerformanceLogger logger = logger(0D, reports);
        PortfolioOpenPerformanceLogger.Trace trace = logger.start(
                PortfolioOpenPerformanceLogger.PortfolioType.PERSONAL);
        ResourceAccessException timeout = new ResourceAccessException(
                "微信读取超时", new HttpTimeoutException("request timed out"));

        assertThatThrownBy(() -> trace.measure(
                PortfolioOpenPerformanceLogger.Phase.WECHAT_LOGIN,
                () -> {
                    throw timeout;
                })).isSameAs(timeout);
        trace.finish(timeout);

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.outcome()).isEqualTo(PortfolioOpenPerformanceLogger.Outcome.WECHAT_TIMEOUT);
            assertThat(report.warn()).isTrue();
            assertThat(report.wechatLoginMs()).isNotNull();
            assertThat(report.visitorPersistMs()).isNull();
        });
    }

    @Test
    void unsampledFastSuccessShouldNotEmitReport() {
        List<PortfolioOpenPerformanceLogger.Report> reports = new ArrayList<>();
        PortfolioOpenPerformanceLogger logger = logger(0D, reports);
        PortfolioOpenPerformanceLogger.Trace trace = logger.start(
                PortfolioOpenPerformanceLogger.PortfolioType.PERSONAL);

        trace.outcome(PortfolioOpenPerformanceLogger.Outcome.SUCCESS);
        trace.finish(null);

        assertThat(reports).isEmpty();
    }

    /** 创建每次读取推进一毫秒的可控日志器。 */
    private PortfolioOpenPerformanceLogger logger(
            double sampleRate,
            List<PortfolioOpenPerformanceLogger.Report> reports
    ) {
        PortfolioOpenPerformanceProperties properties = new PortfolioOpenPerformanceProperties();
        properties.setSlowThreshold(Duration.ofSeconds(2));
        properties.setNormalSampleRate(sampleRate);
        AtomicLong nanos = new AtomicLong();
        return new PortfolioOpenPerformanceLogger(
                properties,
                () -> nanos.getAndAdd(Duration.ofMillis(1).toNanos()),
                () -> 0.5D,
                reports::add);
    }
}
