package com.jxc.wefolio.service.teamportfolio.component.contactform;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队预留联系信息组件引用提取器。
 */
@Component
public class TeamContactFormComponentReferenceExtractor {

    /** 组件标识非法提示。 */
    private static final String COMPONENT_KEY_INVALID_MESSAGE = "预留联系信息组件标识不能为空";

    /** 组件路径非法提示。 */
    private static final String COMPONENT_PATH_INVALID_MESSAGE = "预留联系信息组件路径不能为空";

    /** 团队预留联系信息组件渲染器。 */
    private final TeamContactFormComponentRenderer renderer;

    /**
     * 创建团队预留联系信息组件引用提取器。
     *
     * @param renderer 团队预留联系信息组件渲染器
     */
    public TeamContactFormComponentReferenceExtractor(TeamContactFormComponentRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 预留联系信息不产生资源引用，但会重新渲染拒绝非法配置。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 团队组件上下文
     * @return 不可变空引用列表
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        if (componentKey == null || componentKey.isBlank()) {
            throw new BusinessException(COMPONENT_KEY_INVALID_MESSAGE);
        }
        if (componentPath == null || componentPath.isBlank()) {
            throw new BusinessException(COMPONENT_PATH_INVALID_MESSAGE);
        }
        renderer.render(normalizedConfig, context);
        return List.of();
    }
}
