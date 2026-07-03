package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_visit_record — 访问汇总表 — 同一访客对同一作品集的累计访问聚合
 */
@Data
@TableName("wf_visit_record")
public class VisitRecordEntity extends BaseEntity {

    /** 服务端生成的匿名访客稳定摘要，不存设备标识明文 */
    private String visitorKey;

    /** 被访问作品集 ID */
    private Long portfolioId;

    /** 被访问作品集标题快照，作品集删除后仍用于历史展示 */
    private String portfolioTitleSnapshot;

    /** 被访问作品集分享编码快照，便于删除后排查历史访问来源 */
    private String portfolioShareCodeSnapshot;

    /** 最近一次访问的生效修订号 */
    private Integer lastPortfolioRevision;

    /** 作品集类型：PERSONAL 个人 / TEAM 团队 */
    private String portfolioType;

    /** 记录归属类型：USER 用户 / TEAM 团队 */
    private String ownerType;

    /** 访问记录归属用户 ID 或团队 ID */
    private Long ownerId;

    /** 来源类型：WECHAT_SHARE_CARD / QR_CODE / TEAM_PORTFOLIO / PERSONAL_PORTFOLIO / UNKNOWN */
    private String sourceType;

    /** 最近来源作品集 ID */
    private Long sourcePortfolioId;

    /** 最近来源作品集标题快照 */
    private String sourcePortfolioTitleSnapshot;

    /** 来源作品集类型：PERSONAL / TEAM */
    private String sourcePortfolioType;

    /** 累计打开次数 */
    private Integer visitCount;

    /** 累计查看作品次数 */
    private Integer viewWorkCount;

    /** 累计播放视频次数 */
    private Integer playVideoCount;

    /** 累计档期查询次数 */
    private Integer scheduleQueryCount;

    /** 累计二维码点击或长按次数 */
    private Integer qrActionCount;

    /** 累计成功提交线索次数 */
    private Integer contactSubmitCount;

    /** 累计停留秒数 */
    private Integer totalDurationSeconds;

    /** 查询日期去重缓存 JSON，事件表为明细来源 */
    private String queriedScheduleDates;

    /** 跟进状态：NOT_FOLLOWED_UP 未跟进 / CONTACTED 已联系 / DEAL_WON 已成交 / INVALID 无效 */
    private String followStatus;

    /** 跟进备注 */
    private String followNote;

    /** 首次访问时间 */
    private LocalDateTime firstVisitedAt;

    /** 最近访问时间 */
    private LocalDateTime lastVisitedAt;

}
