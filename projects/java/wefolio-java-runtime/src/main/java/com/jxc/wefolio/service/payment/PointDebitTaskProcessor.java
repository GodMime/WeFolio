package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.PointDebitTaskStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.point.UserPointMutex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 单条微信待扣任务处理器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointDebitTaskProcessor {

    private final PointDebitTaskTransactionService transactionService;
    private final WechatVirtualPaymentClient wechatVirtualPaymentClient;
    private final MaintainerWechatSessionService maintainerWechatSessionService;
    private final UserAuthEntityMapper userAuthEntityMapper;
    private final UserPointMutex userPointMutex;
    private final PointGiftOrderEntityMapper pointGiftOrderEntityMapper;
    private final PointGiftOrderProcessor pointGiftOrderProcessor;
    private final ExecutionLeaseTokenGenerator tokenGenerator;

    /** 领取并处理一条待扣任务。 */
    public TaskExecutionOutcome process(Long taskId) {
        String executionLeaseToken = tokenGenerator.generate();
        PointDebitTaskEntity task = transactionService.tryClaim(taskId, executionLeaseToken);
        if (task == null) {
            return TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE;
        }
        log.info("微信虚拟支付业务开始 operation=处理扣币任务 referenceNo={} userId={} accountId={}",
                task.getTaskNo(), task.getUserId(), task.getAccountId());
        try {
            userPointMutex.execute(task.getUserId(), () -> {
                processClaimed(task, executionLeaseToken);
                return null;
            });
        } catch (ExecutionLeaseLostException exception) {
            log.info("扣币任务执行租约失效 taskId={} userId={}", taskId, task.getUserId());
        }
        return TaskExecutionOutcome.PROCESSED;
    }

    /** 在用户锁内执行权威余额查询与扣币。 */
    private void processClaimed(PointDebitTaskEntity task, String executionLeaseToken) {
        processDueGiftFirst(task.getUserId());
        MaintainerWechatSession session = maintainerWechatSessionService.findAvailableSession(task.getUserId());
        if (session == null || blank(session.sessionKey()) || blank(session.clientIp())) {
            transactionService.markWaitingSession(task.getId(), executionLeaseToken, "缺少有效维护者微信会话");
            log.info("微信虚拟支付业务完成 operation=处理扣币任务 referenceNo={} userId={} "
                            + "localStatus=WAITING_SESSION",
                    task.getTaskNo(), task.getUserId());
            return;
        }
        UserAuthEntity auth = userAuthEntityMapper.selectById(session.authId());
        if (auth == null || !task.getUserId().equals(auth.getUserId()) || blank(auth.getOpenId())) {
            transactionService.markFailure(task.getId(), executionLeaseToken,
                    failure(WechatVirtualPaymentErrorType.PERMANENT, "维护者微信身份不存在"));
            log.info("微信虚拟支付业务完成 operation=处理扣币任务 referenceNo={} userId={} "
                            + "localStatus=FAILED reason=MISSING_WECHAT_IDENTITY",
                    task.getTaskNo(), task.getUserId());
            return;
        }
        long timestamp = Instant.now().getEpochSecond();
        transactionService.renewLease(task.getId(), executionLeaseToken);
        WechatVirtualPaymentResult balanceResult = queryUserBalance(task, session, auth.getOpenId(), timestamp);
        if (!balanceResult.isSuccessful()) {
            handleRemoteFailure(task, executionLeaseToken, session, balanceResult);
            log.info("微信虚拟支付业务完成 operation=处理扣币任务 referenceNo={} userId={} "
                            + "localStatus=REMOTE_BALANCE_FAILED errorType={}",
                    task.getTaskNo(), task.getUserId(), balanceResult.errorType());
            return;
        }
        PointAccountEntity account = transactionService.syncBalance(
                task.getId(), executionLeaseToken, balanceResult.balance(), balanceResult.presentBalance());
        long pendingBefore = value(account.getPendingDebit());
        long requestAmount = task.getRequestAmount() == null
                ? Math.min(pendingBefore, balanceResult.balance())
                : task.getRequestAmount();
        if (requestAmount <= 0L) {
            transactionService.markNoBalance(task.getId(), executionLeaseToken, pendingBefore);
            log.info("微信虚拟支付业务完成 operation=处理扣币任务 referenceNo={} userId={} "
                            + "localStatus=NO_BALANCE pendingDebit={}",
                    task.getTaskNo(), task.getUserId(), pendingBefore);
            return;
        }
        PointDebitTaskEntity prepared = transactionService.prepareRequest(
                task.getId(), executionLeaseToken, requestAmount, pendingBefore,
                balanceResult.balance(), session.sessionVersion());
        transactionService.renewLease(task.getId(), executionLeaseToken);
        WechatVirtualPaymentResult payResult;
        try {
            payResult = wechatVirtualPaymentClient.currencyPay(new WechatCurrencyPayRequest(
                    task.getUserId(),
                    task.getTaskNo(),
                    auth.getOpenId(),
                    session.sessionKey(),
                    session.clientIp(),
                    task.getTaskNo(),
                    prepared.getRequestAmount(),
                    timestamp
            ));
        } catch (RuntimeException exception) {
            log.warn("微信虚拟支付业务异常 operation=处理扣币任务 referenceNo={} userId={} "
                            + "exceptionType={}",
                    task.getTaskNo(), task.getUserId(), exception.getClass().getSimpleName());
            payResult = failure(WechatVirtualPaymentErrorType.TRANSIENT, "微信扣币调用异常");
        }
        log.info("微信虚拟支付业务微信结果 operation=处理扣币任务 referenceNo={} userId={} "
                        + "errcode={} errorType={} balance={} presentBalance={} usedPresentAmount={}",
                task.getTaskNo(), task.getUserId(), payResult.errorCode(), payResult.errorType(),
                payResult.balance(), payResult.presentBalance(), payResult.usedPresentAmount());
        if (payResult.errorType() == WechatVirtualPaymentErrorType.SUCCESS
                || payResult.errorType() == WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS) {
            transactionService.completeSuccess(task.getId(), executionLeaseToken, payResult);
            log.info("微信虚拟支付业务完成 operation=处理扣币任务 referenceNo={} userId={} "
                            + "localStatus=SUCCESS balance={} presentBalance={}",
                    task.getTaskNo(), task.getUserId(), payResult.balance(), payResult.presentBalance());
            return;
        }
        handleRemoteFailure(prepared, executionLeaseToken, session, payResult);
        log.info("微信虚拟支付业务完成 operation=处理扣币任务 referenceNo={} userId={} "
                        + "localStatus=FAILED errorType={}",
                task.getTaskNo(), task.getUserId(), payResult.errorType());
    }

    /** 待扣结算前优先尝试同一用户的一笔到期赠送，赠送失败不阻断扣币。 */
    private void processDueGiftFirst(Long userId) {
        PointGiftOrderEntity gift = pointGiftOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointGiftOrderEntity.class)
                        .eq(PointGiftOrderEntity::getUserId, userId)
                        .in(PointGiftOrderEntity::getStatus,
                                PointGiftOrderStatusDict.READY.getCode(),
                                PointGiftOrderStatusDict.RETRY_WAIT.getCode())
                        .le(PointGiftOrderEntity::getNextExecuteAt, LocalDateTime.now())
                        .and(query -> query.isNull(PointGiftOrderEntity::getLeaseUntil)
                                .or().lt(PointGiftOrderEntity::getLeaseUntil, LocalDateTime.now()))
                        .orderByAsc(PointGiftOrderEntity::getNextExecuteAt)
                        .orderByAsc(PointGiftOrderEntity::getId)
                        .last("LIMIT 1")
        );
        if (gift == null) {
            return;
        }
        try {
            pointGiftOrderProcessor.process(gift.getId());
        } catch (RuntimeException exception) {
            log.warn("待扣结算前赠送尝试失败 giftOrderId={} userId={} exceptionType={}",
                    gift.getId(), userId, exception.getClass().getSimpleName());
        }
    }

    /** 查询微信权威余额。 */
    private WechatVirtualPaymentResult queryUserBalance(
            PointDebitTaskEntity task,
            MaintainerWechatSession session,
            String openid,
            long timestamp
    ) {
        try {
            WechatVirtualPaymentResult result = wechatVirtualPaymentClient.queryUserBalance(
                    new WechatBalanceQueryRequest(
                    task.getUserId(), task.getTaskNo(), openid,
                    session.sessionKey(), session.clientIp(), timestamp));
            log.info("微信虚拟支付业务微信结果 operation=查询权威余额 referenceNo={} userId={} "
                            + "errcode={} errorType={} balance={} presentBalance={}",
                    task.getTaskNo(), task.getUserId(), result.errorCode(), result.errorType(),
                    result.balance(), result.presentBalance());
            return result;
        } catch (RuntimeException exception) {
            log.warn("微信虚拟支付业务异常 operation=查询权威余额 referenceNo={} userId={} "
                            + "exceptionType={}",
                    task.getTaskNo(), task.getUserId(), exception.getClass().getSimpleName());
            return failure(WechatVirtualPaymentErrorType.TRANSIENT, "微信余额查询异常");
        }
    }

    /** 按分类处理远端失败。 */
    private void handleRemoteFailure(
            PointDebitTaskEntity task,
            String executionLeaseToken,
            MaintainerWechatSession session,
            WechatVirtualPaymentResult result
    ) {
        if (result.errorType() == WechatVirtualPaymentErrorType.SESSION_INVALID) {
            maintainerWechatSessionService.invalidateVersion(
                    task.getUserId(), session.sessionVersion(), "微信虚拟支付会话失效");
            transactionService.markWaitingSession(
                    task.getId(), executionLeaseToken, PointDebitTaskStatusDict.WAITING_SESSION.getDisplayName());
            return;
        }
        if (result.errorType() == WechatVirtualPaymentErrorType.INSUFFICIENT_BALANCE) {
            transactionService.markNoBalance(
                    task.getId(), executionLeaseToken, value(task.getPendingBefore()));
            return;
        }
        transactionService.markFailure(task.getId(), executionLeaseToken, result);
    }

    /** 构造本地失败结果。 */
    private WechatVirtualPaymentResult failure(WechatVirtualPaymentErrorType type, String message) {
        return new WechatVirtualPaymentResult(null, message, type,
                0L, 0L, 0L, null, 0L, 0L, 0);
    }

    /** 可空长整数转零。 */
    private long value(Long number) {
        return number == null ? 0L : number;
    }

    /** 是否为空白。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
