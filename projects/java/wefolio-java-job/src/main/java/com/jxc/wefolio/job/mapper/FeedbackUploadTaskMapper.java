package com.jxc.wefolio.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.job.entity.FeedbackUploadTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 反馈附件上传任务 Mapper。
 */
@Mapper
public interface FeedbackUploadTaskMapper extends BaseMapper<FeedbackUploadTaskEntity> {

    /** 待确认状态常量 */
    String PENDING_STATUS = "PENDING";

    /**
     * 锁定并复核一条仍可清理的上传任务。
     *
     * @param taskId 上传任务 ID
     * @param now 本轮过期判断时间
     * @return 仍处于待确认状态的过期任务，不满足条件时返回空
     */
    @Select({
            "SELECT * FROM wf_feedback_upload_task",
            "WHERE id = #{taskId}",
            "AND status = '" + PENDING_STATUS + "'",
            "AND expires_at <= #{now}",
            "AND deleted = 0",
            "FOR UPDATE"
    })
    FeedbackUploadTaskEntity selectPendingExpiredForUpdate(
            @Param("taskId") Long taskId,
            @Param("now") LocalDateTime now);
}
