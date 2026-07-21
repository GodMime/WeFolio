package com.jxc.wefolio.service.teamportfolio.component.carousel;

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
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队轮播图组件配置校验器。
 */
@Component
@RequiredArgsConstructor
public class TeamCarouselComponentValidator {

    /** 条目配置键。 */
    private static final String CONFIG_KEY_ITEMS = "items";

    /** 成员用户 ID 配置键。 */
    private static final String CONFIG_KEY_MEMBER_USER_ID = "memberUserId";

    /** 作品 ID 配置键。 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 允许的最少条目数。 */
    private static final int ITEM_MIN_COUNT = 1;

    /** 允许的最多条目数。 */
    private static final int ITEM_MAX_COUNT = 9;

    /** 允许作品授权标识。 */
    private static final int ALLOW_WORKS = 1;

    /** 配置格式错误提示。 */
    private static final String CONFIG_INVALID_MESSAGE = "轮播图作品配置不正确";

    /** 条目不足提示。 */
    private static final String ITEM_MIN_MESSAGE = "轮播图至少选择一张图片";

    /** 条目过多提示。 */
    private static final String ITEM_MAX_MESSAGE = "轮播图最多选择9张图片";

    /** 重复作品提示。 */
    private static final String WORK_DUPLICATE_MESSAGE = "轮播图不能重复选择同一作品";

    /** 非图片作品提示。 */
    private static final String IMAGE_ONLY_MESSAGE = "轮播图仅支持图片作品";

    /** 作品不可用提示。 */
    private static final String WORK_UNAVAILABLE_MESSAGE = "轮播图作品不存在或不可用";

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper。 */
    private final WorkEntityMapper workEntityMapper;

    /**
     * 校验团队轮播图配置并只保留成员和作品标识。
     *
     * @param config 原始配置
     * @param context 团队作品集组件上下文
     * @return 规范化配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamCarouselComponentConfig componentConfig = toComponentConfig(config);
        List<TeamCarouselComponentConfig.Item> items = componentConfig.getItems();
        validateItemCount(items.size());
        validateDuplicateWorkIds(items);
        validateResources(items, context);
        return componentConfig.toJsonObject();
    }

    /**
     * 严格解析原始 JSON，并通过配置模型完成生产转换。
     *
     * @param config 原始配置
     * @return 已校验标识类型的组件配置
     */
    private TeamCarouselComponentConfig toComponentConfig(JSONObject config) {
        JSONArray rawItems = requireItemsArray(config);
        List<TeamCarouselComponentConfig.Item> convertedItems;
        try {
            TeamCarouselComponentConfig convertedConfig = config.toJavaObject(TeamCarouselComponentConfig.class);
            convertedItems = convertedConfig == null ? null : convertedConfig.getItems();
        } catch (RuntimeException exception) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        if (convertedItems == null || convertedItems.size() != rawItems.size()) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }

