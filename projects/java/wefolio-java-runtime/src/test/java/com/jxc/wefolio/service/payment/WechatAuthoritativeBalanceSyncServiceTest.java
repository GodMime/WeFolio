package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 退款失效余额的恢复、首次账户兼容及开关一致性测试。 */
@ExtendWith(MockitoExtension.class)
class WechatAuthoritativeBalanceSyncServiceTest {

    /** 账户查询模拟。 */
    @Mock private PointAccountEntityMapper accounts;
    /** 认证查询模拟。 */
    @Mock private UserAuthEntityMapper auths;
    /** 会话查询模拟。 */
    @Mock private MaintainerWechatSessionService sessions;
    /** 微信客户端模拟。 */
    @Mock private WechatVirtualPaymentClient client;
    /** 余额短事务模拟。 */
    @Mock private PointDebitTaskTransactionService transactions;
    /** 活动任务保障模拟。 */
    @Mock private PointDebitTaskService tasks;
    /** 用户锁模拟。 */
    @Mock private UserPointMutex mutex;
    /** 当前测试配置。 */
    private WechatVirtualPaymentProperties properties;
    /** 被测同步服务。 */
    private WechatAuthoritativeBalanceSyncService service;

    /** 默认启用支付，单独用例再验证关闭。 */
    @BeforeEach
    void setUp() {
        properties = new WechatVirtualPaymentProperties();
        properties.setEnabled(true);
        service = new WechatAuthoritativeBalanceSyncService(
                accounts, properties, mutex, auths, sessions, client, transactions, tasks);
    }

    /** 关闭开关时所有恢复入口均不读写账户、不查微信也不取得锁。 */
    @Test
    void disabledShouldSkipAllSyncEntrypoints() {
        properties.setEnabled(false);
        service.synchronizeIfSessionAvailable(7L, 10L, "refund");
        service.synchronizeStaleBalanceForUser(7L);
        service.requireFreshBalanceForMaintainer(7L);
        verifyNoInteractions(accounts, mutex, auths, sessions, client, transactions, tasks);
    }

    /** 没有退款失效事实的首次账户正常放行，不依赖会话或账户是否存在。 */
    @Test
    void firstUnsyncedOrMissingAccountShouldNotBeBlocked() {
        service.requireFreshBalanceForMaintainer(7L);
        verify(accounts).selectRefundStaleAccount(7L);
        verifyNoInteractions(mutex, auths, sessions, client, transactions, tasks);
    }

    /** 确认有退款但没有会话时，只阻止维护者继续使用陈旧余额。 */
    @Test
    void refundWithoutSessionShouldRemainStaleAndBlockMaintainer() {
        when(accounts.selectRefundStaleAccount(7L)).thenReturn(account());
        assertThatThrownBy(() -> service.requireFreshBalanceForMaintainer(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额暂无法确认，请稍后重试");
        verifyNoInteractions(mutex, client, transactions, tasks);
    }

    /** 即便没有任何待扣，登录或后台恢复仍会同步退款余额，并只取得一次用户锁。 */
    @Test
    void zeroPendingRefundShouldRecoverWithOneUserLock() {
        when(mutex.execute(eq(7L), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        prepareRefund();
        service.synchronizeStaleBalanceForUser(7L);
        verify(mutex).execute(eq(7L), any());
        verify(transactions).syncAuthoritativeBalance(10L, 0L, 0L);
        verify(tasks).ensureActiveTask(any(PointAccountEntity.class));
    }

    /** 维护者消费已持有用户锁，恢复过程不能再嵌套获取。 */
    @Test
    void maintainerShouldUseExistingUserLock() {
        prepareRefund();
        service.requireFreshBalanceForMaintainer(7L);
        verifyNoInteractions(mutex);
        verify(transactions).syncAuthoritativeBalance(10L, 0L, 0L);
    }

    /** 准备有会话且微信返回零余额的退款用户。 */
    private void prepareRefund() {
        when(accounts.selectRefundStaleAccount(7L)).thenReturn(account());
        when(sessions.findAvailableSession(7L))
                .thenReturn(new MaintainerWechatSession(7L, 11L, "session", 1L, "127.0.0.1"));
        UserAuthEntity auth = new UserAuthEntity();
        auth.setOpenId("openid");
        when(auths.selectOne(any())).thenReturn(auth);
        when(client.queryUserBalance(any())).thenReturn(new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 0L, 0L, 0L, null, 0L, 0L, 200));
        when(transactions.syncAuthoritativeBalance(10L, 0L, 0L)).thenReturn(account());
    }

    /** 构造没有待扣的退款账户。 */
    private PointAccountEntity account() {
        PointAccountEntity account = new PointAccountEntity();
        account.setId(10L);
        account.setUserId(7L);
        account.setPendingDebit(0L);
        account.setBalance(0L);
        account.setWechatPresentBalance(0L);
        return account;
    }
}
