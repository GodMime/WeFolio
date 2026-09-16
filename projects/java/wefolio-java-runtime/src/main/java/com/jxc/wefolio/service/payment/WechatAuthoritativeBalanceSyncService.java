package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /** 退款恢复所需的既有账户与支付配置。 */
    private final PointAccountEntityMapper pointAccountEntityMapper;
    /** runtime 虚拟支付总开关。 */
    private final WechatVirtualPaymentProperties properties;
    /** 用户积分互斥，远端查询到快照落库之间保持串行。 */
    private final UserPointMutex userPointMutex;
    private final UserAuthEntityMapper userAuthEntityMapper;
    private final MaintainerWechatSessionService sessionService;
    private final WechatVirtualPaymentClient virtualPaymentClient;
    private final PointDebitTaskTransactionService debitTaskTransactionService;
    private final PointDebitTaskService debitTaskService;

    /** 有可用会话时立即同步；无会话或功能关闭时保留待同步标记。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void synchronizeIfSessionAvailable(Long userId, Long accountId, String referenceNo) {
        if (!properties.isEnabled()) {
            return;
        }
        userPointMutex.execute(userId, () -> synchronizeInsideUserLock(userId, accountId, referenceNo));
    }

    /** 后台核对已持有用户锁时补查退款余额，失败继续保留失效标记供现有恢复任务处理。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean synchronizeWithinUserLock(Long userId, Long accountId, String referenceNo) {
        return properties.isEnabled() && synchronizeInsideUserLock(userId, accountId, referenceNo);
    }

    /** 登录、刷新会话及后台恢复时，只补偿有退款事实的失效账户。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void synchronizeStaleBalanceForUser(Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        userPointMutex.execute(userId, () -> synchronizeRefundStaleAccount(userId));
    }

    /** 调用方已持有用户锁；暂停业务事务后同步，避免嵌套获取同一把锁。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requireFreshBalanceForMaintainer(Long userId) {
        if (properties.isEnabled() && !synchronizeRefundStaleAccount(userId)) {
            throw new BusinessException(PointMessage.REFUND_BALANCE_UNCONFIRMED_MESSAGE);
        }
    }

    /** 没有账户或没有退款失效事实时正常放行，让原流程初始化账户并判断余额。 */
    private boolean synchronizeRefundStaleAccount(Long userId) {
        PointAccountEntity account = pointAccountEntityMapper.selectRefundStaleAccount(userId);
        return account == null || synchronizeInsideUserLock(userId, account.getId(), String.valueOf(account.getId()));
    }

    /** 在既有用户锁内同步一次，失败时保留原快照的待同步标记。 */
    private boolean synchronizeInsideUserLock(Long userId, Long accountId, String referenceNo) {
        log.info("微信虚拟支付业务开始 operation=同步权威余额 referenceNo={} userId={} accountId={}",
                referenceNo, userId, accountId);
        MaintainerWechatSession session = sessionService.findAvailableSession(userId);
        UserAuthEntity auth = findWechatAuth(userId);
        if (session == null || auth == null || auth.getOpenId() == null || auth.getOpenId().isBlank()) {
            log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                            + "localStatus=SKIPPED reason=MISSING_SESSION_OR_IDENTITY",
                    referenceNo, userId);
            return false;
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
            return false;
        }
        if (result.errorType() != WechatVirtualPaymentErrorType.SUCCESS) {
            log.warn("微信权威余额同步未成功 referenceNo={} userId={} errcode={}",
                    referenceNo, userId, result.errorCode());
            log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                            + "localStatus=FAILED errorType={}",
                    referenceNo, userId, result.errorType());
            return false;
        }
        PointAccountEntity account = debitTaskTransactionService.syncAuthoritativeBalance(
                accountId, result.balance(), result.presentBalance());
        debitTaskService.ensureActiveTask(account);
        log.info("微信虚拟支付业务完成 operation=同步权威余额 referenceNo={} userId={} "
                        + "localStatus=SUCCESS balance={} presentBalance={} pendingDebit={}",
                referenceNo, userId, account.getBalance(), account.getWechatPresentBalance(),
                account.getPendingDebit());
        return true;
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
