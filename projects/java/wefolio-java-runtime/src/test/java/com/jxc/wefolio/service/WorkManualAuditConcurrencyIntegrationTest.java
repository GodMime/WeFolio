package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.WorkManualAuditUpdateRequest;
import com.jxc.wefolio.dto.WorkManualAuditUpdateResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.WorkManualAuditMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品人工审核行锁与 first-write-wins 并发集成测试。 */
@ActiveProfiles("test")
@SpringBootTest
class WorkManualAuditConcurrencyIntegrationTest {

    /** 固定人工审核编号。 */
    private static final String MANUAL_NO = "WA20260827153042A7K2Q9";

    /** 并发任务等待上限秒数。 */
    private static final long TIMEOUT_SECONDS = 5L;

    /** 集成测试数据库访问器。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 通过 Spring 事务代理调用的人工审核服务。 */
    @Autowired
    private WorkManualAuditService service;

    /** 重建最小作品表并准备人工审核中记录。 */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_work");
        jdbcTemplate.execute("""
                CREATE TABLE wf_work (
                  id BIGINT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  audit_status VARCHAR(32) NOT NULL,
                  audit_reason_code VARCHAR(64),
                  audit_reason_codes VARCHAR(1024),
                  audit_reject_reason VARCHAR(512),
                  manual_audit_no CHAR(34),
                  manual_audit_result_at TIMESTAMP(3),
                  updated_at TIMESTAMP(3) NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0
                )
                """);
        resetAuditingWork();
    }

    /** 清理集成测试作品表。 */
    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_work");
    }

    /** 相同并发结论只能首次写入一次，另一请求幂等重放同一时间。 */
    @Test
    void sameConcurrentConclusionChangesOnceAndReplaysOnce() throws Exception {
        List<Outcome> outcomes = runConcurrently(
                request("REJECTED", "同一原因"),
                request("REJECTED", "同一原因"));

        assertThat(outcomes).extracting(Outcome::changed)
                .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes).allMatch(outcome -> outcome.errorMessage() == null);
        assertThat(outcomes.get(0).resultAt()).isNotNull();
        assertThat(outcomes.get(1).resultAt()).isEqualTo(outcomes.get(0).resultAt());
        Timestamp resultAt = jdbcTemplate.queryForObject(
                "SELECT manual_audit_result_at FROM wf_work WHERE id = 91", Timestamp.class);
        assertThat(resultAt).isNotNull();
        assertThat(resultAt.toLocalDateTime()).isEqualTo(outcomes.get(0).resultAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT audit_reject_reason FROM wf_work WHERE id = 91", String.class))
                .isEqualTo("同一原因");
    }

    /** 冲突并发结论只能保留首个结果，另一请求返回冲突。 */
    @Test
    void conflictingConcurrentConclusionsKeepOnlyFirstResult() throws Exception {
        List<Outcome> outcomes = runConcurrently(
                request("PASSED", null),
                request("REJECTED", "人工拒绝"));

        assertThat(outcomes).filteredOn(outcome -> outcome.errorMessage() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> WorkManualAuditMessage.RESULT_CONFLICT_MESSAGE
                .equals(outcome.errorMessage())).hasSize(1);
        Outcome winner = outcomes.stream()
                .filter(outcome -> outcome.errorMessage() == null)
                .findFirst()
                .orElseThrow();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT audit_status FROM wf_work WHERE id = 91", String.class))
                .isEqualTo(winner.status());
    }

    /** 在两个线程同时执行给定人工审核请求。 */
    private List<Outcome> runConcurrently(
            WorkManualAuditUpdateRequest firstRequest,
            WorkManualAuditUpdateRequest secondRequest
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Outcome> first = executor.submit(() -> updateAfterStart(start, firstRequest));
            Future<Outcome> second = executor.submit(() -> updateAfterStart(start, secondRequest));
            return List.of(
                    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 等待并发起点后执行一次请求，并将业务异常转换为测试结果。 */
    private Outcome updateAfterStart(
            CyclicBarrier start,
            WorkManualAuditUpdateRequest request
    ) throws Exception {
        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            WorkManualAuditUpdateResponse response = service.update(MANUAL_NO, request);
            return new Outcome(
                    response.getAuditStatus(), response.isChanged(),
                    response.getManualAuditResultAt(), null);
        } catch (BusinessException exception) {
            return new Outcome(request.getStatus(), false, null, exception.getMessage());
        }
    }

    /** 构建人工审核回传请求。 */
    private WorkManualAuditUpdateRequest request(String status, String reason) {
        WorkManualAuditUpdateRequest request = new WorkManualAuditUpdateRequest();
        request.setStatus(status);
        request.setAuditRejectReason(reason);
        return request;
    }

    /** 重置一条有效人工审核中作品。 */
    private void resetAuditingWork() {
        jdbcTemplate.update("DELETE FROM wf_work");
        jdbcTemplate.update("""
                INSERT INTO wf_work (
                  id, status, audit_status, manual_audit_no, updated_at, deleted, version
                ) VALUES (91, 'ACTIVE', 'AUDITING', ?, CURRENT_TIMESTAMP, 0, 0)
                """, MANUAL_NO);
    }

    /** 单次并发回传的状态、变更标记、结果时间和错误文案。 */
    private record Outcome(
            String status,
            boolean changed,
            LocalDateTime resultAt,
            String errorMessage
    ) {
    }
}
