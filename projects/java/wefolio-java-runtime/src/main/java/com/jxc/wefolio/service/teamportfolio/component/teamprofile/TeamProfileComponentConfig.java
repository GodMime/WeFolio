package com.jxc.wefolio.service.teamportfolio.component.teamprofile;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import lombok.Data;

/**
 * 团队资料组件配置。
 */
@Data
public class TeamProfileComponentConfig {

    /** 团队配置键。 */
    private static final String CONFIG_KEY_TEAM = "team";

    /** 团队 ID 配置键。 */
    private static final String CONFIG_KEY_TEAM_ID = "teamId";

    /** 团队头像配置键。 */
    private static final String CONFIG_KEY_AVATAR_URL = "avatarUrl";

    /** 团队名称配置键。 */
    private static final String CONFIG_KEY_TEAM_NAME = "teamName";

    /** 团队简介配置键。 */
    private static final String CONFIG_KEY_INTRO = "intro";

    /** 默认组件实例键。 */
    private static final String DEFAULT_COMPONENT_KEY = "team-profile";

    /** 团队资料组件类型。 */
    private static final String COMPONENT_TYPE = "TEAM_PROFILE";

    /** 默认排序值。 */
    private static final int DEFAULT_SORT_ORDER = 1000;

    /** 团队资料快照。 */
    private TeamSnapshot team;

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
        return config;
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
