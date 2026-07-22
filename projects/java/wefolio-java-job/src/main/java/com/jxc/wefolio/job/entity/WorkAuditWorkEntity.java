package com.jxc.wefolio.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * job 内部作品审核视图实体，仅保留审核任务需要的作品字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_work")
public class WorkAuditWorkEntity extends BaseEntity {

    /** 作品所属用户 ID */
    private Long userId;

    /** 媒体类型：IMAGE 图片 / VIDEO 视频 */
    private String mediaType;

    /** COS 媒体对象键 */
    private String mediaObjectKey;

    /** 媒体文件 SHA-256 */
    private String mediaSha256;

    /** 视频时长毫秒，图片为空 */
    private Integer durationMs;

    /** 作品审核状态 */
    private String auditStatus;

    /** 作品当前审核轮次 */
    private Integer auditRound;

    /** 与审核供应商解耦的稳定风险类型 */
    private String auditReasonCode;

    /** 当前轮次全部稳定风险类型 JSON 数组，最多 20 个 */
    private String auditReasonCodes;

    /** 审核拒绝原因 */
    private String auditRejectReason;
}
