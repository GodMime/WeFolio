package com.jxc.wefolio.service.teamportfolio.component.schedulequery;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队档期查询组件引用提取器。
 */
@Component
public class TeamScheduleQueryComponentReferenceExtractor {

    /** 组件标识非法提示。 */
    private static final String COMPONENT_KEY_INVALID_MESSAGE = "档期查询组件标识不能为空";

    /** 组件路径非法提示。 */
    private static final String COMPONENT_PATH_INVALID_MESSAGE = "档期查询组件路径不能为空";

    /** 组件上下文非法提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "档期查询组件上下文不正确";

    /** 展示方式配置键。 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 查询范围配置键。 */
    private static final String CONFIG_KEY_QUERY_RANGE = "queryRange";

    /** 团队档期查询组件渲染器。 */
    private final TeamScheduleQueryComponentRenderer renderer;

    /**
     * 创建引用提取器。
     *
     * @param renderer 团队档期查询组件渲染器
     */
    public TeamScheduleQueryComponentReferenceExtractor(TeamScheduleQueryComponentRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 重新渲染组件配置后提取唯一的团队档期组件引用。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化组件配置
     * @param context 团队组件上下文
     * @return 团队档期组件引用
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
        if (context == null || context.teamId() <= 0 || context.portfolioId() <= 0) {
            throw new BusinessException(CONTEXT_INVALID_MESSAGE);
        }
        JSONObject rendered = renderer.render(normalizedConfig, context);
        JSONObject snapshot = new JSONObject();
        snapshot.put(CONFIG_KEY_DISPLAY_MODE, rendered.getString(CONFIG_KEY_DISPLAY_MODE));
        snapshot.put(CONFIG_KEY_QUERY_RANGE, JSON.parseObject(JSON.toJSONString(
                rendered.getJSONObject(CONFIG_KEY_QUERY_RANGE), JSONWriter.Feature.WriteNulls)));
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(context.portfolioId());
        reference.setReferenceType(ReferenceTypeDict.SCHEDULE_COMPONENT.getCode());
        reference.setReferenceId(context.teamId());
        reference.setComponentKey(componentKey);
        reference.setComponentPath(componentPath);
        reference.setSortOrder(0);
        reference.setSnapshotJson(JSON.toJSONString(snapshot, JSONWriter.Feature.WriteNulls));
        reference.setIsValid(1);
        return List.of(reference);
    }
}
