package com.jxc.wefolio.service.teamportfolio.component.videocarousel;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队视频轮播组件严格校验器。
 */
@Component
@RequiredArgsConstructor
public class TeamVideoCarouselComponentValidator {

    /** 默认标题 */
    private static final String DEFAULT_TITLE = "视频作品";

    /** 标题最大 Unicode 码点数 */
    private static final int TITLE_MAX_CODE_POINTS = 10;

    /** 最少条目数 */
    private static final int ITEM_MIN_COUNT = 3;

    /** 最多条目数 */
    private static final int ITEM_MAX_COUNT = 8;

    /** 允许引用作品标识 */
    private static final int ALLOW_WORKS = 1;

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

    /** 团队成员 Mapper */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /**
     * 严格校验并规范化视频轮播配置。
     *
     * @param rawConfig 原始配置
     * @param context 团队作品集上下文
     * @return 只含标识和展示开关的规范化配置
     */
    public JSONObject normalizeAndValidate(JSONObject rawConfig, TeamPortfolioComponentContext context) {
        TeamVideoCarouselComponentConfig config = parseConfig(rawConfig);
        validateResources(config.getItems(), context);
        return config.toJsonObject();
    }

    /** 严格解析配置结构和标识。 */
    private TeamVideoCarouselComponentConfig parseConfig(JSONObject rawConfig) {
        if (rawConfig == null || !(rawConfig.get(CONFIG_KEY_ITEMS) instanceof JSONArray rawItems)) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
        }
        if (rawItems.size() < ITEM_MIN_COUNT || rawItems.size() > ITEM_MAX_COUNT) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_ITEM_COUNT_INVALID);
        }
        Object rawTitle = rawConfig.get(CONFIG_KEY_TITLE);
        String title = rawTitle instanceof String text ? text.strip() : "";
        if (title.isEmpty()) {
            title = DEFAULT_TITLE;
        }
        if (title.codePointCount(0, title.length()) > TITLE_MAX_CODE_POINTS) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_TITLE_TOO_LONG);
        }

        List<TeamVideoCarouselComponentConfig.Item> items = new ArrayList<>();
        Set<Long> workIds = new LinkedHashSet<>();
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof JSONObject itemJson)
                    || !itemJson.containsKey(CONFIG_KEY_MEMBER_USER_ID)
                    || !itemJson.containsKey(CONFIG_KEY_WORK_ID)) {
                throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
            }
            TeamVideoCarouselComponentConfig.Item item = new TeamVideoCarouselComponentConfig.Item();
            item.setMemberUserId(parsePositiveLong(itemJson.get(CONFIG_KEY_MEMBER_USER_ID)));
            item.setWorkId(parsePositiveLong(itemJson.get(CONFIG_KEY_WORK_ID)));
            if (!workIds.add(item.getWorkId())) {
                throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_WORK_DUPLICATE);
            }
            items.add(item);
        }

        TeamVideoCarouselComponentConfig config = new TeamVideoCarouselComponentConfig();
        config.setTitle(title);
        config.setItems(items);
        config.setShowTitle(booleanValue(rawConfig.get(CONFIG_KEY_SHOW_TITLE), true));
        config.setShowSwipeHint(booleanValue(rawConfig.get(CONFIG_KEY_SHOW_SWIPE_HINT), true));
        return config;
    }

    /** 校验当前成员授权、账号与作品状态。 */
    private void validateResources(
            List<TeamVideoCarouselComponentConfig.Item> items,
            TeamPortfolioComponentContext context
    ) {
        if (context == null || context.teamId() <= 0) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
        }
        Set<Long> memberUserIds = items.stream()
                .map(TeamVideoCarouselComponentConfig.Item::getMemberUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> workIds = items.stream()
                .map(TeamVideoCarouselComponentConfig.Item::getWorkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TeamMemberEntity> memberships = membershipsByUserId(context.teamId(), memberUserIds);
        Map<Long, UserEntity> users = activeUsersById(memberUserIds);
        Map<Long, WorkEntity> works = worksById(workIds);
        for (TeamVideoCarouselComponentConfig.Item item : items) {
            WorkEntity work = works.get(item.getWorkId());
            if (!memberships.containsKey(item.getMemberUserId())
                    || !users.containsKey(item.getMemberUserId())
                    || work == null
                    || !item.getMemberUserId().equals(work.getUserId())
                    || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                    || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                    || !MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())) {
                throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_WORK_UNAVAILABLE);
            }
        }
    }

    /** 批量读取已加入且允许作品引用的成员。 */
    private Map<Long, TeamMemberEntity> membershipsByUserId(long teamId, Set<Long> memberUserIds) {
        List<TeamMemberEntity> rows = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .in(TeamMemberEntity::getUserId, memberUserIds)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS));
        Map<Long, TeamMemberEntity> result = new LinkedHashMap<>();
        for (TeamMemberEntity row : safeList(rows)) {
            if (row != null && row.getUserId() != null
                    && (row.getTeamId() == null || row.getTeamId() == teamId)
                    && (row.getJoinStatus() == null || JoinStatusDict.JOINED.getCode().equals(row.getJoinStatus()))
                    && (row.getAllowWorks() == null || row.getAllowWorks() == ALLOW_WORKS)) {
                result.put(row.getUserId(), row);
            }
        }
        return result;
    }

    /** 批量读取正常成员账号。 */
    private Map<Long, UserEntity> activeUsersById(Set<Long> memberUserIds) {
        Map<Long, UserEntity> result = new LinkedHashMap<>();
        for (UserEntity user : safeList(userEntityMapper.selectBatchIds(memberUserIds))) {
            if (user != null && user.getId() != null
                    && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                result.put(user.getId(), user);
            }
        }
        return result;
    }

    /** 批量读取作品。 */
    private Map<Long, WorkEntity> worksById(Set<Long> workIds) {
        List<WorkEntity> rows = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class).in(WorkEntity::getId, workIds));
        Map<Long, WorkEntity> result = new LinkedHashMap<>();
        for (WorkEntity work : safeList(rows)) {
            if (work != null && work.getId() != null) {
                result.put(work.getId(), work);
            }
        }
        return result;
    }

    /** 精确解析正整数 Long。 */
    private Long parsePositiveLong(Object rawValue) {
        long value;
        try {
            if (rawValue instanceof Byte number) {
                value = number;
            } else if (rawValue instanceof Short number) {
                value = number;
            } else if (rawValue instanceof Integer number) {
                value = number;
            } else if (rawValue instanceof Long number) {
                value = number;
            } else if (rawValue instanceof BigDecimal number) {
                value = number.longValueExact();
            } else if (rawValue instanceof BigInteger number) {
                value = number.longValueExact();
            } else {
                throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
        }
        if (value <= 0L) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
        }
        return value;
    }

    /** 读取布尔开关，非布尔值使用缺省值。 */
    private boolean booleanValue(Object value, boolean defaultValue) {
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    /** 空列表兜底。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
