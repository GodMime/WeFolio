package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.point.UserPointMutex;
import com.jxc.wefolio.message.WechatVirtualPaymentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 单条微信代币赠送订单处理器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointGiftOrderProcessor {

    private static final String ACTIVE_AUTH_STATUS = "ACTIVE";

    private final PointGiftOrderTransactionService transactionService;
    private final WechatVirtualPaymentClient wechatVirtualPaymentClient;
    private final UserAuthEntityMapper userAuthEntityMapper;
    private final UserPointMutex userPointMutex;
    private final ExecutionLeaseTokenGenerator tokenGenerator;

    /** 重复赠送应答补查余额所需的维护者会话。 */
    private final MaintainerWechatSessionService sessionService;

    /** 领取并独立处理一条赠送订单。 */
    public TaskExecutionOutcome process(Long orderId) {
        String executionLeaseToken = tokenGenerator.generate();
        PointGiftOrderEntity order = transactionService.tryClaim(orderId, executionLeaseToken);
        if (order == null) {
            return TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE;
        }
        log.info("微信虚拟支付业务开始 operation=处理赠送订单 referenceNo={} userId={} amount={}",
                order.getOrderNo(), order.getUserId(), order.getAmount());
        try {
            userPointMutex.execute(order.getUserId(), () -> {
                processClaimed(order, executionLeaseToken);
                return null;
            });
        } catch (ExecutionLeaseLostException exception) {
            log.info("赠送订单执行租约失效 orderId={} userId={}", orderId, order.getUserId());
        }
        return TaskExecutionOutcome.PROCESSED;
    }

    /** 在用户锁内访问微信并落库。 */
    private void processClaimed(PointGiftOrderEntity order, String executionLeaseToken) {
        String openid = findWechatOpenid(order.getUserId());
        if (openid == null || openid.isBlank()) {
            transactionService.markFailure(order.getId(), executionLeaseToken,
                    failure(WechatVirtualPaymentErrorType.PERMANENT, "用户缺少有效微信身份"));
            log.info("微信虚拟支付业务完成 operation=处理赠送订单 referenceNo={} userId={} "
                            + "localStatus={} reason=MISSING_WECHAT_IDENTITY",
                    order.getOrderNo(), order.getUserId(),
                    PointGiftOrderStatusDict.FAILED.getCode());
            return;
        }
        if (PointGiftOrderTransactionService.hasRecordedSuccess(order)) {
            confirmDuplicateGift(order, executionLeaseToken, openid);
            return;
        }
        transactionService.renewLease(order.getId(), executionLeaseToken);
        WechatVirtualPaymentResult result;
        try {
            result = wechatVirtualPaymentClient.presentCurrency(new WechatPresentCurrencyRequest(
                    order.getUserId(),
                    order.getOrderNo(),
                    openid,
                    order.getOrderNo(),
                    order.getAmount(),
                    Instant.now().getEpochSecond()
            ));
        } catch (RuntimeException exception) {
            log.warn("微信虚拟支付业务异常 operation=处理赠送订单 referenceNo={} userId={} "
                            + "exceptionType={}",
                    order.getOrderNo(), order.getUserId(), exception.getClass().getSimpleName());
            result = failure(WechatVirtualPaymentErrorType.TRANSIENT, "微信赠送调用异常");
        }
        log.info("微信虚拟支付业务微信结果 operation=处理赠送订单 referenceNo={} userId={} "
                        + "errcode={} errorType={} balance={} presentBalance={}",
                order.getOrderNo(), order.getUserId(), result.errorCode(), result.errorType(),
                result.balance(), result.presentBalance());
        if (result.errorType() == WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS) {
            transactionService.recordDuplicateSuccess(order.getId(), executionLeaseToken);
            order.setLastErrorCode(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
            confirmDuplicateGift(order, executionLeaseToken, openid);
            return;
        }
        if (result.errorType() == WechatVirtualPaymentErrorType.SUCCESS) {
            transactionService.completeSuccess(order.getId(), executionLeaseToken, result);
            log.info("微信虚拟支付业务完成 operation=处理赠送订单 referenceNo={} userId={} "
                            + "localStatus=SUCCESS balance={} presentBalance={}",
                    order.getOrderNo(), order.getUserId(), result.balance(), result.presentBalance());
        } else {
            transactionService.markFailure(order.getId(), executionLeaseToken, result);
            log.info("微信虚拟支付业务完成 operation=处理赠送订单 referenceNo={} userId={} "
                            + "localStatus=FAILED errorType={}",
                    order.getOrderNo(), order.getUserId(), result.errorType());
        }
    }

    /** 已持久确认重复赠送后仅补查余额，失败仍保留同一订单等待自动恢复。 */
    private void confirmDuplicateGift(PointGiftOrderEntity order, String executionLeaseToken, String openid) {
        WechatVirtualPaymentResult duplicate = new WechatVirtualPaymentResult(
                null, null, WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS,
                0L, 0L, 0L, null, 0L, 0L, 200);
        WechatVirtualPaymentResult confirmed = completeDuplicateBalance(order, executionLeaseToken, openid, duplicate);
        if (confirmed.isSuccessful()) {
            transactionService.completeSuccess(order.getId(), executionLeaseToken, confirmed);
            log.info("微信重复赠送余额确认完成 orderId={} balance={} presentBalance={}",
                    order.getId(), confirmed.balance(), confirmed.presentBalance());
        } else {
            transactionService.markFailure(order.getId(), executionLeaseToken, confirmed);
            log.warn("微信重复赠送等待余额确认 orderId={} errorType={}", order.getId(), confirmed.errorType());
        }
    }

    /** 重复赠送只确认已到账，取得权威余额后才允许结算同一赠送订单。 */
    private WechatVirtualPaymentResult completeDuplicateBalance(
            PointGiftOrderEntity order, String executionLeaseToken, String openid,
            WechatVirtualPaymentResult duplicate
    ) {
        MaintainerWechatSession session = sessionService.findAvailableSession(order.getUserId());
        if (session == null || session.sessionKey() == null || session.sessionKey().isBlank()
                || session.clientIp() == null || session.clientIp().isBlank()) {
            return failure(WechatVirtualPaymentErrorType.SESSION_INVALID,
                    WechatVirtualPaymentMessage.DUPLICATE_GIFT_SESSION_REQUIRED_MESSAGE);
        }
        transactionService.renewLease(order.getId(), executionLeaseToken);
        WechatVirtualPaymentResult balance;
        try {
            balance = wechatVirtualPaymentClient.queryUserBalance(new WechatBalanceQueryRequest(
                    order.getUserId(), order.getOrderNo(), openid, session.sessionKey(),
                    session.clientIp(), Instant.now().getEpochSecond()));
        } catch (RuntimeException exception) {
            return failure(WechatVirtualPaymentErrorType.TRANSIENT, WechatVirtualPaymentMessage.DUPLICATE_GIFT_BALANCE_QUERY_FAILED_MESSAGE);
        }
        if (balance.errorType() == WechatVirtualPaymentErrorType.SESSION_INVALID) {
            sessionService.invalidateVersion(order.getUserId(), session.sessionVersion(), balance.errorMessage());
        }
        if (balance.errorType() != WechatVirtualPaymentErrorType.SUCCESS) {
            // 保留真实失败分类，使永久错误停止自动补查；成功事实仍由事务服务保留。
            return failure(balance.errorType(),
                    WechatVirtualPaymentMessage.DUPLICATE_GIFT_BALANCE_UNCONFIRMED_MESSAGE);
        }
        return duplicate.withBalanceSnapshot(balance);
    }

    /** 查询用户当前有效微信 openid。 */
    private String findWechatOpenid(Long userId) {
        UserAuthEntity auth = userAuthEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserAuthEntity.class)
                        .eq(UserAuthEntity::getUserId, userId)
                        .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                        .eq(UserAuthEntity::getStatus, ACTIVE_AUTH_STATUS)
                        .isNotNull(UserAuthEntity::getOpenId)
                        .last("LIMIT 1"));
        return auth == null ? null : auth.getOpenId();
    }

    /** 构造本地失败结果。 */
    private WechatVirtualPaymentResult failure(
            WechatVirtualPaymentErrorType errorType,
            String message
    ) {
        return new WechatVirtualPaymentResult(null, message, errorType,
                0L, 0L, 0L, null, 0L, 0L, 0);
    }
}
