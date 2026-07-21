package com.jxc.wefolio.service;

import com.jxc.wefolio.config.PortfolioOpenPerformanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 个人和团队作品集打开链路的低开销分段耗时汇总日志器。 */
@Slf4j
@Component
public class PortfolioOpenPerformanceLogger {

    /** 日志配置。 */
    private final PortfolioOpenPerformanceProperties properties;

    /** 单调纳秒时钟。 */
    private final LongSupplier nanoTime;

    /** 正常请求采样随机数。 */
    private final DoubleSupplier random;

    /** 汇总报告接收器。 */
    private final Consumer<Report> reportConsumer;

    /** 创建生产日志器。 */
    @Autowired
    public PortfolioOpenPerformanceLogger(PortfolioOpenPerformanceProperties properties) {
        this(properties, System::nanoTime, Math::random, null);
    }

    /** 创建可控测试日志器。 */
    PortfolioOpenPerformanceLogger(
            PortfolioOpenPerformanceProperties properties,
            LongSupplier nanoTime,
            DoubleSupplier random,
            Consumer<Report> reportConsumer
    ) {
        this.properties = properties;
        this.nanoTime = nanoTime;
        this.random = random;
        this.reportConsumer = reportConsumer == null ? this::writeLog : reportConsumer;
    }

    /** 开始一次作品集打开链路计时。 */
    public Trace start(PortfolioType portfolioType) {
        return new Trace(portfolioType, nanoTime.getAsLong());
    }

    /** 作品集类型。 */
    public enum PortfolioType {
        PERSONAL,
        TEAM
    }

    /** 可观测阶段。 */
    public enum Phase {
        PORTFOLIO_LOOKUP,
        POINT_GATE,
        WECHAT_LOGIN,
        VISITOR_PERSIST,
        RENDER,
        VISIT_WRITE
    }

    /** 固定结果分类。 */
    public enum Outcome {
        SUCCESS,
        OWNER_SELF,
        MAINTENANCE,
        PORTFOLIO_UNAVAILABLE,
        WECHAT_TIMEOUT,
        WECHAT_FAILED,
        VISITOR_PERSIST_FAILED,
        RENDER_FAILED,
        VISIT_WRITE_FAILED,
        UNEXPECTED_FAILED
    }

    /** 单次请求追踪器，每个请求最多输出一条汇总日志。 */
    public final class Trace {

        /** 作品集类型。 */
        private final PortfolioType portfolioType;

        /** 总计时起点。 */
        private final long startedAtNanos;

        /** 各阶段累计耗时。 */
        private final Map<Phase, Long> phaseNanos = new EnumMap<>(Phase.class);

        /** 异常发生阶段。 */
        private Phase failedPhase;

        /** 显式结果。 */
        private Outcome explicitOutcome;

        /** 是否已结束。 */
        private boolean finished;

        /** 创建单次追踪器。 */
        private Trace(PortfolioType portfolioType, long startedAtNanos) {
            this.portfolioType = portfolioType;
            this.startedAtNanos = startedAtNanos;
        }

        /** 计量一个有返回值的阶段，原异常不转换也不吞掉。 */
        public <T> T measure(Phase phase, Supplier<T> action) {
            long phaseStartedAt = nanoTime.getAsLong();
            try {
                return action.get();
            } catch (RuntimeException | Error exception) {
                failedPhase = phase;
                throw exception;
            } finally {
                long elapsed = elapsedNanos(phaseStartedAt, nanoTime.getAsLong());
                phaseNanos.merge(phase, elapsed, Long::sum);
            }
        }

        /** 计量一个无返回值的阶段。 */
        public void measure(Phase phase, Runnable action) {
            measure(phase, () -> {
                action.run();
                return null;
            });
        }

        /** 设置业务结果分类。 */
        public void outcome(Outcome outcome) {
            this.explicitOutcome = outcome;
        }

