package com.jxc.wefolio.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 作品内容审核任务实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_work_audit_task")
public class WorkAuditTaskEntity extends BaseEntity {

    /** 作品 ID */
    private Long workId;

    /** 用户 ID */
    private Long userId;

    /** 媒体类型：IMAGE 图片 / VIDEO 视频 */
    private String mediaType;

    /** COS 媒体对象键 */
    private String mediaObjectKey;

    /** 媒体文件 SHA-256 */
    private String mediaSha256;

    /** 任务所属作品审核轮次 */
    private Integer auditRound;

    /** 审核服务提供方 */
    private String provider;

    /** 审核任务状态 */
    private String taskStatus;

    /** 审核结果 */
    private String auditResult;

    /** 腾讯云数据万象任务 ID */
    private String ciJobId;

    /** 腾讯云任务状态 */
    private String ciState;

    /** 腾讯云审核结果码 */
    private Integer ciResult;

    /** 命中的主要标签 */
    private String ciLabel;

    /** 命中分数摘要 */
    private Integer ciScore;

    /** 视频截帧间隔秒数 */
    private Integer snapshotIntervalSeconds;

    /** 视频截帧数量 */
    private Integer snapshotCount;

    /** 提交或图片审核调用次数 */
    private Integer attemptCount;

    /** 视频结果主动查询次数 */
    private Integer queryCount;

    /** 最近一次查询时间 */
    private LocalDateTime lastQueryAt;

    /** 当前处理实例 */
    private String lockedBy;

    /** 锁过期时间 */
    private LocalDateTime lockedUntil;

    /** 开始处理时间 */
    private LocalDateTime startedAt;

    /** 视频提交成功时间 */
    private LocalDateTime submittedAt;

    /** 终态时间 */
    private LocalDateTime finishedAt;

    /** 最近一次错误 */
    private String lastErrorMessage;

    /** 请求摘要 */
    private String requestPayload;

    /** 响应摘要 */
    private String responsePayload;
}
