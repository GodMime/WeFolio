package com.jxc.wefolio.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值套餐替换 migration 行为集成测试。
 */
class RechargePackageReplacementMigrationIntegrationTest {

    /** 充值套餐替换 migration 路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V52__replace_one_yuan_recharge_package.sql");

    /** 当前测试连接。 */
    private Connection connection;

    /**
     * 创建当前线上四档套餐的最小数据库场景。
     *
     * @throws SQLException 数据库初始化失败时抛出
     */
    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:recharge_package_replacement;MODE=MySQL;DB_CLOSE_DELAY=-1");
        execute("DROP TABLE IF EXISTS wf_recharge_package");
        execute("""
                CREATE TABLE wf_recharge_package (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  package_code VARCHAR(64) NOT NULL,
                  package_version INT NOT NULL,
                  package_name VARCHAR(100) NOT NULL,
                  amount_fen INT NOT NULL,
                  base_points INT NOT NULL,
                  bonus_points INT NOT NULL,
                  total_points INT NOT NULL,
                  sort_order INT NOT NULL,
                  effective_from TIMESTAMP(3) NOT NULL,
                  effective_to TIMESTAMP(3) NULL,
                  status VARCHAR(32) NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL,
                  updated_at TIMESTAMP(3) NOT NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL,
                  UNIQUE (package_code, package_version)
                )
                """);
        execute("""
                INSERT INTO wf_recharge_package (
                  package_code, package_version, package_name, amount_fen,
                  base_points, bonus_points, total_points, sort_order,
                  effective_from, effective_to, status, created_at, updated_at,
                  deleted, version
                ) VALUES
                  ('RECHARGE_1_YUAN', 1, '1 元档', 100, 100, 0, 100, 10,
                   TIMESTAMP '2026-07-19 00:00:00', NULL, 'ACTIVE',
                   TIMESTAMP '2026-07-19 00:00:00', TIMESTAMP '2026-07-19 00:00:00', 0, 1),
                  ('RECHARGE_10_YUAN', 1, '10 元档', 1000, 1000, 0, 1000, 20,
                   TIMESTAMP '2026-07-19 00:00:00', NULL, 'ACTIVE',
                   TIMESTAMP '2026-07-19 00:00:00', TIMESTAMP '2026-07-19 00:00:00', 0, 1),
                  ('RECHARGE_50_YUAN', 1, '50 元档', 5000, 5000, 200, 5200, 30,
                   TIMESTAMP '2026-07-19 00:00:00', NULL, 'ACTIVE',
                   TIMESTAMP '2026-07-19 00:00:00', TIMESTAMP '2026-07-19 00:00:00', 0, 1),
                  ('RECHARGE_100_YUAN', 1, '100 元档', 10000, 10000, 1000, 11000, 40,
                   TIMESTAMP '2026-07-19 00:00:00', NULL, 'ACTIVE',
                   TIMESTAMP '2026-07-19 00:00:00', TIMESTAMP '2026-07-19 00:00:00', 0, 1)
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
     * migration 应停用一元档，并以五元档组成新的四档有效套餐。
     *
     * @throws SQLException 查询迁移结果失败时抛出
     */
    @Test
    void shouldReplaceActiveOneYuanPackageWithFiveYuanPackage() throws SQLException {
        assertThat(MIGRATION).exists();

        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new FileSystemResource(MIGRATION)));

        assertThat(loadActivePackages()).containsExactly(
                "RECHARGE_5_YUAN|5 元档|500|500|0|500|10",
                "RECHARGE_10_YUAN|10 元档|1000|1000|0|1000|20",
                "RECHARGE_50_YUAN|50 元档|5000|5000|200|5200|30",
                "RECHARGE_100_YUAN|100 元档|10000|10000|1000|11000|40");

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT status, effective_to
                     FROM wf_recharge_package
                     WHERE package_code = 'RECHARGE_1_YUAN'
                       AND package_version = 1
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("status")).isEqualTo("DISABLED");
            assertThat(resultSet.getTimestamp("effective_to")).isNotNull();
        }
    }

    /**
     * 查询按展示顺序排列的有效套餐快照。
     *
     * @return 有效套餐快照
     * @throws SQLException 查询失败时抛出
     */
    private List<String> loadActivePackages() throws SQLException {
        List<String> packages = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT package_code, package_name, amount_fen, base_points,
                            bonus_points, total_points, sort_order
                     FROM wf_recharge_package
                     WHERE status = 'ACTIVE'
                       AND deleted = 0
                     ORDER BY sort_order, id
                     """)) {
            while (resultSet.next()) {
                packages.add(String.join("|",
                        resultSet.getString("package_code"),
                        resultSet.getString("package_name"),
                        resultSet.getString("amount_fen"),
                        resultSet.getString("base_points"),
                        resultSet.getString("bonus_points"),
                        resultSet.getString("total_points"),
                        resultSet.getString("sort_order")));
            }
        }
        return packages;
    }

    /**
     * 执行单条 SQL。
     *
     * @param sql SQL 语句
     * @throws SQLException 执行失败时抛出
     */
    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
