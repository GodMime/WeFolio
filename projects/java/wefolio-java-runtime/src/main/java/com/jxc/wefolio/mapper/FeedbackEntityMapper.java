package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.FeedbackEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;

/**
 * 意见反馈单 Mapper。
 */
@Mapper
public interface FeedbackEntityMapper extends BaseMapper<FeedbackEntity> {

    /**
     * 按反馈 ID 和用户 ID 查询并锁定有效反馈。
     *
     * @param id 反馈 ID
     * @param userId 用户 ID
     * @return 已锁定反馈，不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM wf_feedback
             WHERE id = #{id}
               AND user_id = #{userId}
               AND deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    FeedbackEntity lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按反馈单号查询并锁定有效反馈。
     *
     * @param feedbackNo 反馈单号
     * @return 已锁定反馈，不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM wf_feedback
             WHERE feedback_no = #{feedbackNo}
               AND deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    FeedbackEntity lockByFeedbackNo(@Param("feedbackNo") String feedbackNo);

    /**
     * 统计用户处于指定状态集合的有效反馈数量。
     *
     * @param userId 用户 ID
     * @param activeStatuses 计入活动反馈的状态编码集合
     * @return 活动反馈数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM wf_feedback
 WHERE user_id = #{userId}
   AND deleted = 0
   <choose>
     <when test="activeStatuses != null and activeStatuses.size() > 0">
       AND status IN
       <foreach collection="activeStatuses" item="status" open="(" separator="," close=")">
         #{status}
       </foreach>
     </when>
     <otherwise>
       AND 1 = 0
     </otherwise>
   </choose>
</script>
""")
    int countActiveByUserId(
            @Param("userId") Long userId,
            @Param("activeStatuses") Collection<String> activeStatuses
    );

    /**
     * 按反馈 ID 和用户 ID 查询有效反馈。
     *
     * @param id 反馈 ID
     * @param userId 用户 ID
     * @return 有效反馈，不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM wf_feedback
             WHERE id = #{id}
               AND user_id = #{userId}
               AND deleted = 0
             LIMIT 1
            """)
    FeedbackEntity selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按用户和创建幂等键查询有效反馈。
     *
     * @param userId 用户 ID
     * @param idempotencyKey 创建幂等键
     * @return 已存在反馈，不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM wf_feedback
             WHERE user_id = #{userId}
               AND create_idempotency_key = #{idempotencyKey}
               AND deleted = 0
             LIMIT 1
            """)
    FeedbackEntity selectByUserIdAndCreateIdempotencyKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
