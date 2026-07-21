package com.jxc.wefolio.service.teamportfolio.component.carousel;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * 团队轮播图组件配置。
 */
@Data
public class TeamCarouselComponentConfig {

    /** 条目配置键。 */
    private static final String CONFIG_KEY_ITEMS = "items";

    /** 成员用户 ID 配置键。 */
    private static final String CONFIG_KEY_MEMBER_USER_ID = "memberUserId";

    /** 作品 ID 配置键。 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 轮播图作品条目。 */
    private List<Item> items;

    /**
     * 将配置转换为仅含标识的 JSON。
     *
     * @return 轮播图配置 JSON
     */
    public JSONObject toJsonObject() {
        JSONObject config = new JSONObject();
        JSONArray jsonItems = new JSONArray();
        for (Item item : items == null ? List.<Item>of() : items) {
            JSONObject jsonItem = new JSONObject();
            jsonItem.put(CONFIG_KEY_MEMBER_USER_ID, item.getMemberUserId());
            jsonItem.put(CONFIG_KEY_WORK_ID, item.getWorkId());
            jsonItems.add(jsonItem);
        }
        config.put(CONFIG_KEY_ITEMS, jsonItems);
        return config;
    }

    /**
     * 轮播图单项配置。
     */
    @Data
    public static class Item {

        /** 团队成员用户 ID。 */
        private Long memberUserId;

        /** 作品 ID。 */
        private Long workId;
    }
}
