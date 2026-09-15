package com.jxc.wefolio.service.teamportfolio.component.textsection;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.PortfolioTextBackgroundConfigSupport;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import lombok.RequiredArgsConstructor;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文字说明组件引用提取器。
 */
@Component
@RequiredArgsConstructor
public class TeamTextSectionComponentReferenceExtractor {

    /** 共用团队背景资源支持。 */
    private final TeamTextBackgroundSupport backgroundSupport;

    /**
     * 仅为已启用的文字背景生成 WORK 引用。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 当前背景位置的作品引用，关闭背景时为空
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        return backgroundSupport.extract(componentKey,componentPath,normalizedConfig,context);
    }
}
