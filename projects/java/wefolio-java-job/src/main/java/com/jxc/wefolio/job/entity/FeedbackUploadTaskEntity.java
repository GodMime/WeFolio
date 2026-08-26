package com.jxc.wefolio.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 反馈附件上传任务实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_feedback_upload_task")
public class FeedbackUploadTaskEntity extends BaseEntity {

    /** 所属用户 ID */
    private Long userId;

    /** 客户端上传任务 ID */
    private String clientId;

    /** COS 对象键 */
    private String objectKey;

    /** 媒体类型 */
    private String mediaType;

    /** MIME 类型 */
    private String mimeType;

    /** 文件字节数 */
    private Long fileSize;

    /** 媒体时长毫秒 */
    private Long durationMs;

    /** 上传任务状态 */
    private String status;

    /** 上传任务过期时间 */
    private LocalDateTime expiresAt;

    /** 确认后关联的反馈 ID */
    private Long feedbackId;

    /** 确认后关联的反馈轮次 */
    private Integer roundNo;
}
