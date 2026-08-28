package com.jxc.wefolio.mapper;

import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.entity.FeedbackUploadTaskEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意见反馈 Mapper 可执行 SQL 集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class FeedbackEntityMapperIntegrationTest {

    /** H2 数据库操作入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 待验证的意见反馈 Mapper。 */
    @Autowired
    private FeedbackEntityMapper feedbackEntityMapper;

    /** 待验证的反馈上传任务 Mapper。 */
    @Autowired
    private FeedbackUploadTaskEntityMapper feedbackUploadTaskEntityMapper;

    /** 创建最小反馈表并插入用户隔离测试数据。 */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback_upload_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback");
        jdbcTemplate.execute("""
                CREATE TABLE wf_feedback (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  create_idempotency_key VARCHAR(64),
                  deleted BIGINT NOT NULL
                )
                """);
        insertFeedback(1L, 7L, FeedbackStatusDict.PROCESSING.getCode(), 0L);
        insertFeedback(2L, 7L, FeedbackStatusDict.RESOLVED.getCode(), 0L);
        insertFeedback(3L, 8L, FeedbackStatusDict.PROCESSING.getCode(), 0L);
        insertFeedback(4L, 7L, FeedbackStatusDict.PROCESSING.getCode(), 4L);
        jdbcTemplate.execute("""
                CREATE TABLE wf_feedback_upload_task (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  deleted BIGINT NOT NULL
                )
                """);
        insertUploadTask(101L, 7L);
        insertUploadTask(102L, 8L);
    }

    /** 删除测试创建的最小反馈表。 */
    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback_upload_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback");
    }

    /** 非空状态集合应按用户、状态和逻辑删除条件正确计数。 */
    @Test
    void countActiveByUserIdCountsOnlyMatchingActiveRows() {
        int count = feedbackEntityMapper.countActiveByUserId(
                7L,
                List.of(
                        FeedbackStatusDict.PROCESSING.getCode(),
                        FeedbackStatusDict.RESOLVED.getCode()
                )
        );

        assertThat(count).isEqualTo(2);
    }

    /** 空状态集合应返回零，不应生成无效 IN SQL。 */
    @Test
    void countActiveByUserIdReturnsZeroForEmptyStatuses() {
        int count = feedbackEntityMapper.countActiveByUserId(7L, List.of());

        assertThat(count).isZero();
    }

    /** null 状态集合应返回零，不应触发 foreach 参数异常。 */
    @Test
    void countActiveByUserIdReturnsZeroForNullStatuses() {
        int count = feedbackEntityMapper.countActiveByUserId(7L, null);

        assertThat(count).isZero();
    }

    /** 创建幂等查询必须同时按用户和幂等键隔离。 */
    @Test
    void selectByUserIdAndCreateIdempotencyKeyEnforcesUserIsolation() {
        jdbcTemplate.update("UPDATE wf_feedback SET create_idempotency_key = ? WHERE id = ?", "same-key", 1L);
        jdbcTemplate.update("UPDATE wf_feedback SET create_idempotency_key = ? WHERE id = ?", "same-key", 3L);

        assertThat(feedbackEntityMapper.selectByUserIdAndCreateIdempotencyKey(7L, "same-key"))
                .extracting(feedback -> feedback.getId())
                .isEqualTo(1L);
        assertThat(feedbackEntityMapper.selectByUserIdAndCreateIdempotencyKey(8L, "same-key"))
                .extracting(feedback -> feedback.getId())
                .isEqualTo(3L);
        assertThat(feedbackEntityMapper.selectByUserIdAndCreateIdempotencyKey(9L, "same-key"))
                .isNull();
    }

    /** 上传任务行锁查询必须按用户 ID 隔离，不能读取其他用户任务。 */
    @Test
    @Transactional
    void lockUploadTaskByIdAndUserIdEnforcesUserIsolation() {
        assertThat(feedbackUploadTaskEntityMapper.lockByIdAndUserId(101L, 8L)).isNull();
        assertThat(feedbackUploadTaskEntityMapper.lockByIdAndUserId(102L, 7L)).isNull();

        FeedbackUploadTaskEntity first = feedbackUploadTaskEntityMapper.lockByIdAndUserId(101L, 7L);
        FeedbackUploadTaskEntity second = feedbackUploadTaskEntityMapper.lockByIdAndUserId(102L, 8L);
        assertThat(first).isNotNull();
        assertThat(first.getId()).isEqualTo(101L);
        assertThat(first.getUserId()).isEqualTo(7L);
        assertThat(second).isNotNull();
        assertThat(second.getId()).isEqualTo(102L);
        assertThat(second.getUserId()).isEqualTo(8L);
    }

    /**
     * 插入一条最小反馈测试数据。
     *
     * @param id 反馈 ID
     * @param userId 用户 ID
     * @param status 反馈状态编码
     * @param deleted 逻辑删除值
     */
    private void insertFeedback(long id, long userId, String status, long deleted) {
        jdbcTemplate.update(
                "INSERT INTO wf_feedback (id, user_id, status, deleted) VALUES (?, ?, ?, ?)",
                id,
                userId,
                status,
                deleted
        );
    }

    /** 插入一条最小反馈上传任务测试数据。 */
    private void insertUploadTask(long id, long userId) {
        jdbcTemplate.update(
                "INSERT INTO wf_feedback_upload_task (id, user_id, deleted) VALUES (?, ?, 0)",
                id,
                userId
        );
    }
}
