package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** 由退款等外部事件触发的微信权威余额同步服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatAuthoritativeBalanceSyncService {

    private final UserAuthEntityMapper userAuthEntityMapper;
    private final MaintainerWechatSessionService sessionService;
    private final WechatVirtualPaymentClient virtualPaymentClient;
    private final PointDebitTaskTransactionService debitTaskTransactionService;
    private final PointDebitTaskService debitTaskService;

    /** 有可用会话时立即同步；无会话时保留待同步标记。 */
    public void synchronizeIfSessionAvailable(Long userId, Long accountId, String referenceNo) {
        log.info("微信虚拟支付业务开始 operation=同步权威余额 referenceNo={} userId={} accountId={}",
                referenceNo, userId, accountId);
        MaintainerWechatSession session = sessionService.findAvailableSession(userId);
        UserAuthEntity auth = findWechatAuth(userId);
        if (session == null || auth == null || auth.getOpenId() == null || auth.getOpenId().isBlank()) {
            log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                            + "localStatus=SKIPPED reason=MISSING_SESSION_OR_IDENTITY",
                    referenceNo, userId);
            return;
        }
        WechatVirtualPaymentResult result = virtualPaymentClient.queryUserBalance(
                new WechatBalanceQueryRequest(
                        userId,
                        referenceNo,
                        auth.getOpenId(),
                        session.sessionKey(),
                        session.clientIp(),
                        Instant.now().getEpochSecond()
                ));
        log.info("微信虚拟支付业务微信结果 operation=同步权威余额 referenceNo={} userId={} "
                        + "errcode={} errorType={} balance={} presentBalance={}",
                referenceNo, userId, result.errorCode(), result.errorType(),
                result.balance(), result.presentBalance());
        if (result.errorType() == WechatVirtualPaymentErrorType.SESSION_INVALID) {
            sessionService.invalidateVersion(userId, session.sessionVersion(), result.errorMessage());
            log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                            + "localStatus=SESSION_INVALID",
                    referenceNo, userId);
            return;
        }
        if (!result.isSuccessful()) {
            log.warn("微信权威余额同步未成功 referenceNo={} userId={} errcode={}",
                    referenceNo, userId, result.errorCode());
            log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                            + "localStatus=FAILED errorType={}",
                    referenceNo, userId, result.errorType());
            return;
        }
        PointAccountEntity account = debitTaskTransactionService.syncAuthoritativeBalance(
                accountId, result.balance(), result.presentBalance());
        debitTaskService.ensureActiveTask(account);
        log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                        + "localStatus=SUCCESS balance={} presentBalance={} pendingDebit={}",
                referenceNo, userId, account.getBalance(), account.getWechatPresentBalance(),
                account.getPendingDebit());
    }

    /** 查询用户有效微信身份。 */
    private UserAuthEntity findWechatAuth(Long userId) {
        return userAuthEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserAuthEntity.class)
                        .eq(UserAuthEntity::getUserId, userId)
                        .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                        .eq(UserAuthEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .isNotNull(UserAuthEntity::getOpenId)
                        .last("LIMIT 1")
        );
    }
}
