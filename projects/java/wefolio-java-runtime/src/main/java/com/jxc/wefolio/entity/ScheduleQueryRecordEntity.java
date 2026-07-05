package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * wf_schedule_query_record — 访客查询档期记录表 — 保存按钮查询档期的业务快照
 */
@Data
@TableName("wf_schedule_query_record")
public class ScheduleQueryRecordEntity extends BaseEntity {

    /** 来源作品集 ID */
    private Long portfolioId;

    /**
     * 作品集类型。
     *
     * @see PortfolioTypeDict
     */
    private String portfolioType;

    /** 来源作品集标题快照 */
    private String portfolioTitleSnapshot;

    /** 关联访问汇总记录 ID */
    private Long visitRecordId;

    /** 全局访客 ID，未绑定访客资料时为空 */
    private Long visitorId;

    /** 匿名访客摘要 */
    private String visitorKey;

    /**
     * 记录归属类型。
     *
     * @see PortfolioOwnerTypeDict
     */
    private String ownerType;

    /** 记录归属用户 ID 或团队 ID */
    private Long ownerId;

    /**
     * 来源类型，与访问来源编码一致。
     *
     * @see VisitSourceTypeDict
     */
    private String sourceType;

    /** 查档组件展示方式：MODAL_CALENDAR 弹层月历 / INLINE_CALENDAR 内联月历 */
    private String displayMode;

    /** 查询日期 */
    private LocalDate queriedDate;

    /** 档位定义 ID */
    private Long slotDefinitionId;

    /** 档位名称快照 */
    private String slotNameSnapshot;

    /** 档位开始时间快照，历史快照缺失时为空 */
    private LocalTime startTimeSnapshot;

    /** 档位结束时间快照，历史快照缺失时为空 */
    private LocalTime endTimeSnapshot;

    /** 展示颜色快照 */
    private String colorSnapshot;

    /**
     * 查询结果状态编码。
     *
     * @see ScheduleStatusDict
     */
    private String resultStatus;

    /** 查询结果状态文案 */
    private String resultStatusText;

    /** 是否可约：1 是 / 0 否 */
    private Integer available;

    /** 查询结果提示 */
    private String resultMessage;

    /** 查询成功时间 */
    private LocalDateTime queriedAt;
}
