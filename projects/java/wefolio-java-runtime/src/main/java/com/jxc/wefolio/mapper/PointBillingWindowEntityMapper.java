package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointBillingWindowEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 个人作品集访客滚动扣费窗口 Mapper。
 */
@Mapper
public interface PointBillingWindowEntityMapper extends BaseMapper<PointBillingWindowEntity> {

    /**
     * 尝试创建活动窗口占位记录，唯一键已存在时静默忽略。
     *
     * @param entity 窗口唯一键实体
     * @return 1 表示新建，0 表示已存在
     */
    @Insert("""
            INSERT IGNORE INTO wf_point_billing_window
                (user_id, visitor_id, scene_code, scope_type, scope_id)
            VALUES
                (#{userId}, #{visitorId}, #{sceneCode}, #{scopeType}, #{scopeId})
            """)
    int insertIgnore(PointBillingWindowEntity entity);

    /**
     * 按完整活动唯一键查询并锁定窗口。
     *
     * @param userId 被扣费维护者用户 ID
     * @param sceneCode 积分场景
     * @param visitorId 全局访客 ID
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @return 已锁定窗口，不存在时为空
     */
    @Select("""
            SELECT id, account_id, user_id, visitor_id, scene_code, scope_type,
                   scope_id, last_charged_at, point_transaction_id,
                   created_at, updated_at, deleted, version
              FROM wf_point_billing_window
             WHERE user_id = #{userId}
               AND scene_code = #{sceneCode}
               AND visitor_id = #{visitorId}
               AND scope_type = #{scopeType}
               AND scope_id = #{scopeId}
               AND deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    PointBillingWindowEntity selectForUpdateByUniqueKey(
            @Param("userId") Long userId,
            @Param("sceneCode") String sceneCode,
            @Param("visitorId") Long visitorId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId
    );

    /**
     * 读取数据库当前毫秒时间。
     *
     * @return 数据库当前时间
     */
    @Select("SELECT CURRENT_TIMESTAMP(3)")
    LocalDateTime selectCurrentTimestamp();

    /**
     * 写入最近一次成功扣费结果。
     *
     * @param id 窗口 ID
     * @param accountId 积分账户 ID
     * @param pointTransactionId 积分流水 ID
     * @param lastChargedAt 成功扣费时间
     * @return 更新行数
     */
    @Update("""
            UPDATE wf_point_billing_window
               SET account_id = #{accountId},
                   point_transaction_id = #{pointTransactionId},
                   last_charged_at = #{lastChargedAt},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateCharged(
            @Param("id") Long id,
            @Param("accountId") Long accountId,
            @Param("pointTransactionId") Long pointTransactionId,
            @Param("lastChargedAt") LocalDateTime lastChargedAt
    );
}
