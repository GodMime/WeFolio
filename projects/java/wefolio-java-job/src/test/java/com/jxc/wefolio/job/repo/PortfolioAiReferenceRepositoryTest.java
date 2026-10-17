package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.service.PortfolioAiReferenceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** H2 仅验证引用 SQL 与时间规则；跨进程命名锁必须另在 MySQL 验证。 */
class PortfolioAiReferenceRepositoryTest {
    private JdbcTemplate jdbc;
    private PortfolioAiReferenceRepository repository;
    private final PortfolioAiReferenceRepository.Owner owner = new PortfolioAiReferenceRepository.Owner("USER", 2, "WFTEST");
    private final Instant now = Instant.parse("2026-10-16T00:00:00Z");
    private final String key = PortfolioAiReferenceTestSupport.item(1, false).path("objectKey").asText();
    private final String taskJson = PortfolioAiReferenceTestSupport.snapshot(false, PortfolioAiReferenceTestSupport.item(1, false));

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:references-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE wf_user(id BIGINT, unique_code VARCHAR(32), deleted BIGINT, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE wf_team(id BIGINT, unique_code VARCHAR(32), deleted BIGINT, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE wf_portfolio(id BIGINT,owner_type VARCHAR(32),owner_id BIGINT,deleted BIGINT,ai_reference_attachments_json CLOB)");
        jdbc.execute("CREATE TABLE wf_ai_generation_task(id BIGINT,owner_type VARCHAR(32),owner_id BIGINT,deleted BIGINT,reference_attachments_json CLOB,status VARCHAR(32),completed_at TIMESTAMP)");
        repository = new PortfolioAiReferenceRepository(jdbc);
    }
    private void task(String status, Instant completed, long deleted) {
        jdbc.update("INSERT INTO wf_ai_generation_task VALUES(1,'USER',2,?,?,?,?)", deleted, taskJson, status,
                completed == null ? null : LocalDateTime.ofInstant(completed, ZoneId.of("Asia/Shanghai")));
    }
    @Test void deletedOwnerCanStillBeResolvedButAmbiguousCodeCannot() {
        jdbc.update("INSERT INTO wf_user VALUES(2,'WFTEST',2,'ACTIVE')");
        assertThat(repository.owner("WFTEST")).contains(owner);
        jdbc.update("INSERT INTO wf_team VALUES(3,'WFTEST',0,'ACTIVE')");
        assertThat(repository.owner("WFTEST")).isEmpty();
    }
    @Test void savedPortfolioAndActiveTaskProtectRegardlessOfUploadAge() {
        jdbc.update("INSERT INTO wf_portfolio VALUES(1,'USER',2,0,?)", PortfolioAiReferenceTestSupport.snapshot(true, PortfolioAiReferenceTestSupport.item(1, false)));
        assertThat(repository.protectedKeys(owner, now)).containsExactly(key);
        jdbc.update("UPDATE wf_portfolio SET deleted=id");
        assertThat(repository.protectedKeys(owner, now)).isEmpty();
        task("RUNNING", null, 0); assertThat(repository.protectedKeys(owner, now)).containsExactly(key);
        jdbc.update("UPDATE wf_ai_generation_task SET status='SUCCEEDED',completed_at=?", LocalDateTime.ofInstant(now, ZoneId.of("Asia/Shanghai")));
        assertThat(repository.protectedKeys(owner, now.plusSeconds(86_399))).containsExactly(key);
        assertThat(repository.protectedKeys(owner, now.plusSeconds(86_400))).isEmpty();
    }
    @Test void cancelledAndLogicallyDeletedTerminalTaskRetainOnlyCompletionWindow() {
        task("CANCELLED", now.minusSeconds(86_399), 1); assertThat(repository.protectedKeys(owner, now)).containsExactly(key);
        assertThat(repository.protectedKeys(owner, now.plusSeconds(1))).isEmpty();
    }
    @Test void malformedJsonOrTerminalWithoutCompletionNeverBecomesEmptyProtection() {
        task("FAILED", null, 0); assertThatThrownBy(() -> repository.protectedKeys(owner, now)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE wf_ai_generation_task SET reference_attachments_json='{broken'");
        assertThatThrownBy(() -> repository.protectedKeys(owner, now)).isInstanceOf(RuntimeException.class);
    }
}