        /** 结束追踪并按慢请求、失败和采样规则输出一次汇总。 */
        public void finish(Throwable failure) {
            if (finished) {
                return;
            }
            finished = true;
            long totalNanos = elapsedNanos(startedAtNanos, nanoTime.getAsLong());
            Outcome outcome = resolveOutcome(failure);
            boolean failed = failure != null || isFailureOutcome(outcome);
            boolean warn = failed || totalNanos >= properties.getSlowThreshold().toNanos();
            if (!warn && random.getAsDouble() >= properties.getNormalSampleRate()) {
                return;
            }
            reportConsumer.accept(new Report(
                    portfolioType,
                    durationMs(Phase.PORTFOLIO_LOOKUP),
                    durationMs(Phase.POINT_GATE),
                    durationMs(Phase.WECHAT_LOGIN),
                    durationMs(Phase.VISITOR_PERSIST),
                    durationMs(Phase.RENDER),
                    durationMs(Phase.VISIT_WRITE),
                    Duration.ofNanos(totalNanos).toMillis(),
                    outcome,
                    warn));
        }

        /** 根据显式结果、失败阶段和异常类型确定固定分类。 */
        private Outcome resolveOutcome(Throwable failure) {
            if (explicitOutcome != null) {
                return explicitOutcome;
            }
            if (failedPhase == null) {
                return Outcome.UNEXPECTED_FAILED;
            }
            return switch (failedPhase) {
                case PORTFOLIO_LOOKUP -> Outcome.PORTFOLIO_UNAVAILABLE;
                case WECHAT_LOGIN -> isTimeout(failure) ? Outcome.WECHAT_TIMEOUT : Outcome.WECHAT_FAILED;
                case VISITOR_PERSIST -> Outcome.VISITOR_PERSIST_FAILED;
                case RENDER -> Outcome.RENDER_FAILED;
                case VISIT_WRITE -> Outcome.VISIT_WRITE_FAILED;
                case POINT_GATE -> Outcome.UNEXPECTED_FAILED;
            };
        }

        /** 将已记录阶段换算为毫秒，未执行阶段返回 null。 */
        private Long durationMs(Phase phase) {
            Long nanos = phaseNanos.get(phase);
            return nanos == null ? null : Duration.ofNanos(nanos).toMillis();
        }
    }

    /** 不允许系统时钟异常造成负耗时。 */
    private long elapsedNanos(long startedAt, long endedAt) {
        return Math.max(0L, endedAt - startedAt);
    }

    /** 判断异常链中是否存在明确的超时异常。 */
    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 判断结果是否为失败。 */
    private boolean isFailureOutcome(Outcome outcome) {
        return outcome != Outcome.SUCCESS && outcome != Outcome.OWNER_SELF && outcome != Outcome.MAINTENANCE;
    }

    /** 输出不包含请求标识和敏感字段的固定汇总日志。 */
    private void writeLog(Report report) {
        String format = "作品集打开耗时 portfolioType={} portfolioLookupMs={} pointGateMs={} "
                + "wechatLoginMs={} visitorPersistMs={} renderMs={} visitWriteMs={} totalMs={} outcome={}";
        if (report.warn()) {
            log.warn(format, report.portfolioType(), report.portfolioLookupMs(), report.pointGateMs(),
                    report.wechatLoginMs(), report.visitorPersistMs(), report.renderMs(),
                    report.visitWriteMs(), report.totalMs(), report.outcome());
        } else {
            log.info(format, report.portfolioType(), report.portfolioLookupMs(), report.pointGateMs(),
                    report.wechatLoginMs(), report.visitorPersistMs(), report.renderMs(),
                    report.visitWriteMs(), report.totalMs(), report.outcome());
        }
    }

    /** 一次打开请求的脱敏汇总数据。 */
    record Report(
            PortfolioType portfolioType,
            Long portfolioLookupMs,
            Long pointGateMs,
            Long wechatLoginMs,
            Long visitorPersistMs,
            Long renderMs,
            Long visitWriteMs,
            long totalMs,
            Outcome outcome,
            boolean warn
    ) {
    }
}
