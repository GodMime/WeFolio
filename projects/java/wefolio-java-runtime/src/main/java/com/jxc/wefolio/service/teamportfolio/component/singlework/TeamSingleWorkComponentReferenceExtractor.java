package com.jxc.wefolio.service.teamportfolio.component.singlework;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队单个作品组件引用提取器。
 */
@Component
@RequiredArgsConstructor
public class TeamSingleWorkComponentReferenceExtractor {

    /** 有效引用标识。 */
    private static final int REFERENCE_VALID = 1;

    /** 单个作品渲染器。 */
    private final TeamSingleWorkComponentRenderer renderer;

    /**
     * 提取单个 WORK 引用。
     *
     * @param componentKey 组件键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 单元素引用列表
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        JSONObject snapshot = renderer.render(normalizedConfig, context).getJSONObject("work");
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(context.portfolioId());
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(snapshot.getLong("workId"));
        reference.setComponentKey(componentKey);
        reference.setComponentPath(componentPath + ".work");
        reference.setSortOrder(0);
        reference.setIsValid(REFERENCE_VALID);
        reference.setSnapshotJson(JSON.toJSONString(snapshot, JSONWriter.Feature.WriteNulls));
        return List.of(reference);
    }
}
