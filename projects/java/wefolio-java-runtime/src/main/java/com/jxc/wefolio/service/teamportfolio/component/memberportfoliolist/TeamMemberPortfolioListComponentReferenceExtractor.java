package com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单列成员作品集组件引用提取器。
 */
@RequiredArgsConstructor
@Component
public class TeamMemberPortfolioListComponentReferenceExtractor {

    /** 渲染数据条目字段。 */
    private static final String ITEMS_FIELD = "items";

    /** 个人作品集 ID 字段。 */
    private static final String PORTFOLIO_ID_FIELD = "portfolioId";

    /** 成员作品集引用类型。 */
    private static final String MEMBER_PORTFOLIO_REFERENCE_TYPE = "MEMBER_PORTFOLIO";

    /** 条目路径后缀前缀。 */
    private static final String ITEMS_PATH_PREFIX = ".items[";

    /** 条目路径后缀。 */
    private static final String ITEMS_PATH_SUFFIX = "]";

    /** 有效引用标识。 */
    private static final int VALID_REFERENCE = 1;

    /** 单列成员作品集渲染器。 */
    private final TeamMemberPortfolioListComponentRenderer renderer;

    /**
     * 从规范化配置提取成员作品集引用。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 团队作品集上下文
     * @return 有序引用列表
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        JSONObject rendered = renderer.render(normalizedConfig, context);
        JSONArray items = rendered.getJSONArray(ITEMS_FIELD);
        List<PortfolioReferenceEntity> references = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            JSONObject item = items.getJSONObject(index);
            PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
            reference.setPortfolioId(context.portfolioId());
            reference.setReferenceType(MEMBER_PORTFOLIO_REFERENCE_TYPE);
            reference.setReferenceId(item.getLong(PORTFOLIO_ID_FIELD));
            reference.setComponentKey(componentKey);
            reference.setComponentPath(componentPath + ITEMS_PATH_PREFIX + index + ITEMS_PATH_SUFFIX);
            reference.setSortOrder(index);
            reference.setIsValid(VALID_REFERENCE);
            reference.setSnapshotJson(JSON.toJSONString(item, JSONWriter.Feature.WriteNulls));
            references.add(reference);
        }
        return references;
    }
}
