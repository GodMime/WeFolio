package com.jxc.wefolio.service.teamportfolio.component.contactform;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

/**
 * 团队预留联系信息组件渲染器。
 */
@Component
public class TeamContactFormComponentRenderer {

    /** 团队预留联系信息组件配置校验器。 */
    private final TeamContactFormComponentValidator validator;

    /**
     * 创建团队预留联系信息组件渲染器。
     *
     * @param validator 团队预留联系信息组件配置校验器
     */
    public TeamContactFormComponentRenderer(TeamContactFormComponentValidator validator) {
        this.validator = validator;
    }

    /**
     * 重新完整校验配置，并返回与输入脱离的渲染快照。
     *
     * @param normalizedConfig 规范化配置
     * @param context 团队组件上下文
     * @return 独立的渲染配置快照
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        JSONObject validated = validator.normalizeAndValidate(normalizedConfig, context);
        TeamContactFormComponentConfig componentConfig = validated.toJavaObject(TeamContactFormComponentConfig.class);
        return JSON.parseObject(JSON.toJSONString(componentConfig));
    }
}
