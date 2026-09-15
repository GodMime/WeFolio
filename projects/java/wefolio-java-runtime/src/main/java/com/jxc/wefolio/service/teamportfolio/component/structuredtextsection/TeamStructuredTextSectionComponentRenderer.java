package com.jxc.wefolio.service.teamportfolio.component.structuredtextsection;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.PortfolioStructuredTextConfigSupport;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 团队结构化文字渲染器。 */
@Component
@RequiredArgsConstructor
public class TeamStructuredTextSectionComponentRenderer {
    /** 共用团队背景资源支持。 */
    private final TeamTextBackgroundSupport backgroundSupport;
    /** 保留 AUTO 与文字布局，授权失效仅影响背景层。 */
    public JSONObject render(JSONObject config,TeamPortfolioComponentContext context) {
        JSONObject result = new JSONObject(PortfolioStructuredTextConfigSupport.forRender(config,true));
        backgroundSupport.render(result,result,context);
        return result;
    }
}
