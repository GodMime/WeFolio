package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointBillingWindowEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访客积分滚动扣费窗口 Mapper 合约测试。
 */
class PointBillingWindowEntityMapperTest {

    /** Mapper 必须继承 MyBatis-Plus 基础能力。 */
    @Test
    void mapperExtendsBaseMapperForBillingWindowEntity() {
        assertThat(PointBillingWindowEntityMapper.class.getInterfaces()).contains(BaseMapper.class);
    }

    /** 占位记录必须使用 INSERT IGNORE 返回影响行数。 */
    @Test
    void insertIgnoreUsesDatabaseConflictSemantics() throws NoSuchMethodException {
        Method method = PointBillingWindowEntityMapper.class.getMethod(
                "insertIgnore", PointBillingWindowEntity.class);
        String sql = String.join("\n", method.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("INSERT IGNORE INTO wf_point_billing_window")
                .contains("user_id, visitor_id, scene_code, scope_type, scope_id")
                .doesNotContain("useGeneratedKeys");
        assertThat(method.getReturnType()).isEqualTo(int.class);
    }

    /** 唯一键查询必须锁定活动窗口行。 */
    @Test
    void uniqueKeyLookupUsesForUpdateAndEveryIdentityColumn() throws NoSuchMethodException {
        Method method = PointBillingWindowEntityMapper.class.getMethod(
                "selectForUpdateByUniqueKey",
                Long.class, String.class, Long.class, String.class, Long.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("user_id = #{userId}")
                .contains("scene_code = #{sceneCode}")
                .contains("visitor_id = #{visitorId}")
                .contains("scope_type = #{scopeType}")
                .contains("scope_id = #{scopeId}")
                .contains("deleted = 0")
                .contains("FOR UPDATE");
    }

    /** 窗口判断必须读取数据库毫秒时间。 */
    @Test
    void currentTimestampComesFromDatabase() throws NoSuchMethodException {
        Method method = PointBillingWindowEntityMapper.class.getMethod("selectCurrentTimestamp");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(method.getReturnType()).isEqualTo(LocalDateTime.class);
        assertThat(sql).contains("CURRENT_TIMESTAMP(3)");
    }

    /** 扣费成功后只更新扣费结果字段并推进版本。 */
    @Test
    void updateChargedPersistsTransactionAndWindowTime() throws NoSuchMethodException {
        Method method = PointBillingWindowEntityMapper.class.getMethod(
                "updateCharged", Long.class, Long.class, Long.class, LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("account_id = #{accountId}")
                .contains("point_transaction_id = #{pointTransactionId}")
                .contains("last_charged_at = #{lastChargedAt}")
                .contains("version = version + 1")
                .contains("WHERE id = #{id}")
                .contains("deleted = 0");
    }
}
