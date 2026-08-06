package com.jxc.wefolio.service.teamportfolio.component.videocarousel;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
 * 团队视频轮播作品引用提取器。
 */
@Component
@RequiredArgsConstructor
public class TeamVideoCarouselComponentReferenceExtractor {

    /** 条目配置键 */
    private static final String CONFIG_KEY_ITEMS = "items";

    /** 成员用户 ID 配置键 */
    private static final String CONFIG_KEY_MEMBER_USER_ID = "memberUserId";

    /** 作品 ID 配置键 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 有效引用标识 */
    private static final int REFERENCE_VALID = 1;

    /** 视频轮播渲染器 */
    private final TeamVideoCarouselComponentRenderer renderer;

    /**
     * 提取仍可展示的视频作品引用。
     *
     * @param componentKey 组件键
     * @param componentPath 组件路径
     * @param normalizedConfig 规范化配置
     * @param context 团队作品集上下文
     * @return 有序作品引用
     */
    public List<PortfolioReferenceEntity> extract(
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        JSONObject rendered = renderer.render(normalizedConfig, context);
        if (rendered == null) {
            return List.of();
        }
        JSONArray configuredItems = normalizedConfig.getJSONArray(CONFIG_KEY_ITEMS);
        JSONArray renderedItems = rendered.getJSONArray(CONFIG_KEY_ITEMS);
        List<PortfolioReferenceEntity> references = new ArrayList<>();
        for (Object value : renderedItems) {
            JSONObject renderedItem = (JSONObject) value;
            int configIndex = findConfigIndex(configuredItems, renderedItem);
            PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
            reference.setPortfolioId(context.portfolioId());
            reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
            reference.setReferenceId(renderedItem.getLong(CONFIG_KEY_WORK_ID));
            reference.setComponentKey(componentKey);
            reference.setComponentPath(componentPath + ".config.items[" + configIndex + "]");
            reference.setSortOrder(configIndex);
            reference.setIsValid(REFERENCE_VALID);
            reference.setSnapshotJson(JSON.toJSONString(renderedItem, JSONWriter.Feature.WriteNulls));
            references.add(reference);
        }
        return references;
    }

    /** 根据成员与作品标识找回原配置下标。 */
    private int findConfigIndex(JSONArray configuredItems, JSONObject renderedItem) {
        for (int index = 0; index < configuredItems.size(); index++) {
            JSONObject configured = configuredItems.getJSONObject(index);
            if (configured != null
                    && renderedItem.getLong(CONFIG_KEY_MEMBER_USER_ID).equals(
                    configured.getLong(CONFIG_KEY_MEMBER_USER_ID))
                    && renderedItem.getLong(CONFIG_KEY_WORK_ID).equals(configured.getLong(CONFIG_KEY_WORK_ID))) {
                return index;
            }
        }
        throw new IllegalStateException("视频轮播渲染条目无法映射回原配置");
    }
}
