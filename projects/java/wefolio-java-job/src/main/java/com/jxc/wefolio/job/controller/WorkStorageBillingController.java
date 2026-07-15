package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.common.Response;
import com.jxc.wefolio.job.dto.WorkStorageBillingExecuteRequest;
import com.jxc.wefolio.job.dto.WorkStorageBillingExecutionResponse;
import com.jxc.wefolio.job.service.AdminPointSecretValidator;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.Submission;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.TriggerSource;
import com.jxc.wefolio.job.service.WorkStorageBillingUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * 作品存储月度结算主动执行接口。
 */
@RestController
public class WorkStorageBillingController {

    /** 上海时区 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 首个可结算账期 */
    private static final YearMonth FIRST_BILLING_MONTH = YearMonth.of(2026, 6);

    /** 严格账期格式 */
    private static final DateTimeFormatter BILLING_MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    /** 月份格式正则 */
    private static final String BILLING_MONTH_PATTERN = "\\d{4}-\\d{2}";

    /** 接口与响应固定语义 */
    private static final String EXECUTE_PATH = "/work-storage-billing/execute";
    private static final String ADMIN_POINT_SECRET_HEADER = "X-Admin-Point-Secret";
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

    /**
     * 创建主动执行控制器。
     *
     * @param secretValidator 密钥校验器
     * @param coordinator 异步执行协调器
     * @param clock 可替换时钟
     */
    public WorkStorageBillingController(
            AdminPointSecretValidator secretValidator,
            WorkStorageBillingExecutionCoordinator coordinator,
            Clock clock
    ) {
        this.secretValidator = secretValidator;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    /**
     * 异步提交指定或默认账期结算。
     *
     * @param secret 后台积分密钥
     * @param request 可选账期请求
     * @return 异步提交结果
     */
    @PostMapping(EXECUTE_PATH)
    public ResponseEntity<Response<WorkStorageBillingExecutionResponse>> execute(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) WorkStorageBillingExecuteRequest request
    ) {
        try {
            secretValidator.validate(secret);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail(INVALID_SECRET_MESSAGE));
        }
        LocalDate billingMonth;
        try {
            billingMonth = resolveBillingMonth(request);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Response.fail(exception.getMessage()));
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
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Response.success(ACCEPTED_MESSAGE, data));
            }
            return ResponseEntity.ok(Response.success(ALREADY_RUNNING_MESSAGE, data));
        } catch (WorkStorageBillingUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Response.fail(exception.getMessage()));
        }
    }

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
}
