package com.jxc.wefolio.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_work — 作品表 — 图片和视频素材主数据
 */
@Data
@TableName("wf_work")
public class WorkEntity extends BaseEntity {

    /** 作品所属用户 ID */
    private Long userId;

    /** 媒体类型：IMAGE 图片 / VIDEO 视频，由上传文件自动识别 */
    private String mediaType;

    /** 作品标题，最长 30 字符 */
    private String title;

    /** 上传时原始文件名 */
    private String originalFileName;

    /** COS 对象键，访问 URL 由服务端生成 */
    private String mediaObjectKey;

    /** 原文件 SHA-256，由小程序端计算提交；后端信任该值，仅做格式和唯一性校验。 */
    private String mediaSha256;

    /** 缩略图或视频封面 COS 对象键 */
    private String coverObjectKey;

    /** 缩略图或封面 SHA-256，由小程序端计算提交；后端不下载 COS 文件重新计算。 */
    private String coverSha256;

    /** 文件 MIME 类型 */
    private String mimeType;

    /** 文件字节数 */
    private Long fileSize;

    /** 视频时长毫秒，图片为空 */
    private Integer durationMs;

    /** 动图权威帧数，图片和视频为空 */
    private Integer frameCount;

    /** 动图当前封面帧序号，图片和视频为空 */
    private Integer coverFrameNumber;

    /** 像素宽度 */
    private Integer width;

    /** 像素高度 */
    private Integer height;

    /** 长宽比，例如 16:9、9:16，由小程序端计算提交，后端信任并保存。 */
    private String aspectRatio;

    /** 作品说明，最长 1000 字符 */
    private String description;

    /** 拍摄或服务日期，MVP 可选 */
    private LocalDate serviceDate;

    /** 用户作品库排序值 */
    private Integer sortOrder;

    /** 作品状态：ACTIVE 正常 / PROCESSING 处理中 / PROCESSING_FAILED 处理失败 */
    private String status;

    /** 审核状态：PENDING 未审核 / AUDITING 审核中 / PASSED 通过 / REJECTED 违规 / REVIEW_REQUIRED 疑似 / FAILED 失败 */
    private String auditStatus;

    /** 当前已经进入或即将进入的审核轮次 */
    private Integer auditRound;

    /** 与审核供应商解耦的稳定风险类型 */
    private String auditReasonCode;

    /** 当前轮次全部稳定风险类型 JSON 数组，最多 20 个 */
    private String auditReasonCodes;

    /** 审核拒绝原因 */
    private String auditRejectReason;

    /** 逻辑删除时间 */
    private LocalDateTime deletedAt;

}
