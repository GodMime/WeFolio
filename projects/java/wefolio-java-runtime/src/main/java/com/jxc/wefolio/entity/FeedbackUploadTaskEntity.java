package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackUploadTaskStatusDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_feedback_upload_task — 意见反馈附件上传任务表。
 */
@Data
@TableName("wf_feedback_upload_task")
public class FeedbackUploadTaskEntity extends BaseEntity {

    /** 所属用户 ID。 */
    private Long userId;

    /** 客户端上传任务 ID。 */
    private String clientId;

    /** 后端生成的 COS 对象键。 */
    private String objectKey;

    /**
     * 附件媒体类型。
     *
     * @see FeedbackMediaTypeDict
     */
    private String mediaType;

    /** MIME 类型。 */
    private String mimeType;

    /** 文件字节数。 */
    private Long fileSize;

    /** 媒体时长毫秒，图片为 0。 */
    private Long durationMs;

    /**
     * 上传任务状态。
     *
     * @see FeedbackUploadTaskStatusDict
     */
    private String status;

    /** 上传任务过期时间。 */
    private LocalDateTime expiresAt;

    /** 确认后关联的反馈 ID。 */
    private Long feedbackId;

    /** 确认后关联的反馈轮次。 */
    private Integer roundNo;
}
