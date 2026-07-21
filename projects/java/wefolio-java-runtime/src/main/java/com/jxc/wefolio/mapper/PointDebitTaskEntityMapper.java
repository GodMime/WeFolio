package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 微信代币扣币历史任务 Mapper。
 */
@Mapper
public interface PointDebitTaskEntityMapper extends BaseMapper<PointDebitTaskEntity> {

    /**
     * 幂等插入活动任务，活动槽位冲突时返回 0 而不抛出导致来源事务回滚的异常。
     */
    @Insert("""
            INSERT IGNORE INTO wf_point_debit_task (
              task_no, account_id, user_id, status, active_flag,
              settled_amount, used_present_amount, retry_count, next_execute_at,
              created_at, updated_at, deleted, version
            ) VALUES (
              #{taskNo}, #{accountId}, #{userId}, #{status}, 1,
              0, 0, 0, #{nextExecuteAt},
              CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0, 0
            )
            """)
    int insertActiveTask(
            @Param("taskNo") String taskNo,
            @Param("accountId") Long accountId,
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("nextExecuteAt") LocalDateTime nextExecuteAt
    );

    /** 原子领取已到期或租约过期的任务。 */
    @Update("""
            UPDATE wf_point_debit_task
               SET status = 'RUNNING',
                   lease_owner = #{leaseOwner},
                   lease_until = #{leaseUntil},
                   started_at = COALESCE(started_at, #{now}),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{taskId}
               AND active_flag = 1
               AND (
                    (status IN ('WAITING', 'RETRY_WAIT') AND next_execute_at <= #{now})
                    OR (status = 'RUNNING' AND lease_until < #{now})
               )
               AND deleted = 0
            """)
    int tryClaim(
            @Param("taskId") Long taskId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    /** 新会话可用时唤醒等待会话的活动任务，不修改其他活动任务时间窗口。 */
    @Update("""
            UPDATE wf_point_debit_task
               SET status = 'WAITING',
                   next_execute_at = #{nextExecuteAt},
                   lease_owner = NULL,
                   lease_until = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE user_id = #{userId}
               AND active_flag = 1
               AND status = 'WAITING_SESSION'
               AND deleted = 0
            """)
    int wakeWaitingSession(
            @Param("userId") Long userId,
            @Param("nextExecuteAt") LocalDateTime nextExecuteAt
    );

    /** 人工重试失败任务，保留原任务号和已持久化请求金额。 */
    @Update("""
            UPDATE wf_point_debit_task
               SET status = 'RETRY_WAIT',
                   retry_count = 0,
                   next_execute_at = CURRENT_TIMESTAMP(3),
                   lease_owner = NULL,
                   lease_until = NULL,
                   last_error_code = NULL,
                   last_error_message = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{taskId}
               AND status = 'FAILED'
               AND active_flag = 1
               AND deleted = 0
            """)
    int resetForManualRetry(@Param("taskId") Long taskId);
}
