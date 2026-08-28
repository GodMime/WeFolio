package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 问题反馈容量锁与附件确认回滚的真实事务集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class FeedbackTransactionIntegrationTest {

    /** 集成测试固定用户 ID。 */
    private static final long USER_ID = 7L;

    /** 并发等待超时秒数。 */
    private static final long AWAIT_TIMEOUT_SECONDS = 5L;

    /** 判断第二事务仍被行锁阻塞的超时毫秒数。 */
    private static final long LOCK_PROBE_TIMEOUT_MILLIS = 500L;

    /** H2 数据库操作入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 真实 Spring 事务代理。 */
    @Autowired
    private FeedbackTransactionService transactionService;

    /** 附件服务替身，用于把事务停在插入后和模拟确认失败。 */
    @MockBean
    private FeedbackUploadService feedbackUploadService;

    /** 创建最小真实表和两个活跃反馈。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.clear();
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_user");
        jdbcTemplate.execute("""
                CREATE TABLE wf_user (
                  id BIGINT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  deleted BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_feedback (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  feedback_no VARCHAR(34) NOT NULL,
                  user_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  feedback_result VARCHAR(200),
                  feedback_result_at TIMESTAMP(3),
                  rounds_json CLOB NOT NULL,
                  round_count INT NOT NULL,
                  attachment_count INT NOT NULL,
                  create_idempotency_key VARCHAR(64) NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL,
                  updated_at TIMESTAMP(3) NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0,
                  CONSTRAINT uk_test_feedback_no UNIQUE (feedback_no, deleted),
                  CONSTRAINT uk_test_feedback_create UNIQUE (user_id, create_idempotency_key, deleted)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO wf_user (id, status, deleted) VALUES (?, 'ACTIVE', 0)", USER_ID);
        insertActiveFeedback(1L, "FB" + "1".repeat(32), "existing-1");
        insertActiveFeedback(2L, "FB" + "2".repeat(32), "existing-2");
        when(feedbackUploadService.validateAndBuildAttachments(anyLong(), anyList()))
                .thenReturn(List.of());
    }

    /** 删除测试表并清理主线程认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_user");
    }

    /** 两个不同幂等键并发争夺最后一个名额时只能有一个成功。 */
    @Test
    void concurrentCreatesShouldAllowExactlyOneLastActiveSlot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstReachedConfirmation = new CountDownLatch(1);
        CountDownLatch releaseFirstCreate = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstReachedConfirmation.countDown();
            if (!releaseFirstCreate.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("首个反馈创建等待释放超时");
            }
            return null;
        }).when(feedbackUploadService).confirmTasks(anyLong(), anyList(), anyLong(), anyInt());

        try {
            Future<Boolean> first = executor.submit(() -> createAndReportSuccess("concurrent-a"));
            assertThat(firstReachedConfirmation.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = executor.submit(() -> {
                secondStarted.countDown();
                return createAndReportSuccess("concurrent-b");
            });
            assertThat(secondStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(LOCK_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstCreate.countDown();
            assertThat(List.of(
                    first.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            releaseFirstCreate.countDown();
            executor.shutdownNow();
        }

        assertThat(activeFeedbackCount()).isEqualTo(3L);
    }

    /** 附件确认异常必须回滚同一事务中已经插入的新反馈。 */
    @Test
    void confirmFailureShouldRollbackInsertedFeedback() {
        doThrow(new IllegalStateException("模拟附件确认失败"))
                .when(feedbackUploadService)
                .confirmTasks(anyLong(), anyList(), anyLong(), anyInt());

        assertThatThrownBy(() -> create("rollback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟附件确认失败");

        assertThat(activeFeedbackCount()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_feedback WHERE create_idempotency_key = 'rollback-key'",
                Long.class)).isZero();
    }

    /** 在线程内设置并清理认证上下文，将业务失败映射为 false。 */
    private boolean createAndReportSuccess(String idempotencyKey) {
        try {
            return create(idempotencyKey).changed();
        } catch (BusinessException exception) {
            return false;
        }
    }

    /** 在线程内设置并清理认证上下文后创建反馈。 */
    private FeedbackTransactionService.MutationResult create(String idempotencyKey) {
        AuthContextHolder.set(new AuthContext(USER_ID, "feedback-integration-token"));
        try {
            MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
            request.setIdempotencyKey(idempotencyKey);
            request.setDescription("并发容量测试问题");
            request.setUploadTaskIds(List.of());
            return transactionService.create(request);
        } finally {
            AuthContextHolder.clear();
        }
    }

    /** 插入一条已存在的活跃反馈。 */
    private void insertActiveFeedback(long id, String feedbackNo, String idempotencyKey) {
        jdbcTemplate.update("""
                        INSERT INTO wf_feedback (
                          id, feedback_no, user_id, status, rounds_json, round_count,
                          attachment_count, create_idempotency_key, created_at, updated_at,
                          deleted, version
                        ) VALUES (?, ?, ?, ?, '[]', 1, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
                        """,
                id,
                feedbackNo,
                USER_ID,
                FeedbackStatusDict.PROCESSING.getCode(),
                idempotencyKey);
    }

    /** 查询当前用户有效的活跃反馈数量。 */
    private long activeFeedbackCount() {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM wf_feedback
                         WHERE user_id = ?
                           AND status IN (?, ?)
                           AND deleted = 0
                        """,
                Long.class,
                USER_ID,
                FeedbackStatusDict.PROCESSING.getCode(),
                FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());
    }
}
