package com.jxc.wefolio.service.teamportfolio.component.carousel;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队轮播图组件引用提取器。
 */
@Component
@RequiredArgsConstructor
public class TeamCarouselComponentReferenceExtractor {

    /** 条目配置键。 */
    private static final String CONFIG_KEY_ITEMS = "items";

    /** 作品 ID 配置键。 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 条目真实配置路径后缀。 */
    private static final String ITEM_PATH_SUFFIX = ".config.items[";

    /** 有效引用标识。 */
    private static final int REFERENCE_VALID = 1;

    /** 轮播图渲染器。 */
    private final TeamCarouselComponentRenderer renderer;

    /**
     * 使用逐项渲染快照提取作品引用。
     *
     * @param componentKey 组件实例键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 团队作品集组件上下文
     * @return 有序作品引用
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        JSONObject rendered = renderer.render(normalizedConfig, context);
        List<PortfolioReferenceEntity> references = new ArrayList<>();
        for (int index = 0; index < rendered.getJSONArray(CONFIG_KEY_ITEMS).size(); index++) {
            JSONObject item = rendered.getJSONArray(CONFIG_KEY_ITEMS).getJSONObject(index);
            PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
            reference.setPortfolioId(context.portfolioId());
            reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
            reference.setReferenceId(item.getLong(CONFIG_KEY_WORK_ID));
            reference.setComponentKey(componentKey);
            reference.setComponentPath(componentPath + ITEM_PATH_SUFFIX + index + "]");
            reference.setSortOrder(index);
            reference.setIsValid(REFERENCE_VALID);
            reference.setSnapshotJson(JSON.toJSONString(item, JSONWriter.Feature.WriteNulls));
            references.add(reference);
        }
        return references;
    }
}
