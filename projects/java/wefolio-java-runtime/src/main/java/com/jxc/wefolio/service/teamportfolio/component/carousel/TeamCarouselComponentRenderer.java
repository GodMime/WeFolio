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
import com.jxc.wefolio.service.CosService;
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
 * 团队轮播图组件渲染器。
 */
@Component
@RequiredArgsConstructor
public class TeamCarouselComponentRenderer {

    /** 条目配置键。 */
    private static final String CONFIG_KEY_ITEMS = "items";

    /** 成员用户 ID 配置键。 */
    private static final String CONFIG_KEY_MEMBER_USER_ID = "memberUserId";

    /** 作品 ID 配置键。 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 成员展示名称键。 */
    private static final String RENDER_KEY_MEMBER_DISPLAY_NAME = "memberDisplayName";

    /** 成员头像键。 */
    private static final String RENDER_KEY_MEMBER_AVATAR_URL = "memberAvatarUrl";

    /** 作品标题键。 */
    private static final String RENDER_KEY_TITLE = "title";

    /** 作品封面地址键。 */
    private static final String RENDER_KEY_COVER_URL = "coverUrl";

    /** 作品媒体地址键。 */
    private static final String RENDER_KEY_MEDIA_URL = "mediaUrl";

    /** 作品宽度键。 */
    private static final String RENDER_KEY_WIDTH = "width";

    /** 作品高度键。 */
    private static final String RENDER_KEY_HEIGHT = "height";

    /** 作品比例键。 */
    private static final String RENDER_KEY_ASPECT_RATIO = "aspectRatio";

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

    /** 允许的最少条目数。 */
    private static final int ITEM_MIN_COUNT = 1;

    /** 允许的最多条目数。 */
    private static final int ITEM_MAX_COUNT = 9;

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

    /** COS 服务。 */
    private final CosService cosService;

