package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.MineFeedbackListResponse;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前用户反馈列表的真实 Mapper 与分页拦截器集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class MineFeedbackPaginationIntegrationTest {

    /** 当前用户 ID。 */
    private static final long USER_ID = 7L;

    /** 其他用户 ID。 */
    private static final long OTHER_USER_ID = 8L;

    /** H2 数据库操作入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 待验证的查询服务。 */
    @Autowired
    private MineFeedbackService mineFeedbackService;

    /** 轮次 JSON 编解码器。 */
    @Autowired
    private FeedbackRoundCodec feedbackRoundCodec;

    /** 创建反馈表和包含隔离、删除、同时间排序边界的数据。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(USER_ID, "feedback-pagination-token"));
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback");
        jdbcTemplate.execute("""
                CREATE TABLE wf_feedback (
                  id BIGINT PRIMARY KEY,
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
                  version INT NOT NULL DEFAULT 0
                )
                """);
        insertFeedback(1L, USER_ID, "最早的问题", time(9), time(10), 0L);
        insertFeedback(2L, USER_ID, "同一更新时间的较小编号", time(10), time(12), 0L);
        insertFeedback(3L, USER_ID, "同一更新时间的较大编号", time(11), time(12), 0L);
        insertFeedback(4L, USER_ID, "已逻辑删除的问题", time(12), time(14), 4L);
        insertFeedback(5L, OTHER_USER_ID, "其他用户的问题", time(13), time(15), 0L);
    }

    /** 删除测试表并清理认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_feedback");
    }

    /** 真实分页 SQL 必须隔离用户、排除逻辑删除并稳定返回末页和越界页。 */
    @Test
    void listShouldExecuteRealPaginationWithIsolationStableOrderingAndPageBoundaries() {
        MineFeedbackListResponse firstPage = mineFeedbackService.list(1, 2);

        assertThat(firstPage.getPageNo()).isEqualTo(1);
        assertThat(firstPage.getPageSize()).isEqualTo(2);
        assertThat(firstPage.getTotal()).isEqualTo(3L);
        assertThat(firstPage.isHasMore()).isTrue();
        assertThat(firstPage.getItems())
                .extracting(MineFeedbackListResponse.Item::getId)
                .containsExactly(3L, 2L);
        assertThat(firstPage.getItems())
                .extracting(MineFeedbackListResponse.Item::getDescriptionSummary)
                .containsExactly("同一更新时间的较大编号", "同一更新时间的较小编号");

        MineFeedbackListResponse lastPage = mineFeedbackService.list(2, 2);

        assertThat(lastPage.getTotal()).isEqualTo(3L);
        assertThat(lastPage.isHasMore()).isFalse();
        assertThat(lastPage.getItems())
                .extracting(MineFeedbackListResponse.Item::getId)
                .containsExactly(1L);

        MineFeedbackListResponse beyondLastPage = mineFeedbackService.list(3, 2);

        assertThat(beyondLastPage.getTotal()).isEqualTo(3L);
        assertThat(beyondLastPage.isHasMore()).isFalse();
        assertThat(beyondLastPage.getItems()).isEmpty();
    }

    /** 插入一条包含合法单轮快照的反馈。 */
    private void insertFeedback(
            long id,
            long userId,
            String description,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            long deleted
    ) {
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(1);
        round.setIdempotencyKey("page-key-" + id);
        round.setDescription(description);
        round.setSubmittedAt(createdAt);
        round.setTeamResult(null);
        round.setTeamResultAt(null);
        round.setAttachments(List.of());
        jdbcTemplate.update("""
                        INSERT INTO wf_feedback (
                          id, feedback_no, user_id, status, rounds_json, round_count,
                          attachment_count, create_idempotency_key, created_at, updated_at,
                          deleted, version
                        ) VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, 0)
                        """,
                id,
                "FB" + Long.toString(id).repeat(32).substring(0, 32),
                userId,
                FeedbackStatusDict.PROCESSING.getCode(),
                feedbackRoundCodec.serialize(List.of(round)),
                round.getIdempotencyKey(),
                createdAt,
                updatedAt,
                deleted);
    }

    /** 构造同一测试日期的不同时刻。 */
    private LocalDateTime time(int hour) {
        return LocalDateTime.of(2026, 8, 25, hour, 0);
    }
}
