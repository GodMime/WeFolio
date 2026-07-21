package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dto.WorkStorageBillingExecuteRequest;
import com.jxc.wefolio.job.dto.WorkStorageBillingExecutionResponse;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.Submission;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.TriggerSource;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** 作品存储月度结算手工执行应用服务。 */
@Service
public class WorkStorageBillingExecutionService {

    /** 上海时区 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 首个可结算账期 */
    private static final YearMonth FIRST_BILLING_MONTH = YearMonth.of(2026, 6);

    /** 严格账期格式 */
    private static final DateTimeFormatter BILLING_MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    /** 月份格式正则 */
    private static final String BILLING_MONTH_PATTERN = "\\d{4}-\\d{2}";

    /** 执行结果固定语义 */
    private static final String INVALID_SECRET_MESSAGE = "后台积分密钥无效";
    private static final String ACCEPTED_MESSAGE = "作品存储结算任务已提交";
    private static final String ALREADY_RUNNING_MESSAGE = "作品存储结算任务正在执行，本次未重复提交";
    private static final String INVALID_FORMAT_MESSAGE = "账期格式必须为 yyyy-MM";
    private static final String TOO_EARLY_MESSAGE = "账期不得早于 2026-06";
    private static final String NOT_HISTORICAL_MESSAGE = "仅允许执行当前月之前的账期";

    /** 后台积分密钥校验器 */
    private final AdminPointSecretValidator secretValidator;

    /** 异步执行协调器 */
    private final WorkStorageBillingExecutionCoordinator coordinator;

    /** 可替换时钟 */
    private final Clock clock;

    /** 创建手工执行应用服务。 */
    public WorkStorageBillingExecutionService(
            AdminPointSecretValidator secretValidator,
            WorkStorageBillingExecutionCoordinator coordinator,
            Clock clock
    ) {
        this.secretValidator = secretValidator;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    /** 校验并异步提交指定或默认账期结算。 */
    public ExecutionResult execute(String secret, WorkStorageBillingExecuteRequest request) {
        if (!secretValidator.isValid(secret)) {
            return ExecutionResult.failure(ExecutionOutcome.UNAUTHORIZED, INVALID_SECRET_MESSAGE);
        }
        LocalDate billingMonth;
        try {
            billingMonth = resolveBillingMonth(request);
        } catch (IllegalArgumentException exception) {
            return ExecutionResult.failure(ExecutionOutcome.BAD_REQUEST, exception.getMessage());
        }
        try {
            Submission submission = coordinator.submit(billingMonth, TriggerSource.MANUAL);
            WorkStorageBillingExecutionResponse data = new WorkStorageBillingExecutionResponse(
                    submission.accepted(),
                    submission.executionId(),
                    YearMonth.from(submission.billingMonth()).format(BILLING_MONTH_FORMATTER),
                    submission.status()
            );
            if (submission.accepted()) {
                return new ExecutionResult(ExecutionOutcome.ACCEPTED, ACCEPTED_MESSAGE, data);
            }
            return new ExecutionResult(ExecutionOutcome.ALREADY_RUNNING, ALREADY_RUNNING_MESSAGE, data);
        } catch (WorkStorageBillingUnavailableException exception) {
            return ExecutionResult.failure(ExecutionOutcome.UNAVAILABLE, exception.getMessage());
        }
    }

    /** 解析并校验指定或默认账期。 */
    private LocalDate resolveBillingMonth(WorkStorageBillingExecuteRequest request) {
        YearMonth currentMonth = YearMonth.now(clock.withZone(SHANGHAI_ZONE));
        String requested = request == null ? null : request.getBillingMonth();
        YearMonth billingMonth;
        if (requested == null || requested.isBlank()) {
            billingMonth = currentMonth.minusMonths(1);
        } else {
            String normalized = requested.strip();
            if (!normalized.matches(BILLING_MONTH_PATTERN)) {
                throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
            }
            try {
                billingMonth = YearMonth.parse(normalized, BILLING_MONTH_FORMATTER);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE, exception);
            }
        }
        if (billingMonth.isBefore(FIRST_BILLING_MONTH)) {
            throw new IllegalArgumentException(TOO_EARLY_MESSAGE);
        }
        if (!billingMonth.isBefore(currentMonth)) {
            throw new IllegalArgumentException(NOT_HISTORICAL_MESSAGE);
        }
        return billingMonth.atDay(1);
    }

    /** Controller 可映射的执行结果类型。 */
    public enum ExecutionOutcome {
        ACCEPTED,
        ALREADY_RUNNING,
        UNAUTHORIZED,
        BAD_REQUEST,
        UNAVAILABLE
    }

    /**
     * 手工执行应用结果。
     *
     * @param outcome 结果类型
     * @param message 响应消息
     * @param data 执行响应，失败时为空
     */
    public record ExecutionResult(
            ExecutionOutcome outcome,
            String message,
            WorkStorageBillingExecutionResponse data
    ) {
        /** 创建失败结果。 */
        public static ExecutionResult failure(ExecutionOutcome outcome, String message) {
            return new ExecutionResult(outcome, message, null);
        }
    }
}
