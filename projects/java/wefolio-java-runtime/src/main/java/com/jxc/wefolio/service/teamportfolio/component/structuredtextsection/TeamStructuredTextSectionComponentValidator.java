package com.jxc.wefolio.service.teamportfolio.component.structuredtextsection;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.PortfolioStructuredTextConfigSupport;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 团队结构化文字配置校验器。 */
@Component
@RequiredArgsConstructor
public class TeamStructuredTextSectionComponentValidator {
    /** 共用团队背景资源支持。 */
    private final TeamTextBackgroundSupport backgroundSupport;
    /** 规范化独立区块并重新验证团队背景资源。 */
    public JSONObject normalizeAndValidate(JSONObject config,TeamPortfolioComponentContext context) {
        JSONObject result = new JSONObject(PortfolioStructuredTextConfigSupport.normalize(config,true));
        backgroundSupport.validate(result,context);
        return result;
    }
}