    /**
     * 批量加载资源并返回脱离输入的轮播图展示快照。
     *
     * @param normalizedConfig 规范化配置
     * @param context 团队作品集组件上下文
     * @return 可展示的轮播图数据
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        List<TeamCarouselComponentConfig.Item> items = parseNormalizedItems(normalizedConfig);
        validateItemCount(items.size());
        validateDuplicateWorkIds(items);
        Set<Long> memberUserIds = items.stream()
                .map(TeamCarouselComponentConfig.Item::getMemberUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> workIds = items.stream()
                .map(TeamCarouselComponentConfig.Item::getWorkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TeamMemberEntity> memberships = membershipsByUserId(context.teamId(), memberUserIds);
        for (TeamCarouselComponentConfig.Item item : items) {
            if (!memberships.containsKey(item.getMemberUserId())) {
                throw new BusinessException(WORK_UNAVAILABLE_MESSAGE);
            }
        }
        Map<Long, UserEntity> users = activeUsersById(memberUserIds);
        for (TeamCarouselComponentConfig.Item item : items) {
            if (!users.containsKey(item.getMemberUserId())) {
                throw new BusinessException(WORK_UNAVAILABLE_MESSAGE);
            }
        }
        Map<Long, WorkEntity> works = worksById(workIds);

        JSONArray renderedItems = new JSONArray();
        for (TeamCarouselComponentConfig.Item item : items) {
            TeamMemberEntity membership = memberships.get(item.getMemberUserId());
            UserEntity user = users.get(item.getMemberUserId());
            WorkEntity work = works.get(item.getWorkId());
            validatePair(item, membership, user, work);
            renderedItems.add(renderItem(item, membership, user, work));
        }
        JSONObject rendered = new JSONObject();
        rendered.put(CONFIG_KEY_ITEMS, renderedItems);
        return rendered;
    }

    /**
     * 严格解析规范化配置，并使用组件模型完成转换。
     *
     * @param normalizedConfig 规范化配置
     * @return 有序条目
     */
    private List<TeamCarouselComponentConfig.Item> parseNormalizedItems(JSONObject normalizedConfig) {
        if (normalizedConfig == null || !normalizedConfig.containsKey(CONFIG_KEY_ITEMS)
                || !(normalizedConfig.get(CONFIG_KEY_ITEMS) instanceof JSONArray rawItems)) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        TeamCarouselComponentConfig convertedConfig;
        try {
            convertedConfig = normalizedConfig.toJavaObject(TeamCarouselComponentConfig.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        if (convertedConfig == null || convertedConfig.getItems() == null
                || convertedConfig.getItems().size() != rawItems.size()) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        List<TeamCarouselComponentConfig.Item> items = new ArrayList<>();
        for (int index = 0; index < rawItems.size(); index++) {
            Object rawItem = rawItems.get(index);
            if (!(rawItem instanceof JSONObject itemJson)
                    || !itemJson.containsKey(CONFIG_KEY_MEMBER_USER_ID)
                    || !itemJson.containsKey(CONFIG_KEY_WORK_ID)) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
            TeamCarouselComponentConfig.Item convertedItem = convertedConfig.getItems().get(index);
            if (convertedItem == null) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
            TeamCarouselComponentConfig.Item item = new TeamCarouselComponentConfig.Item();
            item.setMemberUserId(parsePositiveLong(itemJson.get(CONFIG_KEY_MEMBER_USER_ID)));
            item.setWorkId(parsePositiveLong(itemJson.get(CONFIG_KEY_WORK_ID)));
            items.add(item);
        }
        return items;
    }

    /**
     * 解析正整数 Long。
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
     * 批量加载满足团队授权的成员关系。
     *
     * @param teamId 团队 ID
     * @param memberUserIds 成员用户 ID 集合
     * @return 用户 ID 到成员关系的映射
     */
    private Map<Long, TeamMemberEntity> membershipsByUserId(long teamId, Set<Long> memberUserIds) {
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
     * 批量加载正常用户。
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
     * 批量加载作品。
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

    /**
     * 校验成员、用户和作品是否仍能组成配置中的原始配对。
     *
     * @param item 配置条目
     * @param membership 成员关系
     * @param user 用户记录
     * @param work 作品记录
     */
    private void validatePair(
            TeamCarouselComponentConfig.Item item,
            TeamMemberEntity membership,
            UserEntity user,
            WorkEntity work
    ) {
        if (membership == null || user == null || work == null
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

    /**
     * 构建单个展示快照。
     *
     * @param item 配置条目
     * @param membership 成员关系
     * @param user 用户记录
     * @param work 作品记录
     * @return 展示快照
     */
    private JSONObject renderItem(
            TeamCarouselComponentConfig.Item item,
            TeamMemberEntity membership,
            UserEntity user,
            WorkEntity work
    ) {
        String mediaUrl = publicUrl(work.getMediaObjectKey());
        String coverUrl = hasText(work.getCoverObjectKey()) ? publicUrl(work.getCoverObjectKey()) : mediaUrl;
        JSONObject rendered = new JSONObject();
        rendered.put(CONFIG_KEY_MEMBER_USER_ID, item.getMemberUserId());
        rendered.put(RENDER_KEY_MEMBER_DISPLAY_NAME, user.getNickname());
        rendered.put(RENDER_KEY_MEMBER_AVATAR_URL, user.getAvatarUrl());
        rendered.put(CONFIG_KEY_WORK_ID, item.getWorkId());
        rendered.put(RENDER_KEY_TITLE, work.getTitle());
        rendered.put(RENDER_KEY_COVER_URL, coverUrl);
        rendered.put(RENDER_KEY_MEDIA_URL, mediaUrl);
        rendered.put(RENDER_KEY_WIDTH, work.getWidth());
        rendered.put(RENDER_KEY_HEIGHT, work.getHeight());
        rendered.put(RENDER_KEY_ASPECT_RATIO, work.getAspectRatio());
        return rendered;
    }

    /**
     * 仅为非空对象键构建公开地址。
     *
     * @param objectKey COS 对象键
     * @return 公开地址或空值
     */
    private String publicUrl(String objectKey) {
        return hasText(objectKey) ? cosService.publicUrl(objectKey) : null;
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value 文本
     * @return 是否非空白
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
