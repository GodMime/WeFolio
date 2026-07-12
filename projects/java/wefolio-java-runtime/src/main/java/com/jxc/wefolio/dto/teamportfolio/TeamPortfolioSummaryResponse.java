package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 团队作品集摘要响应。
 */
@Data
public class TeamPortfolioSummaryResponse {

    /** 作品集 ID */
    private Long portfolioId;

    /** 分享编码 */
    private String shareCode;

    /** 作品集标题 */
    private String title;

    /** 封面地址 */
    private String coverUrl;

    /** 团队 ID */
    private Long teamId;

    /** 团队名称 */
    private String teamName;

    /** 当前用户团队角色 */
    private String currentRole;

    /** 是否可维护 */
    private boolean canMaintain;

    /** 是否可分享 */
    private boolean canShare;

    /** 发布状态 */
    private String publicationStatus;

    /** 草稿版本号 */
    private Integer draftRevision;

    /** 已发布版本号 */
    private Integer publishedRevision;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
