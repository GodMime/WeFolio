package com.jxc.wefolio.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 访客作品集响应。
 */
@Data
public class VisitorPortfolioResponse {

    /** 分享编码 */
    private String shareCode;

    /** 作品集 ID */
    private Long portfolioId;

    /** 正式发布版本 */
    private Integer publishedRevision;

    /** 分享标题 */
    private String title;

    /** 是否维护中 */
    private boolean underMaintenance;

    /** 维护原因编码。 */
    private String maintenanceReason;

    /** 维护中文案 */
    private MaintenanceText maintenanceText;

    /** 正式发布配置 */
    private PortfolioConfigDto config;

    /** 访客端渲染模型 */
    private PortfolioRenderDto renderData;

    /** 访问汇总记录 ID */
    private Long visitRecordId;

    /** 访客登录令牌类型 */
    private String tokenType;

    /** 访客加密登录令牌 */
    private String token;

    /** 访客登录令牌有效期秒数 */
    private Long expiresInSeconds;

    /** 服务端生成的匿名访客稳定 key */
    private String visitorKey;

    /** 是否本次新建访客 */
    @JSONField(name = "isNewVisitor")
    private boolean newVisitor;

    /** 是否需要补充访客头像昵称 */
    private boolean needVisitorProfile;

    /** 访客头像昵称资料更新短期 token */
    private String visitorProfileToken;

    /**
     * 维护中文案。
     */
    @Data
    public static class MaintenanceText {

        /** 英文主文案 */
        private String primary;

        /** 中文副文案 */
        private String secondary;
    }
}
