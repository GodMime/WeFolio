package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;


/**
 * AI 生成任务表 Mapper
 */
@Mapper
public interface AiGenerationTaskEntityMapper extends BaseMapper<AiGenerationTaskEntity> {
    /** 按当前状态和执行代次取消活动任务，使迟到结果失效。 */
    @Update("""
            UPDATE wf_ai_generation_task SET status='CANCELLED', completed_at=#{now},
              version=version+1, updated_at=#{now}
            WHERE id=#{id} AND requested_by=#{userId} AND deleted=0
              AND status=#{status} AND status IN ('PENDING','RUNNING') AND version=#{version}
            """)
    int cancel(@Param("id") Long id, @Param("userId") Long userId,
               @Param("status") String status, @Param("version") Integer version,
               @Param("now") LocalDateTime now);

    /** 查询和提交时收口本人的过期活动任务；固定超时提示与 Job 共用，条件更新仍隔离旧 worker。 */
    @Update("UPDATE wf_ai_generation_task SET status='FAILED', error_code='TIMEOUT', "
            + "error_message=CONCAT_WS(CHAR(10), NULLIF(error_message, ''), CONCAT('[attempt=', attempt_count, ' code=TIMEOUT] "
            + PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE + "')), "
            + "completed_at=#{now}, updated_at=#{now}, version=version+1 "
            + "WHERE requested_by=#{userId} AND deleted=0 AND status IN ('PENDING','RUNNING') "
            + "AND created_at <= #{deadline}")
    int expireRequester(@Param("userId") Long userId, @Param("now") LocalDateTime now,
                        @Param("deadline") LocalDateTime deadline);
}
