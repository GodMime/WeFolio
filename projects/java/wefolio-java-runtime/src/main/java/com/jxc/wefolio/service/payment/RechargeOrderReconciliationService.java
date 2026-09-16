package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PaymentChannelDict;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.service.AdminPointSecretValidator;
import com.jxc.wefolio.service.point.UserPointMutex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/** 后台逐单权威核对：恢复尚未入账的充值，已入账订单退出定时核对。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeOrderReconciliationService {
    /** 内部响应任务类型及固定结果。 */
    private static final String TASK_TYPE = "RECHARGE_ORDER";
    /** 本轮核对已得到确定结果。 */
    private static final String SUCCEEDED = "SUCCEEDED";
    /** 本轮结果未确认，保留订单继续退避。 */
    private static final String RETRY_WAIT = "RETRY_WAIT";
    /** 等待维护者刷新微信会话。 */
    private static final String WAITING_SESSION = "WAITING_SESSION";
    /** 运行时支付开关关闭。 */
    private static final String SKIPPED_DISABLED = "SKIPPED_DISABLED";
    /** 订单尚未到达下次核对时间。 */
    private static final String SKIPPED_NOT_DUE = "SKIPPED_NOT_DUE";
    /** 订单不存在或已退出可核对状态。 */
    private static final String SKIPPED_TERMINAL = "SKIPPED_TERMINAL";
    /** 核对失败的持久化分类。 */
    private static final String QUERY_FAILED = "QUERY_FAILED";
    /** 微信订单仍待确认的持久化分类。 */
    private static final String QUERY_PENDING = "QUERY_PENDING";
    /** 尚未完成的充值核对最多退避一小时。 */
    private static final long MAX_RETRY_MINUTES = 60L;
    /** 远端关闭订单继续保留迟到支付补偿的复查小时数。 */
    private static final long CLOSED_QUERY_HOURS = 24L;
    /** 会话缺失后的最短复查分钟数。 */
    private static final long MISSING_SESSION_RETRY_MINUTES = 5L;
    /** 退避计数只封顶计数值，不停止恢复。 */
    private static final int MAX_RETRY_COUNT = 30;
    /** 后台能够恢复尚未入账充值的订单状态。 */
    private static final Set<String> CANDIDATE_STATES = Set.of(
            RechargeOrderStatusDict.PENDING_PAYMENT.getCode(), RechargeOrderStatusDict.CLOSED.getCode());

    /** 内部请求鉴权。 */
    private final AdminPointSecretValidator secretValidator;
    /** 运行时支付开关。 */
    private final WechatVirtualPaymentProperties properties;
    /** 充值订单查询。 */
    private final RechargeOrderEntityMapper orders;
    /** 复用已有身份核验和幂等充值流程。 */
    private final RechargeCommandService rechargeCommandService;
    /** 每次核对后独立提交调度进度。 */
    private final RechargeOrderTransactionService transactions;
    /** 缺少有效会话时保留订单等待客户端刷新。 */
    private final MaintainerWechatSessionService sessions;
    /** 并发通知、公开同步及后台核对共用用户互斥。 */
    private final UserPointMutex mutex;

    /** 验证内部身份后处理一笔订单，远端异常只延后核对，不改变订单终态。 */
    public VirtualPaymentTaskExecutionResponse reconcile(String secret, Long orderId) {
        secretValidator.validate(secret);
        if (!properties.isEnabled()) {
            return result(orderId, SKIPPED_DISABLED);
        }
        RechargeOrderEntity candidate = orders.selectById(orderId);
        if (!isCandidate(candidate)) {
            return result(orderId, SKIPPED_TERMINAL);
        }
        return mutex.execute(candidate.getUserId(), () -> reconcileInsideLock(orderId));
    }

    /** 取得用户锁后重读订单和到期时间，阻止同一候选重复请求微信。 */
    private VirtualPaymentTaskExecutionResponse reconcileInsideLock(Long orderId) {
        RechargeOrderEntity order = orders.selectById(orderId);
        if (!isCandidate(order)) {
            return result(orderId, SKIPPED_TERMINAL);
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.getNextQueryAt() != null && order.getNextQueryAt().isAfter(now)) {
            return result(orderId, SKIPPED_NOT_DUE);
        }
        RechargeOrderSyncResponse response;
        try {
            if (sessions.findAvailableSession(order.getUserId()) == null) {
                transactions.recordQuerySchedule(orderId, now.plusMinutes(MISSING_SESSION_RETRY_MINUTES),
                        retryCount(order), WAITING_SESSION);
                return result(orderId, WAITING_SESSION);
            }
            response = rechargeCommandService.reconcileOrderWithinUserLock(order.getUserId(), order.getMerchantOrderNo());
        } catch (RuntimeException exception) {
            log.warn("后台充值核对暂未完成 orderId={} userId={} exceptionType={}",
                    orderId, order.getUserId(), exception.getClass().getSimpleName());
            return scheduleRetry(order, now, QUERY_FAILED);
        }
        if (!response.isConfirmed()) {
            return scheduleRetry(order, now, QUERY_PENDING);
        }
        boolean closed = RechargeOrderStatusDict.CLOSED.getCode().equals(response.getStatus());
        transactions.recordQuerySchedule(orderId, closed ? now.plusHours(CLOSED_QUERY_HOURS) : null, 0, null);
        return result(orderId, SUCCEEDED);
    }

    /** 未完成结果持续重试且有界退避，独立更新计划保证其他订单获得扫描机会。 */
    private VirtualPaymentTaskExecutionResponse scheduleRetry(RechargeOrderEntity order, LocalDateTime now, String code) {
        int previous = Math.min(retryCount(order), MAX_RETRY_COUNT);
        long delay = Math.min(MAX_RETRY_MINUTES, 1L << Math.min(previous, 6));
        transactions.recordQuerySchedule(order.getId(), now.plusMinutes(delay),
                Math.min(MAX_RETRY_COUNT, previous + 1), code);
        return result(order.getId(), RETRY_WAIT);
    }

    /** 仅恢复未入账的虚拟支付；已核实收款的失败单仍需核实最终结果，已入账单不查退款。 */
    private boolean isCandidate(RechargeOrderEntity order) {
        return order != null && order.getUserId() != null
                && PaymentChannelDict.WECHAT_VIRTUAL_PAYMENT.getCode().equals(order.getPayChannel())
                && order.getStatus() != null
                && (CANDIDATE_STATES.contains(order.getStatus())
                || (RechargeOrderStatusDict.PAYMENT_FAILED.getCode().equals(order.getStatus())
                    && order.getPaidFee() != null && order.getPaidFee() > 0L));
    }

    /** 兼容存量订单和防止异常计数导致移位溢出。 */
    private int retryCount(RechargeOrderEntity order) {
        return order.getQueryRetryCount() == null ? 0 : Math.max(0, order.getQueryRetryCount());
    }

    /** 返回与既有内部任务接口一致的结果形状。 */
    private VirtualPaymentTaskExecutionResponse result(Long orderId, String outcome) {
        return new VirtualPaymentTaskExecutionResponse(orderId, TASK_TYPE, outcome);
    }
}