        TeamCarouselComponentConfig normalizedConfig = new TeamCarouselComponentConfig();
        normalizedConfig.setItems(new java.util.ArrayList<>());
        for (int index = 0; index < rawItems.size(); index++) {
            Object rawItem = rawItems.get(index);
            if (!(rawItem instanceof JSONObject itemJson)
                    || !itemJson.containsKey(CONFIG_KEY_MEMBER_USER_ID)
                    || !itemJson.containsKey(CONFIG_KEY_WORK_ID)) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
            TeamCarouselComponentConfig.Item convertedItem = convertedItems.get(index);
            if (convertedItem == null) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
            TeamCarouselComponentConfig.Item item = new TeamCarouselComponentConfig.Item();
            item.setMemberUserId(parsePositiveLong(itemJson.get(CONFIG_KEY_MEMBER_USER_ID)));
            item.setWorkId(parsePositiveLong(itemJson.get(CONFIG_KEY_WORK_ID)));
            normalizedConfig.getItems().add(item);
        }
        return normalizedConfig;
    }

    /**
     * 获取条目数组，拒绝缺失和错误结构。
     *
     * @param config 原始配置
     * @return 原始条目数组
     */
    private JSONArray requireItemsArray(JSONObject config) {
        if (config == null || !config.containsKey(CONFIG_KEY_ITEMS)
                || !(config.get(CONFIG_KEY_ITEMS) instanceof JSONArray items)) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        return items;
    }

    /**
     * 解析正整数 Long，拒绝字符串、小数、数组和超范围值。
     *
     * @param rawValue 原始值
     * @return 正整数 Long
     */
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
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        if (value <= 0) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        return value;
    }

    /**
     * 校验条目数量边界。
     *
     * @param itemCount 条目数量
     */
    private void validateItemCount(int itemCount) {
        if (itemCount < ITEM_MIN_COUNT) {
            throw new BusinessException(ITEM_MIN_MESSAGE);
        }
        if (itemCount > ITEM_MAX_COUNT) {
            throw new BusinessException(ITEM_MAX_MESSAGE);
        }
    }

    /**
     * 校验作品不可重复。
     *
     * @param items 规范化条目
     */
    private void validateDuplicateWorkIds(List<TeamCarouselComponentConfig.Item> items) {
        Set<Long> workIds = new LinkedHashSet<>();
        for (TeamCarouselComponentConfig.Item item : items) {
            if (!workIds.add(item.getWorkId())) {
                throw new BusinessException(WORK_DUPLICATE_MESSAGE);
            }
        }
    }

    /**
     * 用固定次数的批量查询校验成员、用户和作品的全部可用性。
     *
     * @param items 规范化条目
     * @param context 团队作品集组件上下文
     */
    private void validateResources(List<TeamCarouselComponentConfig.Item> items, TeamPortfolioComponentContext context) {
        Set<Long> memberUserIds = items.stream()
                .map(TeamCarouselComponentConfig.Item::getMemberUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> workIds = items.stream()
                .map(TeamCarouselComponentConfig.Item::getWorkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TeamMemberEntity> memberships = membershipByUserId(context.teamId(), memberUserIds);
        for (TeamCarouselComponentConfig.Item item : items) {
            if (!memberships.containsKey(item.getMemberUserId())) {
                throw new BusinessException(WORK_UNAVAILABLE_MESSAGE);
            }
        }
        Map<Long, UserEntity> activeUsers = activeUsersById(memberUserIds);
        for (TeamCarouselComponentConfig.Item item : items) {
            if (!activeUsers.containsKey(item.getMemberUserId())) {
                throw new BusinessException(WORK_UNAVAILABLE_MESSAGE);
            }
        }
        Map<Long, WorkEntity> works = worksById(workIds);

        for (TeamCarouselComponentConfig.Item item : items) {
            WorkEntity work = works.get(item.getWorkId());
            if (work == null
                    || !item.getMemberUserId().equals(work.getUserId())
                    || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                    || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())) {
                throw new BusinessException(WORK_UNAVAILABLE_MESSAGE);
            }
            if (MediaTypeDict.VIDEO.getCode().equals(work.getMediaType())) {
                throw new BusinessException(IMAGE_ONLY_MESSAGE);
            }
            if (!MediaTypeDict.IMAGE.getCode().equals(work.getMediaType())) {
                throw new BusinessException(WORK_UNAVAILABLE_MESSAGE);
            }
        }
    }

    /**
     * 按团队和授权条件批量加载成员关系。
     *
     * @param teamId 团队 ID
     * @param memberUserIds 成员用户 ID 集合
     * @return 用户 ID 到成员关系的映射
     */
    private Map<Long, TeamMemberEntity> membershipByUserId(long teamId, Set<Long> memberUserIds) {
        List<TeamMemberEntity> memberships = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .in(TeamMemberEntity::getUserId, memberUserIds)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
        );
        Map<Long, TeamMemberEntity> result = new LinkedHashMap<>();
        for (TeamMemberEntity membership : memberships == null ? List.<TeamMemberEntity>of() : memberships) {
            if (membership != null && membership.getUserId() != null) {
                result.put(membership.getUserId(), membership);
            }
        }
        return result;
    }

    /**
     * 批量加载状态正常的用户。
     *
     * @param memberUserIds 成员用户 ID 集合
     * @return 用户 ID 到用户记录的映射
     */
    private Map<Long, UserEntity> activeUsersById(Set<Long> memberUserIds) {
        List<UserEntity> users = userEntityMapper.selectBatchIds(memberUserIds);
        Map<Long, UserEntity> result = new LinkedHashMap<>();
        for (UserEntity user : users == null ? List.<UserEntity>of() : users) {
            if (user != null && user.getId() != null && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                result.put(user.getId(), user);
            }
        }
        return result;
    }

    /**
     * 批量加载待校验作品。
     *
     * @param workIds 作品 ID 集合
     * @return 作品 ID 到作品记录的映射
     */
    private Map<Long, WorkEntity> worksById(Set<Long> workIds) {
        List<WorkEntity> works = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class).in(WorkEntity::getId, workIds)
        );
        Map<Long, WorkEntity> result = new LinkedHashMap<>();
        for (WorkEntity work : works == null ? List.<WorkEntity>of() : works) {
            if (work != null && work.getId() != null) {
                result.put(work.getId(), work);
            }
        }
        return result;
    }
}
