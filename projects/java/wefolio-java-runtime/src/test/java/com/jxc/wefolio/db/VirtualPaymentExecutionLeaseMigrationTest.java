package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付执行租约迁移契约测试。
 */
class VirtualPaymentExecutionLeaseMigrationTest {

    /** 执行租约扩展迁移路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V50__add_virtual_payment_execution_lease_token.sql");

    /** 扩展阶段必须为赠送订单和扣币任务增加同语义字段，并暂时保留旧字段。 */
    @Test
    void migrationShouldAddExecutionLeaseTokenWithoutDroppingLegacyOwner() throws Exception {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE `wf_point_debit_task`")
                .contains("ALTER TABLE `wf_point_gift_order`")
                .contains("`execution_lease_token` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL")
                .doesNotContain("DROP COLUMN `lease_owner`");
    }
}
