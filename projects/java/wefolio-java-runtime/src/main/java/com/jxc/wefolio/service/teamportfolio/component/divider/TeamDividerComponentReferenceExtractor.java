package com.jxc.wefolio.service.teamportfolio.component.divider;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分割线组件引用提取器。
 */
@Component
public class TeamDividerComponentReferenceExtractor {

    /**
     * 分割线不引用业务资源。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 不可变空引用列表
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        return List.of();
    }
}
