package com.jxc.wefolio.integration;

import com.jxc.wefolio.service.VisitActivitySessionConcurrencyIntegrationTest;
import com.jxc.wefolio.mapper.VisitActivitySessionEntityMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式隔离MySQL8迁移、唯一键、事务及并发验证；默认套件不连接外部数据库。 */
@Slf4j
@EnabledIfSystemProperty(named = "visit.activity.mysql.enabled", matches = "true")
public class VisitActivityMysqlIntegrationTest extends VisitActivitySessionConcurrencyIntegrationTest {
    /** 真实迁移元数据必须保留历史未知语义、双键唯一性及设备查询索引顺序。 */
    @Test
    void migrationMetadataKeepsNullableDurationAndExactIndexes() {
        var duration = jdbc.queryForMap("""
                SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='wf_visit_record' AND column_name='foreground_duration_ms'
                """);
        assertThat(duration.get("DATA_TYPE")).isEqualTo("bigint");
        assertThat(duration.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(duration.get("COLUMN_DEFAULT")).isNull();
        var reportTime = jdbc.queryForMap("""
                SELECT IS_NULLABLE, COLUMN_DEFAULT, DATETIME_PRECISION FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='wf_visit_activity_session' AND column_name='last_reported_at'
                """);
        assertThat(reportTime.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(reportTime.get("COLUMN_DEFAULT")).isNull();
        assertThat(((Number) reportTime.get("DATETIME_PRECISION")).intValue()).isEqualTo(3);
        assertIndex("uk_visit_activity_session", 0,
                "visitor_id", "portfolio_type", "portfolio_id", "client_session_key", "deleted");
        assertIndex("uk_visit_activity_open", 0,
                "visitor_id", "portfolio_type", "portfolio_id", "open_idempotency_key", "deleted");
        assertIndex("idx_visit_activity_device", 1, "visit_record_id", "deleted", "created_at", "id");
        log.info("MySQL迁移元数据验证通过：foreground_duration_ms={}，last_reported_at={}，双键与设备索引顺序一致", duration, reportTime);
    }

    /** 使用真实Mapper SQL做EXPLAIN，验证索引命中及空设备、删除、同时间排序语义。 */
    @Test
    void latestDeviceQueryUsesDeviceIndexAndFiltersInvalidSnapshots() throws Exception {
        var initial = open("device-client", "device-open");
        Long recordId = initial.record().getId();
        LocalDateTime created = LocalDateTime.now().minusMinutes(5);
        List<Object[]> unrelated = new ArrayList<>();
        for (int index = 0; index < 1024; index++) {
            unrelated.add(new Object[] { 10000L + index, "fixture-client-" + index, "fixture-open-" + index,
                    "其他记录设备", created, 0L });
        }
        jdbc.batchUpdate(deviceFixtureSql(), unrelated);
        jdbc.update(deviceFixtureSql(), recordId, "older", "older-open", "旧设备", created, 0L);
        jdbc.update(deviceFixtureSql(), recordId, "latest", "latest-open", "新设备", created, 0L);
        jdbc.update(deviceFixtureSql(), recordId, "empty", "empty-open", null, created.plusMinutes(1), 0L);
        jdbc.update(deviceFixtureSql(), recordId, "deleted", "deleted-open", "已删除设备", created.plusMinutes(2), 1L);
        jdbc.execute("ANALYZE TABLE wf_visit_activity_session");
        assertThat(sessions.selectLatestDevice(recordId).getModel()).isEqualTo("新设备");
        String mapperSql = String.join("\n", VisitActivitySessionEntityMapper.class
                .getMethod("selectLatestDevice", Long.class).getAnnotation(Select.class).value())
                .replace("#{recordId}", "?");
        var plan = jdbc.queryForMap("EXPLAIN " + mapperSql, recordId);
        assertThat(plan.get("key")).isEqualTo("idx_visit_activity_device");
        assertThat(((Number) plan.get("rows")).longValue()).isLessThan(20L);
        log.info("MySQL最新有效设备查询EXPLAIN：{}", plan);
    }

    /** 从真实information_schema验证唯一性与列顺序，避免只检验脚本文本。 */
    private void assertIndex(String name, int nonUnique, String... columns) {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='wf_visit_activity_session' AND index_name=?
                ORDER BY seq_in_index
                """, String.class, name)).containsExactly(columns);
        assertThat(jdbc.queryForObject("""
                SELECT MIN(non_unique) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='wf_visit_activity_session' AND index_name=?
                """, Integer.class, name)).isEqualTo(nonUnique);
    }

    /** 仅用于当前隔离库的设备查询数据，四设备字段中只有型号可选写入。 */
    private String deviceFixtureSql() {
        return """
                INSERT INTO wf_visit_activity_session(visitor_id,portfolio_id,portfolio_type,visit_record_id,
                    client_session_key,open_idempotency_key,model,created_at,deleted)
                VALUES(7,8,'PERSONAL',?,?,?,?,?,?)
                """;
    }
}
