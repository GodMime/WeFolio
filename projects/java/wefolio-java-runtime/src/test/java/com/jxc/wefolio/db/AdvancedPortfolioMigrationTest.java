package com.jxc.wefolio.db;

import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

/** 增量与目标 DDL、十九列实体及五态字典同批核对；不访问真实环境。 */
class AdvancedPortfolioMigrationTest {
    /** 未启用前提在任何 ALTER 之前校验，禁止自动清理业务数据。 */
    @Test void migrationGuardsUnusedTablesBeforeDdl() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V61__advanced_portfolio_ai_tasks.sql"));
        assertThat(sql).contains("SELECT COUNT(*) FROM `wf_ai_generation_task`", "`template_type` = 'ADVANCED'",
                "CHECK (`task_count` = 0)", "CHECK (`advanced_count` = 0)");
        assertThat(sql.indexOf("INSERT INTO `wf_advanced_migration_guard`")).isLessThan(sql.indexOf("ALTER TABLE"));
        assertThat(sql).doesNotContain("DELETE FROM", "DROP TABLE `wf_ai_generation_task`", "ALTER TABLE `wf_portfolio_history`");
    }

    /** 目标定义十九列与 Java 十四业务字段逐一对应，不保留旧任务读写。 */
    @Test void finalSchemaAndEntityHaveNineteenColumnsAndFiveStates() throws Exception {
        String schema = Files.readString(Path.of("full-schema.sql"));
        String task = schema.substring(schema.indexOf("CREATE TABLE `wf_ai_generation_task`"));
        task = task.substring(0, task.indexOf(";") + 1);
        long columns = task.lines().filter(line -> line.stripLeading().startsWith("`")).count();
        assertThat(columns).isEqualTo(19);
        assertThat(AiGenerationTaskEntity.class.getDeclaredFields()).hasSize(14);
        assertThat(task).contains("`requested_by`, `idempotency_key`, `deleted`", "`next_run_at`", "`request_json`", "`result_json`");
        assertThat(task).doesNotContain("`applied_revision`", "`selected_sources_json`", "`result_schema_json`", "`cost_points`", "TIMED_OUT");
        assertThat(Arrays.stream(AiTaskStatusDict.values()).map(AiTaskStatusDict::getCode)).containsExactly("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
        assertThat(schema).contains("`ai_context_json` JSON NULL");
    }

    /** 默认Mapper调试日志不得泄露冻结用户输入；实体字符串也排除两份原文。 */
    @Test void taskPrivacyOverridesMapperDebugAndEntityText() throws Exception {
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        assertThat(logback).contains("name=\"com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper\" level=\"INFO\"");
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setRequestJson("隐藏输入与私密绑定"); task.setResultJson("私密结果");
        assertThat(task.toString()).doesNotContain("隐藏输入", "私密结果", "requestJson", "resultJson");
    }
}
