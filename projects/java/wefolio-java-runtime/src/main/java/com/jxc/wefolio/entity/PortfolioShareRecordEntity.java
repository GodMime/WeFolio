package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_portfolio_share_record — 作品集分享记录表 — 记录每次分享行为，已补充 owner_type/owner_id 冗余字段支持直接按归属查询
 */
@Data
@TableName("wf_portfolio_share_record")
public class PortfolioShareRecordEntity extends BaseEntity {

    /** 作品集 ID */
    private Long portfolioId;

    /** 分享时当前生效修订号 */
    private Integer portfolioRevision;

    /** 发起分享的维护者或团队成员 ID */
    private Long sharedByUserId;

    /** 分享归属类型：USER 用户 / TEAM 团队 */
    private String ownerType;

    /** 分享归属用户 ID 或团队 ID */
    private Long ownerId;

    /** 分享渠道：WECHAT_CARD 微信卡片 / QR_CODE 二维码 / COPIED_PATH 复制链接 */
    private String shareChannel;

    /** 页面入口或业务场景编码 */
    private String shareScene;

    /** 分享时间 */
    private LocalDateTime createdAt;

}
