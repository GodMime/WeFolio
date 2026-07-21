package com.jxc.wefolio.dto.teamportfolio;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 访客打开标准团队作品集响应。
 */
@Data
public class VisitorTeamPortfolioResponse {

    /** 分享编码。 */
    private String shareCode;

    /** 作品集 ID。 */
    private Long portfolioId;

    /** 团队 ID。 */
    private Long teamId;

    /** 团队名称。 */
    private String teamName;

    /** 正式发布版本。 */
    private Integer publishedRevision;

    /** 页面标题。 */
    private String title;

    /** 是否维护中。 */
    private boolean underMaintenance;

    /** 维护原因编码。 */
    private String maintenanceReason;

    /** 正式发布配置。 */
    private TeamPortfolioConfigDto config;

    /** 团队访客渲染模型。 */
    private TeamPortfolioRenderDto renderData;

    /** 访问汇总记录 ID。 */
    private Long visitRecordId;

    /** 访客登录令牌类型。 */
    private String tokenType;

    /** 访客加密登录令牌。 */
    private String token;

    /** 访客登录令牌有效期秒数。 */
    private Long expiresInSeconds;

    /** 服务端生成的匿名访客稳定键。 */
    private String visitorKey;

    /** 是否为本次新建访客。 */
    @JSONField(name = "isNewVisitor")
    private boolean newVisitor;

    /** 是否需要补充访客头像昵称。 */
    private boolean needVisitorProfile;

    /** 访客资料更新短期令牌。 */
    private String visitorProfileToken;
}
