package com.jxc.wefolio.service.teamportfolio.component.videocarousel;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * 团队视频轮播组件配置。
 */
@Data
public class TeamVideoCarouselComponentConfig {

    /** 标题配置键 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 条目配置键 */
    private static final String CONFIG_KEY_ITEMS = "items";

    /** 成员用户 ID 配置键 */
    private static final String CONFIG_KEY_MEMBER_USER_ID = "memberUserId";

    /** 作品 ID 配置键 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 展示作品标题配置键 */
    private static final String CONFIG_KEY_SHOW_TITLE = "showTitle";

    /** 展示滑动提示配置键 */
    private static final String CONFIG_KEY_SHOW_SWIPE_HINT = "showSwipeHint";

    /** 组件标题 */
    private String title;

    /** 有序视频作品条目 */
    private List<Item> items;

    /** 是否展示作品标题 */
    private boolean showTitle;

    /** 是否展示滑动提示 */
    private boolean showSwipeHint;

    /**
     * 转换为只含展示开关和资源标识的 JSON。
     *
     * @return 规范化配置
     */
    public JSONObject toJsonObject() {
        JSONObject config = new JSONObject();
        config.put(CONFIG_KEY_TITLE, title);
        JSONArray jsonItems = new JSONArray();
        for (Item item : items == null ? List.<Item>of() : items) {
            JSONObject jsonItem = new JSONObject();
            jsonItem.put(CONFIG_KEY_MEMBER_USER_ID, item.getMemberUserId());
            jsonItem.put(CONFIG_KEY_WORK_ID, item.getWorkId());
            jsonItems.add(jsonItem);
        }
        config.put(CONFIG_KEY_ITEMS, jsonItems);
        config.put(CONFIG_KEY_SHOW_TITLE, showTitle);
        config.put(CONFIG_KEY_SHOW_SWIPE_HINT, showSwipeHint);
        return config;
    }

    /**
     * 视频作品标识条目。
     */
    @Data
    public static class Item {

        /** 成员用户 ID */
        private Long memberUserId;

        /** 作品 ID */
        private Long workId;
    }
}
