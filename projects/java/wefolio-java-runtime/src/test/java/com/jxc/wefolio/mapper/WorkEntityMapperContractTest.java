package com.jxc.wefolio.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品人工审核 Mapper SQL 契约测试。 */
class WorkEntityMapperContractTest {

    /** 人工审核编号查询必须使用当前读行锁。 */
    @Test
    void manualAuditLookupUsesCurrentRowLock() throws Exception {
        Method method = WorkEntityMapper.class.getDeclaredMethod(
                "lockByManualAuditNo", String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("manual_audit_no = #{manualAuditNo}")
                .contains("deleted = 0")
                .contains("LIMIT 1")
                .contains("FOR UPDATE");
    }

    /** 首次人工结论写回必须具备完整条件并清空自动审核原因。 */
    @Test
    void manualAuditCompletionIsConditionalAndClearsAutomatedReasons() throws Exception {
        Method method = WorkEntityMapper.class.getDeclaredMethod(
                "completeManualAudit", Long.class, String.class, String.class,
                String.class, LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("id = #{workId}")
                .contains("manual_audit_no = #{manualAuditNo}")
                .contains("status = 'ACTIVE'")
                .contains("audit_status = 'AUDITING'")
                .contains("deleted = 0")
                .contains("audit_reason_code = NULL")
                .contains("audit_reason_codes = NULL")
                .contains("audit_reject_reason = #{auditRejectReason}")
                .contains("manual_audit_result_at = #{resultAt}")
                .contains("version = version + 1");
    }
}
