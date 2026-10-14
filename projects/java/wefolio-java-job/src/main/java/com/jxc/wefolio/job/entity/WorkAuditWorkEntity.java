package com.jxc.wefolio.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.time.LocalDateTime;

/**
 * job 内部作品审核视图实体，仅保留审核任务需要的作品字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_work")
public class WorkAuditWorkEntity extends BaseEntity {

    /** 作品所属用户 ID */
    private Long userId;

    /** 媒体类型：IMAGE 图片 / VIDEO 视频 / ANIMATION 动图 */
    private String mediaType;

    /** COS 媒体对象键 */
    private String mediaObjectKey;

    /** 媒体文件 SHA-256 */
    private String mediaSha256;

    /** 视频时长毫秒，图片为空 */
    private Integer durationMs;

    /** 视频音频转写全文；NULL 表示尚未生成，空字符串表示已处理但没有文字，仅由专用仓储写入。 */
    @ToString.Exclude
    @TableField(select = false, updateStrategy = FieldStrategy.NEVER)
    private String videoAudioText;

    /** 完整持久视频帧清单，未准备完成时为空 */
    private String videoFramesJson;

    /** 独立媒体摘要缓存，只由专用仓储条件更新，禁止回带旧值。 */
    @TableField(select = false, updateStrategy = FieldStrategy.NEVER)
    @ToString.Exclude
    private String aiMediaSummaryJson;

    /** 摘要独立过期时间，不影响持久视频帧。 */
    @TableField(select = false, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime aiMediaSummaryExpiresAt;

    /** 作品业务状态，历史补帧仅扫描有效作品 */
    private String status;

    /** 动图总帧数，非动图为空 */
    private Integer frameCount;

    /** 作品审核状态 */
    private String auditStatus;

    /** 作品当前审核轮次 */
    private Integer auditRound;

    /** 人工审核编号，非空时自动审核任务必须隔离 */
    private String manualAuditNo;

    /** 与审核供应商解耦的稳定风险类型 */
    private String auditReasonCode;

    /** 当前轮次全部稳定风险类型 JSON 数组，最多 20 个 */
    private String auditReasonCodes;

    /** 审核拒绝原因 */
    private String auditRejectReason;
}
