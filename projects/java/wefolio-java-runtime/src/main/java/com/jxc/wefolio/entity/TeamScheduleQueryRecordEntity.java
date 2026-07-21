package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.TeamScheduleResultStatusDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * wf_team_schedule_query_record — 团队作品集访客查档记录表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_team_schedule_query_record")
public class TeamScheduleQueryRecordEntity extends BaseEntity {

    /** 来源团队作品集 ID */
    private Long portfolioId;

    /** 查询时发布修订号 */
    private Integer portfolioRevision;

    /** 来源作品集标题快照 */
    private String portfolioTitleSnapshot;

    /** 记录归属团队 ID */
    private Long teamId;

    /** 访问汇总记录 ID */
    private Long visitRecordId;

    /** 全局访客 ID，未绑定访客资料时为空 */
    private Long visitorId;

    /** 匿名访客摘要 */
    private String visitorKey;

    /**
     * 访问来源类型。
     *
     * @see VisitSourceTypeDict
     */
    private String sourceType;

    /** 查档组件展示方式 */
    private String displayMode;

    /** 查询日期 */
    private LocalDate queriedDate;

    /**
     * 团队查档结果状态。
     *
     * @see TeamScheduleResultStatusDict
     */
    private String resultStatus;

    /** 团队查档结果状态文案 */
    private String resultStatusText;

    /** 是否至少有一名成员可约：1 是 / 0 否 */
    private Integer available;

    /** 查询结果提示 */
    private String resultMessage;

    /** 成员级档期结果快照 */
    private String teamResultJson;

    /** 空闲成员数 */
    private Integer availableMemberCount;

    /** 部分档期空闲成员数 */
    private Integer partialAvailableMemberCount;

    /** 已满成员数 */
    private Integer fullMemberCount;

    /** 查询成功时间 */
    private LocalDateTime queriedAt;
}
