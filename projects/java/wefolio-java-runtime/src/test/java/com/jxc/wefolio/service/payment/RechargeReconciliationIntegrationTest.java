package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.AdminPointSecretValidator;
import com.jxc.wefolio.service.PointService;
import com.jxc.wefolio.service.point.GiftOrderResult;
import com.jxc.wefolio.service.point.PointCommandService;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.sql.Timestamp;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 充值核对的真实订单事务回归：补查失败、迟到恢复、已入账退出及退款通知。 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recharge_reconciliation;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class RechargeReconciliationIntegrationTest {
    /** 实际订单与调度状态数据库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 实际公开充值服务。 */
    @Autowired private RechargeCommandService recharge;
    /** 实际后台核对及用户锁。 */
    @Autowired private RechargeOrderReconciliationService reconciliations;
    /** 已验签通知的实际业务入口。 */
    @Autowired private WechatVirtualPaymentNotificationService notifications;
    /** 后台内部入口使用的实际用户互斥。 */
    @Autowired private UserPointMutex mutex;
    /** 实际开关配置。 */
    @Autowired private WechatVirtualPaymentProperties properties;
    /** 外部微信查询边界，不连接微信。 */
    @MockBean private WechatVirtualPaymentClient client;
    /** 已确认身份与会话。 */
    @MockBean private UserAuthEntityMapper auths;
    @MockBean private MaintainerWechatSessionService sessions;
    /** 充值流水与赠送由已有幂等服务负责，此处验证编排调用次数。 */
    @MockBean private PointService points;
    @MockBean private PointCommandService commands;
    @MockBean private PointDebitTaskService tasks;
    /** 退款查余额失败时必须仍然保留已提交的失效标记。 */
    @MockBean private WechatAuthoritativeBalanceSyncService balances;
    /** 内部密钥另由应用服务和 Controller 契约测试覆盖。 */
    @MockBean private AdminPointSecretValidator secrets;

    /** 建立订单真实字段，使用已经关闭但微信实际已支付的历史订单。 */
    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        jdbc.execute("""
                CREATE TABLE wf_recharge_order (
                    id BIGINT PRIMARY KEY, merchant_order_no VARCHAR(64), account_id BIGINT, user_id BIGINT,
                    package_id BIGINT, package_snapshot VARCHAR(1000), amount_fen INT, base_points INT,
                    bonus_points INT, total_points INT, buy_quantity BIGINT, status VARCHAR(32), pay_channel VARCHAR(32),
                    wechat_order_id VARCHAR(64), channel_order_id VARCHAR(64), wxpay_order_id VARCHAR(64), paid_fee BIGINT,
                    point_transaction_id BIGINT, bonus_gift_order_id BIGINT, last_query_error_code VARCHAR(64),
                    last_query_error_message VARCHAR(255), last_query_error_at TIMESTAMP, paid_at TIMESTAMP,
                    closed_at TIMESTAMP, refunded_at TIMESTAMP, expire_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    deleted BIGINT DEFAULT 0, version INT DEFAULT 0
                )
                """);
        // 执行本轮真实迁移，保证生产调度字段与实际 Mapper 使用相同定义。
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V58__add_recharge_order_reconciliation.sql"))
                .execute(jdbc.getDataSource());
        jdbc.execute("""
                CREATE TABLE wf_point_account (
                    id BIGINT PRIMARY KEY, wechat_balance_synced_at TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, version INT DEFAULT 0, deleted BIGINT DEFAULT 0
                )
                """);
        jdbc.update("INSERT INTO wf_point_account (id, wechat_balance_synced_at) VALUES (10, CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO wf_recharge_order (id, merchant_order_no, account_id, user_id, package_snapshot,
                        amount_fen, base_points, bonus_points, total_points, buy_quantity, status, pay_channel)
                VALUES (11, 'ORDER', 10, 7, '{"packageName":"测试套餐"}', 5000, 500, 50, 550, 500,
                        'CLOSED', 'WECHAT_VIRTUAL_PAYMENT')
                """);
        UserAuthEntity auth = new UserAuthEntity();
        auth.setOpenId("openid");
        when(auths.selectOne(any())).thenReturn(auth);
        when(sessions.findAvailableSession(7L)).thenReturn(new MaintainerWechatSession(7L, 1L, "session", 1L, "127.0.0.1"));
        when(client.queryOrder(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return remote("PAID", WechatVirtualPaymentErrorType.SUCCESS);
        });
    }

    /** 各测试使用独立数据库内容，不触及真实数据。 */
    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        jdbc.execute("DROP ALL OBJECTS");
    }

    /** 查单成功事实独立落库；旧客户端不再查单时仍由后台恢复且只入账/赠送一次。 */
    @Test
    void paidFactShouldCommitBeforeFailureAndClosedOrderShouldRecoverOnce() {
        when(client.queryUserBalance(any())).thenReturn(remote(null, WechatVirtualPaymentErrorType.TRANSIENT));
        assertThatThrownBy(() -> recharge.syncOrder(7L, "ORDER")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT paid_fee FROM wf_recharge_order", Long.class)).isEqualTo(5000L);
        assertThat(jdbc.queryForObject("SELECT paid_at FROM wf_recharge_order", Timestamp.class)).isNotNull();
        assertThat(status()).isEqualTo("CLOSED");
        verifyNoInteractions(points, commands);

        PointMutationResponse mutation = new PointMutationResponse();
        mutation.setTransactionId(77L);
        mutation.setBalanceAfter(500L);
        when(points.rechargeVirtual(eq(7L), eq(500L), eq("ORDER"), anyString(), anyString(), anyString(), eq(500L), eq(0L)))
                .thenReturn(mutation);
        when(commands.createGiftOrderWithinUserLock(any()))
                .thenReturn(new GiftOrderResult(List.of(new GiftOrderResult.GiftOrderItem(88L, "GIFT", 7L, PointGiftOrderStatusDict.READY.getCode(), false))));
        when(client.queryUserBalance(any())).thenReturn(remote(null, WechatVirtualPaymentErrorType.SUCCESS));

        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SUCCEEDED");
        assertThat(status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT point_transaction_id FROM wf_recharge_order", Long.class)).isEqualTo(77L);
        assertThat(jdbc.queryForObject("SELECT bonus_gift_order_id FROM wf_recharge_order", Long.class)).isEqualTo(88L);
        assertThat(jdbc.queryForObject("SELECT next_query_at FROM wf_recharge_order", Timestamp.class)).isNull();
        clearInvocations(client);
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");
        jdbc.update("UPDATE wf_recharge_order SET next_query_at = ?", Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");
        verifyNoInteractions(client);
        verify(points, times(1)).rechargeVirtual(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
        verify(commands, times(1)).createGiftOrderWithinUserLock(any());
    }

    /** 已入账订单不主动查退款，保留本地状态和余额快照直到收到实际退款事件。 */
    @Test
    void paidOrderShouldNotQueryWechatForRefunds() {
        jdbc.update("UPDATE wf_recharge_order SET status = 'PAID', paid_fee = 5000");

        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");

        assertThat(status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT wechat_balance_synced_at FROM wf_point_account", Timestamp.class)).isNotNull();
        verifyNoInteractions(client, sessions, balances, points, commands);
    }

    /** 持锁内部入口同样快速返回已入账订单，防止绕过候选筛选时恢复全量退款查单。 */
    @Test
    void paidInternalSyncShouldReturnWithoutRemoteQueries() {
        jdbc.update("UPDATE wf_recharge_order SET status = 'PAID', paid_fee = 5000");
        PointAccountEntity account = new PointAccountEntity();
        account.setBalance(500L);
        when(points.ensureAccount(7L)).thenReturn(account);

        var response = mutex.execute(7L, () -> recharge.reconcileOrderWithinUserLock(7L, "ORDER"));

        assertThat(response.getStatus()).isEqualTo("PAID");
        assertThat(response.getBalance()).isEqualTo(500L);
        assertThat(response.isConfirmed()).isTrue();
        verifyNoInteractions(client, sessions, balances, commands);
    }

    /** 已入账订单收到退款通知后仍落库退款事实；余额尚未同步时保留恢复标记。 */
    @Test
    void paidOrderShouldProcessRefundNotificationWithoutPolling() {
        jdbc.update("UPDATE wf_recharge_order SET status = 'PAID', paid_fee = 5000");

        String response = notifications.handle(new WechatVirtualPaymentNotification("xpay_refund_notify", "ORDER"));

        assertThat(response).contains("SUCCESS");
        assertThat(status()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refunded_at FROM wf_recharge_order", Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT wechat_balance_synced_at FROM wf_point_account", Timestamp.class)).isNull();
        verify(balances).synchronizeIfSessionAvailable(7L, 10L, "ORDER");
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");
        verifyNoInteractions(client, points, commands);
    }

    /** 缺失、零值或不匹配的金额不能持久化为收款事实，也不能获得充值或套餐赠送。 */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 4999L})
    void invalidAmountShouldRemainUnsettledAndRetryable(long amount) {
        when(client.queryOrder(any())).thenReturn(new WechatVirtualPaymentResult(0, null,
                WechatVirtualPaymentErrorType.SUCCESS, 0L, 0L, 0L, "PAID", 500L, amount,
                200, "openid", 0, "ORDER"));
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("SELECT paid_fee FROM wf_recharge_order", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT next_query_at FROM wf_recharge_order", Timestamp.class)).isNotNull();
        assertThat(status()).isEqualTo("CLOSED");
        verify(client, never()).queryUserBalance(any());
        verifyNoInteractions(points, commands);
    }

    /** 后台成功查询到关闭仍每天核对；暂时查询故障只更新调度计划，不改业务状态。 */
    @Test
    void closedRemoteOrderAndTransientFailureShouldBothRemainRecoverable() {
        when(client.queryOrder(any())).thenReturn(remote("CLOSED", WechatVirtualPaymentErrorType.SUCCESS));
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SUCCEEDED");
        assertThat(status()).isEqualTo("CLOSED");
        Timestamp stableNext = jdbc.queryForObject("SELECT next_query_at FROM wf_recharge_order", Timestamp.class);
        assertThat(stableNext.toLocalDateTime()).isAfter(LocalDateTime.now().plusHours(23));
        jdbc.update("UPDATE wf_recharge_order SET next_query_at = NULL");
        when(client.queryOrder(any())).thenReturn(remote(null, WechatVirtualPaymentErrorType.TRANSIENT));
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("RETRY_WAIT");
        assertThat(status()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT query_retry_count FROM wf_recharge_order", Integer.class)).isEqualTo(1);
        verifyNoInteractions(points, commands);
    }

    /** 旧公开接口退款收口仍兼容；已核实收款的失败订单必须继续由后台补退款失效标记。 */
    @Test
    void confirmedPaymentRefundedByPublicSyncShouldRemainBackgroundCandidate() {
        jdbc.update("UPDATE wf_recharge_order SET paid_fee = 5000, paid_at = CURRENT_TIMESTAMP");
        when(client.queryOrder(any())).thenReturn(remote("REFUND", WechatVirtualPaymentErrorType.SUCCESS));
        assertThat(recharge.syncOrder(7L, "ORDER").getStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(reconciliations.reconcile("secret", 11L).outcome()).isEqualTo("SUCCEEDED");
        assertThat(status()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT wechat_balance_synced_at FROM wf_point_account", Timestamp.class)).isNull();
        verify(balances).synchronizeWithinUserLock(7L, 10L, "ORDER");
        verifyNoInteractions(points, commands);
    }

    /** 读回真实持久化订单状态。 */
    private String status() {
        return jdbc.queryForObject("SELECT status FROM wf_recharge_order", String.class);
    }

    /** 构造固定身份和金额的远端结果，不包含任何真实微信数据。 */
    private WechatVirtualPaymentResult remote(String status, WechatVirtualPaymentErrorType type) {
        return new WechatVirtualPaymentResult(type == WechatVirtualPaymentErrorType.SUCCESS ? 0 : -1,
                null, type, 500L, 0L, 0L, status, 500L, 5000L, 200, "openid", 0, "ORDER");
    }
}
