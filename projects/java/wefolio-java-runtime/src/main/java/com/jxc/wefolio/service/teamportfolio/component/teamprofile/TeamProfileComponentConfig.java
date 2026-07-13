package com.jxc.wefolio.service.teamportfolio.component.teamprofile;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队资料组件配置。
 */
@Data
public class TeamProfileComponentConfig {

    /** 团队配置键。 */
    private static final String CONFIG_KEY_TEAM = "team";

    /** 展示字段配置键。 */
    private static final String CONFIG_KEY_VISIBLE_FIELDS = "visibleFields";

    /** 团队 ID 配置键。 */
    private static final String CONFIG_KEY_TEAM_ID = "teamId";

    /** 团队头像配置键。 */
    private static final String CONFIG_KEY_AVATAR_URL = "avatarUrl";

    /** 团队名称配置键。 */
    private static final String CONFIG_KEY_TEAM_NAME = "teamName";

    /** 团队简介配置键。 */
    private static final String CONFIG_KEY_INTRO = "intro";

    /** 团队头像展示字段。 */
    private static final String VISIBLE_FIELD_AVATAR = "avatar";

    /** 团队名称展示字段。 */
    private static final String VISIBLE_FIELD_TEAM_NAME = "teamName";

    /** 团队简介展示字段。 */
    private static final String VISIBLE_FIELD_INTRO = "intro";

    /** 默认组件实例键。 */
    private static final String DEFAULT_COMPONENT_KEY = "team-profile";

    /** 团队资料组件类型。 */
    private static final String COMPONENT_TYPE = "TEAM_PROFILE";

    /** 默认排序值。 */
    private static final int DEFAULT_SORT_ORDER = 1000;

    /** 团队资料快照。 */
    private TeamSnapshot team;

    /** 团队资料展示字段开关。 */
    private Map<String, Boolean> visibleFields;

    /**
     * 创建团队资料组件的默认私有配置。
     *
     * @return 独立的空配置
     */
    public static JSONObject defaultConfig() {
        return new JSONObject();
    }

    /**
     * 创建团队资料组件的默认信封。
     *
     * @return 独立的默认组件信封
     */
    public static TeamPortfolioConfigDto.ComponentEnvelope defaultEnvelope() {
        TeamPortfolioConfigDto.ComponentEnvelope envelope = new TeamPortfolioConfigDto.ComponentEnvelope();
        envelope.setComponentKey(DEFAULT_COMPONENT_KEY);
        envelope.setComponentType(COMPONENT_TYPE);
        envelope.setSortOrder(DEFAULT_SORT_ORDER);
        envelope.setEnabled(true);
        envelope.setConfig(defaultConfig());
        return envelope;
    }

    /**
     * 将团队资料配置转换为保留全部快照字段的 JSON。
     *
     * @return 团队资料配置 JSON
     */
    public JSONObject toJsonObject() {
        JSONObject config = new JSONObject();
        config.put(CONFIG_KEY_TEAM, team.toJsonObject());
        config.put(CONFIG_KEY_VISIBLE_FIELDS, visibleFieldsToJsonObject());
        return config;
    }

    /**
     * 构建补齐默认值的展示字段配置。
     *
     * @return 独立的展示字段 JSON
     */
    private JSONObject visibleFieldsToJsonObject() {
        Map<String, Boolean> normalized = normalizeVisibleFields(visibleFields);
        JSONObject fields = new JSONObject();
        normalized.forEach(fields::put);
        return fields;
    }

    /**
     * 规范化展示字段配置，兼容未保存开关的历史团队作品集。
     *
     * @param source 原始展示字段配置
     * @return 包含全部团队资料字段的开关
     */
    static Map<String, Boolean> normalizeVisibleFields(Map<String, Boolean> source) {
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        normalized.put(VISIBLE_FIELD_AVATAR, visibleFieldValue(source, VISIBLE_FIELD_AVATAR));
        normalized.put(VISIBLE_FIELD_TEAM_NAME, visibleFieldValue(source, VISIBLE_FIELD_TEAM_NAME));
        normalized.put(VISIBLE_FIELD_INTRO, visibleFieldValue(source, VISIBLE_FIELD_INTRO));
        return normalized;
    }

    /**
     * 获取单个展示字段开关，缺失时默认展示。
     *
     * @param source 原始展示字段配置
     * @param field 字段名
     * @return 是否展示
     */
    private static boolean visibleFieldValue(Map<String, Boolean> source, String field) {
        return source == null || !source.containsKey(field) || Boolean.TRUE.equals(source.get(field));
    }

    /**
     * 团队资料展示快照。
     */
    @Data
    public static class TeamSnapshot {

        /** 团队 ID。 */
        private Long teamId;

        /** 团队头像地址。 */
        private String avatarUrl;

        /** 团队名称。 */
        private String teamName;

        /** 团队简介。 */
        private String intro;

        /**
         * 将团队快照转换为保留全部字段的 JSON。
         *
         * @return 团队快照 JSON
         */
        public JSONObject toJsonObject() {
            JSONObject snapshot = new ExplicitNullTeamSnapshotJsonObject();
            snapshot.put(CONFIG_KEY_TEAM_ID, teamId);
            snapshot.put(CONFIG_KEY_AVATAR_URL, avatarUrl);
            snapshot.put(CONFIG_KEY_TEAM_NAME, teamName);
            snapshot.put(CONFIG_KEY_INTRO, intro);
            return snapshot;
        }
    }

    /**
     * 保证团队快照经现有引用提取逻辑序列化时保留显式空值。
     */
    private static class ExplicitNullTeamSnapshotJsonObject extends JSONObject {

        /**
         * 使用写入空值特性序列化团队快照。
         *
         * @param features 调用方指定的序列化特性
         * @return 团队快照 JSON 文本
         */
        @Override
        public String toJSONString(JSONWriter.Feature... features) {
            return JSON.toJSONString(this, JSONWriter.Feature.WriteNulls);
        }
    }
}
