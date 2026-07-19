package com.jxc.wefolio.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月度作品存储结算持久化边界测试。
 */
class WorkStorageBillingSettlementStructureTest {

    /** Service 只编排结算，账单 SQL 必须集中在 Mapper。 */
    @Test
    void settlementSqlShouldBeEncapsulatedByMapper() throws Exception {
        Path mapperPath = Path.of(
                "src/main/java/com/jxc/wefolio/mapper/WorkStorageBillingMapper.java");
        String service = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/WorkStorageBillingSettlementService.java"));

        assertThat(Files.exists(mapperPath)).isTrue();
        String mapper = Files.readString(mapperPath);
        assertThat(service)
                .contains("private final WorkStorageBillingMapper workStorageBillingMapper")
                .doesNotContain("JdbcTemplate")
                .doesNotContain("INSERT IGNORE INTO")
                .doesNotContain("UPDATE wf_work_storage_monthly_bill");
        assertThat(mapper)
                .contains("interface WorkStorageBillingMapper")
                .contains("@Select")
                .contains("@Insert")
                .contains("@Update")
                .contains("wf_work_storage_monthly_bill")
                .contains("wf_work")
                .contains("wf_point_rule");
    }
}
