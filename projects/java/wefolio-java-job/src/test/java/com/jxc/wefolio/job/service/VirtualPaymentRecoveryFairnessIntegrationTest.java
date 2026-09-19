package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VirtualPaymentDispatchProperties;
import com.jxc.wefolio.job.config.AdminPointProperties;
import com.jxc.wefolio.job.config.RuntimeProperties;
import com.jxc.wefolio.job.repo.VirtualPaymentCandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 使用真实候选 SQL 验证持续失败账户不会饿死其他恢复请求。 */
class VirtualPaymentRecoveryFairnessIntegrationTest {

    /** 退款恢复拥有独立额度，失败账户不写回时仍轮转到后续账户并最终回绕。 */
    @Test
    void stuckRefundsShouldNotBlockMissingDebitsOrLaterRefunds() {
        JdbcTemplate jdbc = database();
        for (long id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO wf_point_account VALUES (?, ?, 0, NULL, 0)", id, id + 100);
            jdbc.update("INSERT INTO wf_recharge_order (id, account_id, user_id, status, deleted) "
                    + "VALUES (?, ?, ?, 'REFUNDED', 0)", id, id, id + 100);
            jdbc.update("INSERT INTO wf_maintainer_wechat_session VALUES (?, 'AVAILABLE', 0)", id + 100);
        }
        jdbc.update("INSERT INTO wf_point_account VALUES (4, 104, 20, NULL, 0)");
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        List<Long> requested = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            requested.add(invocation.getArgument(0));
            throw new IllegalStateException("恢复失败且没有修改候选状态");
        }).when(client).recoverDebitTask(anyLong());
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        properties.setBatchSize(2);
        VirtualPaymentDispatchService service = new VirtualPaymentDispatchService(
                new VirtualPaymentCandidateRepository(jdbc), client, properties, Executors.newFixedThreadPool(2));
        try {
            service.dispatchDebitTasks();
            assertThat(requested).containsExactlyInAnyOrder(101L, 102L, 104L);
            requested.clear();

            service.dispatchDebitTasks();
            assertThat(requested).containsExactlyInAnyOrder(103L, 104L);
            requested.clear();

            service.dispatchDebitTasks();
            assertThat(requested).containsExactlyInAnyOrder(101L, 102L, 104L);
        } finally {
            service.shutdown();
        }
    }

    /** 同一账户同时需要补建和退款恢复时，同轮只调用一次，不能把失败变成隐式重试。 */
    @Test
    void overlappingRecoveryCandidatesShouldBeRequestedOnlyOncePerRound() {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO wf_point_account VALUES (1, 101, 20, NULL, 0)");
        jdbc.update("""
                INSERT INTO wf_recharge_order (id, account_id, user_id, status, deleted)
                VALUES (1, 1, 101, 'REFUNDED', 0)
                """);
        jdbc.update("INSERT INTO wf_maintainer_wechat_session VALUES (101, 'AVAILABLE', 0)");
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        List<Long> requested = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            requested.add(invocation.getArgument(0));
            throw new IllegalStateException("恢复失败");
        }).when(client).recoverDebitTask(anyLong());
        VirtualPaymentDispatchService service = new VirtualPaymentDispatchService(
                new VirtualPaymentCandidateRepository(jdbc), client,
                new VirtualPaymentDispatchProperties(), Executors.newFixedThreadPool(2));
        try {
            service.dispatchDebitTasks();
            assertThat(requested).containsExactly(101L);
        } finally {
            service.shutdown();
        }
    }

    /** 充值候选缺少新迁移字段时，原有活动扣币仍能执行，不能让新增补偿阻断既有任务。 */
    @Test
    void rechargeCandidateQueryFailureShouldNotBlockExistingDebitTasks() {
        JdbcTemplate jdbc = database();
        jdbc.execute("ALTER TABLE wf_recharge_order DROP COLUMN next_query_at");
        jdbc.update("""
                INSERT INTO wf_point_debit_task VALUES
                    (23, 101, 'WAITING', CURRENT_TIMESTAMP, NULL, 1, 0)
                """);
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        List<Long> requested = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            requested.add(invocation.getArgument(0));
            RuntimeVirtualPaymentTaskClient.TaskExecutionResult result =
                    new RuntimeVirtualPaymentTaskClient.TaskExecutionResult();
            result.setTargetId(23L);
            result.setTaskType("DEBIT_TASK");
            result.setOutcome("PROCESSED");
            return result;
        }).when(client).executeDebitTask(anyLong());
        VirtualPaymentDispatchService service = new VirtualPaymentDispatchService(
                new VirtualPaymentCandidateRepository(jdbc), client,
                new VirtualPaymentDispatchProperties(), Executors.newFixedThreadPool(2));
        try {
            assertThatCode(service::dispatchDebitTasks).doesNotThrowAnyException();
            assertThat(requested).containsExactly(23L);
        } finally {
            service.shutdown();
        }
    }

    /** 失败状态仅在已确认收款时继续核对，且不能绕过渠道、删除、会话和到期限制。 */
    @Test
    void failedRechargeShouldRemainRecoverableOnlyWithConfirmedPayment() {
        JdbcTemplate jdbc = database();
        Long[] paidFees = {100L, null, 0L, 100L, 100L, 100L, 100L};
        for (long id = 1; id <= paidFees.length; id++) {
            jdbc.update("""
                    INSERT INTO wf_recharge_order (id, user_id, status, deleted, pay_channel, next_query_at, paid_fee)
                    VALUES (?, ?, 'PAYMENT_FAILED', ?, ?, ?, ?)
                    """, id, id + 100, id == 5 ? 5L : 0L,
                    id == 4 ? "OTHER" : "WECHAT_VIRTUAL_PAYMENT",
                    id == 7 ? LocalDateTime.now().plusDays(1) : null, paidFees[(int) id - 1]);
            if (id != 6) {
                jdbc.update("INSERT INTO wf_maintainer_wechat_session VALUES (?, 'AVAILABLE', 0)", id + 100);
            }
        }

        assertThat(new VirtualPaymentCandidateRepository(jdbc).findDueRechargeOrderIds(0L, 100))
                .containsExactly(1L);
    }

    /** 待支付、已关闭和确认收款失败单继续核对，已支付单不再扫描，HTTP 失败不阻塞后续订单。 */
    @Test
    void shouldReconcileRecoverableOrdersFairlyThroughAuthenticatedRuntimeRequests() {
        JdbcTemplate jdbc = database();
        for (long id = 1; id <= 10; id++) {
            String status = switch ((int) id) {
                case 2 -> "CLOSED";
                case 3, 8, 10 -> "PAID";
                case 5 -> "REFUNDED";
                case 9 -> "PAYMENT_FAILED";
                default -> "PENDING_PAYMENT";
            };
            jdbc.update("""
                    INSERT INTO wf_recharge_order (id, user_id, status, deleted, pay_channel, next_query_at, paid_fee)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, id + 100, status, id == 8 ? 8L : 0L,
                    id == 7 ? "OTHER" : "WECHAT_VIRTUAL_PAYMENT",
                    id == 4 ? LocalDateTime.now().plusDays(1)
                            : id == 10 ? LocalDateTime.now().minusDays(30) : null,
                    id == 3 || id >= 8 ? 100L : 0L);
            if (id != 6) {
                jdbc.update("INSERT INTO wf_maintainer_wechat_session VALUES (?, 'AVAILABLE', 0)", id + 100);
            }
        }
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        for (long id : List.of(1L, 2L, 9L)) {
            var expectation = server.expect(requestTo(
                            "https://runtime.example.com/api/admin/points/virtual-payment/recharge-orders/"
                                    + id + "/reconciliations"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Admin-Point-Secret", "test-secret"));
            if (id == 1) {
                expectation.andRespond(withServerError());
            } else {
                expectation.andRespond(withSuccess("""
                        {"success":true,"data":{"targetId":%d,"taskType":"RECHARGE_ORDER","outcome":"SUCCEEDED"}}
                        """.formatted(id), MediaType.APPLICATION_JSON));
            }
        }
        RuntimeProperties runtime = new RuntimeProperties();
        runtime.setBaseUrl("https://runtime.example.com");
        AdminPointProperties admin = new AdminPointProperties();
        admin.setSecret("test-secret");
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        properties.setBatchSize(2);
        VirtualPaymentDispatchService service = new VirtualPaymentDispatchService(
                new VirtualPaymentCandidateRepository(jdbc),
                new RuntimeVirtualPaymentTaskClient(runtime, admin, builder.build()),
                properties, Executors.newFixedThreadPool(2));
        try {
            service.dispatchDebitTasks();
            service.dispatchDebitTasks();
            server.verify();
        } finally {
            service.shutdown();
        }
    }

    /** 创建与本轮候选查询一致的最小共享表结构。 */
    private JdbcTemplate database() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:recovery_fairness_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE wf_point_account (
                    id BIGINT PRIMARY KEY, user_id BIGINT, pending_debit BIGINT,
                    wechat_balance_synced_at TIMESTAMP, deleted BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE wf_point_debit_task (
                    id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(32), next_execute_at TIMESTAMP,
                    lease_until TIMESTAMP, active_flag INTEGER, deleted BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE wf_recharge_order (
                    id BIGINT PRIMARY KEY, account_id BIGINT, user_id BIGINT, status VARCHAR(32), deleted BIGINT,
                    pay_channel VARCHAR(32), next_query_at TIMESTAMP, paid_fee BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE wf_maintainer_wechat_session (user_id BIGINT, status VARCHAR(32), deleted BIGINT)
                """);
        return jdbc;
    }
}
