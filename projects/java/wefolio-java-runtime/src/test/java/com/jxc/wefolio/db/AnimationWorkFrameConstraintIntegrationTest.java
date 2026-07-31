package com.jxc.wefolio.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动图帧数字段约束行为集成测试。
 */
class AnimationWorkFrameConstraintIntegrationTest {

    /** 当前测试连接。 */
    private Connection connection;

    /**
     * 创建与生产约束等价的最小测试表。
     *
     * @throws SQLException 建表失败时抛出
     */
    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:animation_frame_constraint;MODE=MySQL;DB_CLOSE_DELAY=-1");
        execute("DROP TABLE IF EXISTS animation_work");
        execute("""
                CREATE TABLE animation_work (
                  id BIGINT PRIMARY KEY,
                  media_type VARCHAR(16) NOT NULL,
                  frame_count INT NULL,
                  cover_frame_number INT NULL,
                  CONSTRAINT chk_work_animation_frames CHECK (
                    (
                      media_type = 'ANIMATION'
                      AND frame_count IS NOT NULL
                      AND cover_frame_number IS NOT NULL
                      AND frame_count BETWEEN 2 AND 300
                      AND cover_frame_number BETWEEN 1 AND frame_count
                    )
                    OR (
                      media_type <> 'ANIMATION'
                      AND frame_count IS NULL
                      AND cover_frame_number IS NULL
                    )
                  )
                )
                """);
    }

    /**
     * 关闭数据库连接。
     *
     * @throws SQLException 关闭失败时抛出
     */
    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    /**
     * 合法的高帧数和封面帧应成功写入。
     */
    @Test
    void shouldAcceptAnimationWithCoverWithinFrameCount() {
        assertThatCode(() -> execute("""
                INSERT INTO animation_work (id, media_type, frame_count, cover_frame_number)
                VALUES (1, 'ANIMATION', 300, 250)
                """)).doesNotThrowAnyException();
    }

    /**
     * 只降低帧数导致封面越界时应被约束拒绝。
     */
    @Test
    void shouldRejectLoweringFrameCountWithoutUpdatingCover() throws SQLException {
        insertAnimation();

        assertThatThrownBy(() -> execute(
                "UPDATE animation_work SET frame_count = 200 WHERE id = 1"))
                .isInstanceOf(SQLException.class);
    }

    /**
     * 同一更新中同步修正帧数和封面帧应成功。
     */
    @Test
    void shouldAllowUpdatingFrameCountAndCoverTogether() throws SQLException {
        insertAnimation();

        assertThatCode(() -> execute("""
                UPDATE animation_work
                SET frame_count = 200, cover_frame_number = 180
                WHERE id = 1
                """)).doesNotThrowAnyException();
    }

    /**
     * 非动图作品不得写入帧数字段。
     */
    @Test
    void shouldRejectFrameFieldsForNonAnimation() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO animation_work (id, media_type, frame_count, cover_frame_number)
                VALUES (1, 'IMAGE', 2, 1)
                """)).isInstanceOf(SQLException.class);
    }

    /**
     * 动图作品缺少权威帧数或封面帧时必须被约束拒绝，不能利用 SQL UNKNOWN 语义绕过。
     */
    @Test
    void shouldRejectAnimationWithMissingFrameFields() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO animation_work (id, media_type, frame_count, cover_frame_number)
                VALUES (1, 'ANIMATION', NULL, NULL)
                """)).isInstanceOf(SQLException.class);
    }

    private void insertAnimation() throws SQLException {
        execute("""
                INSERT INTO animation_work (id, media_type, frame_count, cover_frame_number)
                VALUES (1, 'ANIMATION', 300, 250)
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
