package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_work_upload_task — 作品直传 COS 上传任务表。
 */
@Data
@TableName("wf_work_upload_task")
public class WorkUploadTaskEntity extends BaseEntity {

    /** 批量上传批次 ID */
    private String batchId;

    /** 所属用户 ID */
    private Long userId;

    /**
     * 媒体类型。
     *
     * @see MediaTypeDict
     */
    private String mediaType;

    /** 后端生成的 COS 原文件对象键 */
    private String objectKey;

    /** 当前上传对象的 SHA-256，由小程序端计算提交，后端为避免下载 COS 文件而信任该值。 */
    private String fileSha256;

    /** 封面对象键，图片作品默认等于原文件 */
    private String coverObjectKey;

    /** 原始文件名 */
    private String originalFileName;

    /** MIME 类型 */
    private String mimeType;

    /** 文件字节数 */
    private Long fileSize;

    /** 视频时长毫秒，图片为空 */
    private Integer durationMs;

    /** 像素宽度 */
    private Integer width;

    /** 像素高度 */
    private Integer height;

    /**
     * 上传任务状态。
     *
     * @see WorkUploadTaskStatusDict
     */
    private String status;

    /** 失败原因摘要 */
    private String errorMessage;

    /** 上传票据过期时间 */
    private LocalDateTime expiresAt;

    /** 确认后创建的作品 ID */
    private Long confirmedWorkId;

    /** 上传任务创建幂等键 */
    private String idempotencyKey;
}
