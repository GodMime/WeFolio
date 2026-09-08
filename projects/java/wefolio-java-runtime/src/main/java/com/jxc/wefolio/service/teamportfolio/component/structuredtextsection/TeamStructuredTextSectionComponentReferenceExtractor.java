package com.jxc.wefolio.service.teamportfolio.component.structuredtextsection;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.PortfolioStructuredTextConfigSupport;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 团队结构化文字引用提取器。 */
@Component
@RequiredArgsConstructor
public class TeamStructuredTextSectionComponentReferenceExtractor {
    /** 共用团队背景资源支持。 */
    private final TeamTextBackgroundSupport backgroundSupport;
    /** 提取结构化文字背景的真实 WORK 引用路径。 */
    public List<PortfolioReferenceEntity> extract(String key,String path,JSONObject config,TeamPortfolioComponentContext context) {
        return backgroundSupport.extract(key,path,config,context);
    }
}
