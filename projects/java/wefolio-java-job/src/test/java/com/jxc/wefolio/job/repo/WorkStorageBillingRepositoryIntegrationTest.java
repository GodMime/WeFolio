package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品存储结算扫描仓储集成测试。 */
class WorkStorageBillingRepositoryIntegrationTest {

    /** 音频参与原 file_size 聚合，引用同一封面不重复累计封面大小。 */
    @Test
    void audioShouldBeIncludedByExistingStorageAggregation() {
        jdbcTemplate.execute("ALTER TABLE wf_work ADD media_type VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE wf_work ADD cover_object_key VARCHAR(255)");
        jdbcTemplate.update("INSERT INTO wf_user(id, status, deleted) VALUES (7, 'ACTIVE', 0)");
        jdbcTemplate.update("""
                INSERT INTO wf_work(id, user_id, file_size, deleted, media_type, cover_object_key) VALUES
                (11, 7, 1000, 0, 'IMAGE', 'user/work/image/cover.png'),
                (12, 7, 2000, 0, 'AUDIO', 'user/work/image/cover.png'),
                (13, 7, 3000, 0, 'AUDIO', 'system/default-audio-cover-v1-200kb.png'),
                (14, 7, 9999, 14, 'AUDIO', 'system/default-audio-cover-v1-200kb.png')
                """);
        assertThat(repository.findUnbilledUserAggregates(LocalDate.of(2026, 9, 1), 0, 100))
                .containsExactly(new UserStorageAggregate(7, 3, 6000));
    }

    private JdbcTemplate jdbcTemplate;
    private WorkStorageBillingRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "work_storage_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new WorkStorageBillingRepository(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE wf_user (id BIGINT PRIMARY KEY, status VARCHAR(32), deleted BIGINT)");
        jdbcTemplate.execute("CREATE TABLE wf_work (id BIGINT PRIMARY KEY, user_id BIGINT, file_size BIGINT, deleted BIGINT)");
        jdbcTemplate.execute("""
                CREATE TABLE wf_work_storage_monthly_bill (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  billing_month DATE NOT NULL,
                  deleted BIGINT NOT NULL
                )
                """);
    }

    @Test
    void aggregateShouldFilterUsersAndWorksAndExcludeExistingBill() {
        jdbcTemplate.update("INSERT INTO wf_user(id, status, deleted) VALUES (1, 'ACTIVE', 0), (2, 'ACTIVE', 0), (3, 'DISABLED', 0)");
        jdbcTemplate.update("""
                INSERT INTO wf_work(id, user_id, file_size, deleted) VALUES
                (11, 2, NULL, 0), (12, 2, 10485760, 0), (13, 2, 999, 1), (14, 3, 999, 0)
                """);
        jdbcTemplate.update(
                "INSERT INTO wf_work_storage_monthly_bill(user_id, billing_month, deleted) VALUES (1, ?, 0)",
                LocalDate.of(2026, 7, 1));

        List<UserStorageAggregate> result = repository.findUnbilledUserAggregates(
                LocalDate.of(2026, 7, 1), 0, 100);

        assertThat(result).containsExactly(new UserStorageAggregate(2, 2, 10L * 1024 * 1024));
    }
}
