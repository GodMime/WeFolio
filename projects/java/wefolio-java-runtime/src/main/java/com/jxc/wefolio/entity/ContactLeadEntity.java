package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_contact_lead — 联系线索表 — 访客通过预留联系信息组件主动提交的联系信息
 */
@Data
@TableName("wf_contact_lead")
public class ContactLeadEntity extends BaseEntity {

    /** 来源作品集 ID */
    private Long portfolioId;

    /** 来源作品集标题快照，作品集删除后仍用于线索展示 */
    private String portfolioTitleSnapshot;

    /** 来源作品集分享编码快照，便于删除后排查线索来源 */
    private String portfolioShareCodeSnapshot;

    /** 提交时的生效修订号 */
    private Integer portfolioRevision;

    /** 关联访问汇总记录 ID */
    private Long visitRecordId;

    /** 线索归属类型：USER 个人用户 / TEAM 团队 */
    private String ownerType;

    /** 线索归属用户 ID 或团队 ID */
    private Long ownerId;

    /** 联系人姓名（必填） */
    private String contactName;

    /** 手机号密文 */
    private String phoneCiphertext;

    /** 手机号尾号，用于脱敏展示 */
    private String phoneLast4;

    /** 微信号密文 */
    private String wechatCiphertext;

    /** 微信号脱敏提示，不保存完整原文 */
    private String wechatMaskHint;

    /** 意向档期文本 */
    private String desiredSchedule;

    /** 需求描述 */
    private String needs;

    /** 来源类型，与访问来源编码一致 */
    private String sourceType;

    /** 隐私说明版本号 */
    private String consentVersion;

    /** 访客主动同意隐私说明并提交的时间 */
    private LocalDateTime consentAt;

    /** 跟进状态：NOT_FOLLOWED_UP / CONTACTED / DEAL_WON / INVALID */
    private String followStatus;

    /** 跟进备注 */
    private String followNote;

    /** 提交幂等键，防止重复创建线索 */
    private String idempotencyKey;

    /** 提交时间 */
    private LocalDateTime submittedAt;

}
