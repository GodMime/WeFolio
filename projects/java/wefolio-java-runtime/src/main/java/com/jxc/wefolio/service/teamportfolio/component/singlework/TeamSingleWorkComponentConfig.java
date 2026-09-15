package com.jxc.wefolio.service.teamportfolio.component.singlework;

import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.PortfolioComponentDisplayOptionsSupport;
import lombok.Data;

/**
 * 团队单个作品组件配置。
 */
@Data
public class TeamSingleWorkComponentConfig {

    /** 成员用户 ID。 */
    private Long memberUserId;

    /** 作品 ID。 */
    private Long workId;

    /** 是否展示作品标题。 */
    private Boolean showTitle;

    /** 是否展示作品说明。 */
    private Boolean showDescription;

    /** 打开方式。 */
    private String openMode;
    /** 独立详情展示开关。 */
    private Map<String, Object> detailOptions;

    /**
     * 转换为严格白名单配置。
     *
     * @return 规范化 JSON
     */
    public JSONObject toJsonObject() {
        JSONObject config = new JSONObject();
        config.put("memberUserId", memberUserId);
        config.put("workId", workId);
        config.put("showTitle", showTitle);
        config.put("showDescription", showDescription);
        config.put(PortfolioComponentDisplayOptionsSupport.OPEN_MODE, openMode);
        config.put(PortfolioComponentDisplayOptionsSupport.DETAIL_OPTIONS, detailOptions);
        return config;
    }
}
