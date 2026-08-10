package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dto.VirtualPaymentDebitTaskRecoveryResponse;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.service.AdminPointSecretValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * job 调用的微信虚拟支付单任务执行应用服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VirtualPaymentTaskExecutionApplicationService {

    /** 赠送订单任务类型。 */
    private static final String GIFT_ORDER_TASK_TYPE = "GIFT_ORDER";

    /** 扣币任务类型。 */
    private static final String DEBIT_TASK_TYPE = "DEBIT_TASK";

    /** 扣币任务恢复类型。 */
    private static final String DEBIT_TASK_RECOVERY_TYPE = "DEBIT_TASK_RECOVERY";

    /** 已保障活动任务结果。 */
    private static final String ACTIVE_TASK_ENSURED_OUTCOME = "ACTIVE_TASK_ENSURED";

    /** 没有待扣金额结果。 */
    private static final String NO_PENDING_DEBIT_OUTCOME = "NO_PENDING_DEBIT";

    /** runtime 微信虚拟支付功能关闭结果。 */
    private static final String VIRTUAL_PAYMENT_DISABLED_OUTCOME = "VIRTUAL_PAYMENT_DISABLED";

    /** 本次内部调用失败结果。 */
    private static final String FAILED_OUTCOME = "FAILED";

    /** 后台积分内部密钥校验器。 */
    private final AdminPointSecretValidator secretValidator;

    /** 单条赠送订单处理器。 */
    private final PointGiftOrderProcessor pointGiftOrderProcessor;

    /** 单条扣币任务处理器。 */
    private final PointDebitTaskProcessor pointDebitTaskProcessor;

    /** 活动扣币任务保障服务。 */
    private final PointDebitTaskService pointDebitTaskService;

    /** runtime 微信虚拟支付总开关配置。 */
    private final WechatVirtualPaymentProperties virtualPaymentProperties;

    /** 校验内部密钥并处理单条赠送订单。 */
    public VirtualPaymentTaskExecutionResponse executeGiftOrder(String secret, Long orderId) {
        secretValidator.validate(secret);
        try {
            if (!virtualPaymentProperties.isEnabled()) {
                return buildExecutionResponse(
                        orderId, GIFT_ORDER_TASK_TYPE, TaskExecutionOutcome.SKIPPED_DISABLED.name());
            }
            TaskExecutionOutcome outcome = pointGiftOrderProcessor.process(orderId);
            return buildExecutionResponse(orderId, GIFT_ORDER_TASK_TYPE, outcome.name());
        } catch (RuntimeException exception) {
            logExecutionFailure(GIFT_ORDER_TASK_TYPE, orderId, exception);
            throw exception;
        }
    }

    /** 校验内部密钥并处理单条扣币任务。 */
    public VirtualPaymentTaskExecutionResponse executeDebitTask(String secret, Long taskId) {
        secretValidator.validate(secret);
        try {
            if (!virtualPaymentProperties.isEnabled()) {
                return buildExecutionResponse(
                        taskId, DEBIT_TASK_TYPE, TaskExecutionOutcome.SKIPPED_DISABLED.name());
            }
            TaskExecutionOutcome outcome = pointDebitTaskProcessor.process(taskId);
            return buildExecutionResponse(taskId, DEBIT_TASK_TYPE, outcome.name());
        } catch (RuntimeException exception) {
            logExecutionFailure(DEBIT_TASK_TYPE, taskId, exception);
            throw exception;
        }
    }

    /** 校验内部密钥并保障指定用户存在活动扣币任务。 */
    public VirtualPaymentDebitTaskRecoveryResponse recoverDebitTask(String secret, Long userId) {
        secretValidator.validate(secret);
        try {
            if (!virtualPaymentProperties.isEnabled()) {
                return buildRecoveryResponse(userId, null, VIRTUAL_PAYMENT_DISABLED_OUTCOME);
            }
            PointDebitTaskEntity task = pointDebitTaskService.ensureActiveTask(userId);
            if (task == null) {
                return buildRecoveryResponse(userId, null, NO_PENDING_DEBIT_OUTCOME);
            }
            return buildRecoveryResponse(userId, task.getId(), ACTIVE_TASK_ENSURED_OUTCOME);
        } catch (RuntimeException exception) {
            logRecoveryFailure(userId, exception);
            throw exception;
        }
    }

    /** 构造单任务执行响应并记录本次内部调用结果。 */
    private VirtualPaymentTaskExecutionResponse buildExecutionResponse(
            Long targetId,
            String taskType,
            String outcome
    ) {
        VirtualPaymentTaskExecutionResponse response =
                new VirtualPaymentTaskExecutionResponse(targetId, taskType, outcome);
        log.info("微信虚拟支付内部任务调用完成 taskType={} targetId={} outcome={}",
                taskType, targetId, outcome);
        return response;
    }

    /** 构造扣币任务恢复响应并记录本次内部调用结果。 */
    private VirtualPaymentDebitTaskRecoveryResponse buildRecoveryResponse(
            Long userId,
            Long taskId,
            String outcome
    ) {
        VirtualPaymentDebitTaskRecoveryResponse response =
                new VirtualPaymentDebitTaskRecoveryResponse(userId, taskId, outcome);
        log.info("微信虚拟支付内部任务调用完成 taskType={} userId={} taskId={} outcome={}",
                DEBIT_TASK_RECOVERY_TYPE, userId, taskId, outcome);
        return response;
    }

    /** 记录单任务内部调用失败结果。 */
    private void logExecutionFailure(String taskType, Long targetId, RuntimeException exception) {
        log.info("微信虚拟支付内部任务调用完成 taskType={} targetId={} outcome={} exceptionType={}",
                taskType, targetId, FAILED_OUTCOME, exception.getClass().getSimpleName());
    }

    /** 记录扣币任务恢复内部调用失败结果。 */
    private void logRecoveryFailure(Long userId, RuntimeException exception) {
        log.info("微信虚拟支付内部任务调用完成 taskType={} userId={} taskId={} outcome={} "
                        + "exceptionType={}",
                DEBIT_TASK_RECOVERY_TYPE, userId, null, FAILED_OUTCOME,
                exception.getClass().getSimpleName());
    }
}
