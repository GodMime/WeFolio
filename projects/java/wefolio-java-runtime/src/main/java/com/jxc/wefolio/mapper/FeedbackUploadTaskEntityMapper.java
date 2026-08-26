package com.jxc.wefolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jxc.wefolio.entity.FeedbackUploadTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 意见反馈附件上传任务 Mapper。
 */
@Mapper
public interface FeedbackUploadTaskEntityMapper extends BaseMapper<FeedbackUploadTaskEntity> {

    /**
     * 按上传任务 ID 和用户 ID 查询并锁定有效任务。
     *
     * @param id 上传任务 ID
     * @param userId 用户 ID
     * @return 已锁定上传任务，不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM wf_feedback_upload_task
             WHERE id = #{id}
               AND user_id = #{userId}
               AND deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    FeedbackUploadTaskEntity lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
