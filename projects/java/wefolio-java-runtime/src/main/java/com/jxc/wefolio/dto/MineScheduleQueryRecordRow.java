package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 维护端可见查档记录的统一只读投影。
 */
@Data
public class MineScheduleQueryRecordRow {

    /** 来源表真实主键。 */
    private Long sourceRecordId;

    /** 记录类型：个人或团队。 */
    private String recordType;

    /** 来源作品集标题快照。 */
    private String portfolioTitleSnapshot;

    /** 全局访客 ID。 */
    private Long visitorId;

    /** 匿名访客摘要。 */
    private String visitorKey;

    /** 访问来源类型。 */
    private String sourceType;

    /** 查询日期。 */
    private LocalDate queriedDate;

    /** 个人档位名称快照。 */
    private String slotNameSnapshot;

    /** 个人档位开始时间快照。 */
    private LocalTime startTimeSnapshot;

    /** 个人档位结束时间快照。 */
    private LocalTime endTimeSnapshot;

    /** 查询结果状态编码。 */
    private String resultStatus;

    /** 查询结果状态文案。 */
    private String resultStatusText;

    /** 是否可约。 */
    private Integer available;

    /** 查询结果提示。 */
    private String resultMessage;

    /** 团队空闲成员数。 */
    private Integer availableMemberCount;

    /** 团队部分空闲成员数。 */
    private Integer partialAvailableMemberCount;

    /** 团队已满成员数。 */
    private Integer fullMemberCount;

    /** 查询成功时间。 */
    private LocalDateTime queriedAt;
}
