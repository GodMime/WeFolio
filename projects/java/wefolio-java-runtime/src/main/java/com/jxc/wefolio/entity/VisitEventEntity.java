package com.jxc.wefolio.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_visit_event — 访问行为事件表 — 每次具体行为的明细记录
 */
@Data
@TableName("wf_visit_event")
public class VisitEventEntity extends BaseEntity {

    /** 逻辑关联访问汇总记录 ID */
    private Long visitRecordId;

    /** 作品集 ID，便于独立统计 */
    private Long portfolioId;

    /** 事件发生时的生效修订号 */
    private Integer portfolioRevision;

    /** 匿名访客摘要 */
    private String visitorKey;

    /** 事件类型：PORTFOLIO_OPENED / WORK_VIEWED / VIDEO_PLAYED / SCHEDULE_QUERIED / QR_CODE_INTERACTED / MEMBER_PORTFOLIO_OPENED / CONTACT_FORM_EXPOSED / CONTACT_LEAD_SUBMITTED */
    private String eventType;

    /** 相关作品 ID */
    private Long workId;

    /** 查询档期日期 */
    private LocalDate queriedDate;

    /** 本次停留或播放时长秒数 */
    private Integer durationSeconds;

    /** 客户端或服务端事件幂等键，防止异步重试重复计数 */
    private String idempotencyKey;

    /** 二维码动作、来源、档位等扩展信息 JSON，不含敏感明文 */
    private String metadata;

    /** 业务发生时间 */
    private LocalDateTime occurredAt;

}
