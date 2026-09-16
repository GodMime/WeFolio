package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.InsufficientPointBalanceException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.point.DebitCommand;
import com.jxc.wefolio.service.point.PointCommandService;
import com.jxc.wefolio.service.point.PointMutationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 退款余额恢复的真实事务回归：账户、流水、待扣均走实际 H2 与 Spring 事务代理。
 * H2 使用默认 READ_COMMITTED，仅验证事务挂起/提交与扣费边界，不替代 MySQL RR 的当前读验证。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund_balance_transaction;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class RefundBalanceTransactionIntegrationTest {

    /** 真实数据库连接，用于布置账户并核对提交结果。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 真实事务管理器，显式启动调用方的外层事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 实际积分扣费与退款恢复调用链。 */
    @Autowired private PointCommandService commands;
    /** 测试期间开启已有虚拟支付能力。 */
    @Autowired private WechatVirtualPaymentProperties properties;
    /** 仅隔离与资金 SQL 无关的用户资料。 */
    @MockBean private UserEntityMapper users;
    /** 仅隔离微信身份资料查询。 */
    @MockBean private UserAuthEntityMapper auths;
    /** 外部微信会话使用可控测试值。 */
    @MockBean private MaintainerWechatSessionService sessions;
    /** 微信远端余额可控，不连接真实微信。 */
    @MockBean private WechatVirtualPaymentClient client;
    /** 活动任务生成不属于本测试的账户与流水事务边界。 */
    @MockBean private PointDebitTaskService tasks;

    /** 布置余额 100、有退款事实、尚未同步且没有待扣的账户。 */
    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        createTables();
        jdbcTemplate.update("""
                INSERT INTO wf_point_account
                    (id, user_id, balance, wechat_balance, wechat_present_balance, pending_debit,
                     total_recharged, total_gifted, total_consumed)
                VALUES (10, 7, 100, 100, 0, 0, 100, 0, 0)
                """);
        jdbcTemplate.update("INSERT INTO wf_recharge_order VALUES (1, 10, 7, 'REFUNDED', 0)");
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(users.selectById(7L)).thenReturn(user);
        UserAuthEntity auth = new UserAuthEntity();
        auth.setOpenId("openid-test");
        when(auths.selectOne(any())).thenReturn(auth);
        when(sessions.findAvailableSession(7L))
                .thenReturn(new MaintainerWechatSession(7L, 2L, "session-test", 1L, "127.0.0.1"));
    }

    /** 清理专属内存数据库，恢复配置，避免测试状态传播。 */
    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    /** 先读旧余额再挂起同步，消费和流水必须使用独立事务已提交的新余额。 */
    @Test
    void outerTransactionShouldDeductAndRecordLedgerFromSynchronizedBalance() {
        mockAuthoritativeBalance(30L);
        PointMutationResult result = new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(accountValue("balance")).isEqualTo(100L);
            return commands.deductForMaintainer(command());
        });

        assertThat(result).isNotNull();
        assertThat(result.balanceBefore()).isEqualTo(30L);
        assertThat(result.balanceAfter()).isEqualTo(20L);
        assertThat(accountValue("balance")).isEqualTo(20L);
        assertThat(accountValue("wechat_balance")).isEqualTo(30L);
        assertThat(accountValue("pending_debit")).isEqualTo(10L);
        assertThat(accountValue("total_consumed")).isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject("SELECT balance_before FROM wf_point_transaction", Long.class))
                .isEqualTo(30L);
        assertThat(jdbcTemplate.queryForObject("SELECT balance_after FROM wf_point_transaction", Long.class))
                .isEqualTo(20L);
        assertThat(jdbcTemplate.queryForObject("SELECT remaining_amount FROM wf_point_pending_debit", Long.class))
                .isEqualTo(10L);
        assertSynced();
    }

    /** 余额同步到零后扣费被拒绝，外层回滚不能撤销独立余额同步或留下消费流水。 */
    @Test
    void failedOuterConsumptionShouldKeepCommittedRefundBalanceWithoutLedger() {
        mockAuthoritativeBalance(0L);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(accountValue("balance")).isEqualTo(100L);
            return commands.deductForMaintainer(command());
        })).isInstanceOf(InsufficientPointBalanceException.class);

        assertThat(accountValue("balance")).isZero();
        assertThat(accountValue("wechat_balance")).isZero();
        assertThat(accountValue("pending_debit")).isZero();
        assertThat(accountValue("total_consumed")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wf_point_transaction", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wf_point_pending_debit", Long.class)).isZero();
        assertSynced();
    }

    /** 验证远端调用时业务事务确实已挂起，并返回需要独立提交的权威余额。 */
    private void mockAuthoritativeBalance(long balance) {
        when(client.queryUserBalance(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new WechatVirtualPaymentResult(0, null, WechatVirtualPaymentErrorType.SUCCESS,
                    balance, 0L, 0L, null, 0L, 0L, 200);
        });
    }

    /** 构造一笔有固定幂等身份的维护者消费。 */
    private DebitCommand command() {
        return new DebitCommand(7L, null, PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode(),
                "TEST", "1", "{}", "refund-consumption", null, 10L);
    }

    /** 读取已提交账户聚合值。 */
    private Long accountValue(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM wf_point_account WHERE id = 10", Long.class);
    }

    /** 确认独立同步已清除退款待同步状态。 */
    private void assertSynced() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_point_account WHERE wechat_balance_synced_at IS NOT NULL", Long.class))
                .isEqualTo(1L);
    }

    /** 创建被测实际 Mapper 使用的最小表结构，所有公共字段保留默认值。 */
    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_account (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                  balance BIGINT NOT NULL, wechat_balance BIGINT NOT NULL,
                  wechat_present_balance BIGINT NOT NULL, pending_debit BIGINT NOT NULL,
                  wechat_balance_synced_at TIMESTAMP NULL, total_recharged BIGINT NOT NULL,
                  total_gifted BIGINT NOT NULL, total_consumed BIGINT NOT NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_recharge_order (
                  id BIGINT PRIMARY KEY, account_id BIGINT, user_id BIGINT, status VARCHAR(32), deleted BIGINT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_transaction (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT, user_id BIGINT, rule_id BIGINT,
                  transaction_type VARCHAR(32), scene_code VARCHAR(64), points_change BIGINT,
                  balance_before BIGINT, balance_after BIGINT, business_type VARCHAR(32), business_id VARCHAR(64),
                  calculation_snapshot VARCHAR(1024), idempotency_key VARCHAR(128) UNIQUE,
                  remark VARCHAR(255), occurred_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_pending_debit (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT, user_id BIGINT,
                  point_transaction_id BIGINT, original_amount BIGINT, remaining_amount BIGINT,
                  last_settled_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0
                )
                """);
    }
}
