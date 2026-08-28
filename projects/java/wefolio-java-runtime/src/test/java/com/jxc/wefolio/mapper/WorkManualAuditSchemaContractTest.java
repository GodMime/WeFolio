package com.jxc.wefolio.mapper;

import com.jxc.wefolio.entity.WorkEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品人工审核数据库迁移与实体字段契约测试。 */
class WorkManualAuditSchemaContractTest {

    /** V54 与 Runtime 实体必须声明人工编号和首次结论时间。 */
    @Test
    void migrationAndEntityDeclareManualAuditColumns() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V54__add_work_manual_audit.sql"));

        assertThat(sql)
                .contains("manual_audit_no CHAR(34) CHARACTER SET ascii COLLATE ascii_bin NULL")
                .contains("manual_audit_result_at DATETIME(3) NULL")
                .contains("UNIQUE KEY uk_work_manual_audit_no (manual_audit_no, deleted)");
        assertThat(WorkEntity.class.getDeclaredField("manualAuditNo").getType())
                .isEqualTo(String.class);
        assertThat(WorkEntity.class.getDeclaredField("manualAuditResultAt").getType())
                .isEqualTo(LocalDateTime.class);
    }
}
