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
 * 团队视频轮播宽容渲染器。
 */
@Component
@RequiredArgsConstructor
public class TeamVideoCarouselComponentRenderer {

    /** 默认标题 */
    private static final String DEFAULT_TITLE = "视频作品";

    /** 最少配置条目数 */
    private static final int ITEM_MIN_COUNT = 3;

    /** 最多配置条目数 */
    private static final int ITEM_MAX_COUNT = 8;

    /** 允许作品引用标识 */
    private static final int ALLOW_WORKS = 1;

    private static final String CONFIG_KEY_TITLE = "title";
    private static final String CONFIG_KEY_ITEMS = "items";
    private static final String CONFIG_KEY_MEMBER_USER_ID = "memberUserId";
    private static final String CONFIG_KEY_WORK_ID = "workId";
    private static final String CONFIG_KEY_SHOW_TITLE = "showTitle";
    private static final String CONFIG_KEY_SHOW_SWIPE_HINT = "showSwipeHint";
    private static final String RENDER_KEY_WORKS = "works";
    private static final String RENDER_KEY_MEMBER_DISPLAY_NAME = "memberDisplayName";
    private static final String RENDER_KEY_MEMBER_AVATAR_URL = "memberAvatarUrl";
    private static final String RENDER_KEY_MEDIA_TYPE = "mediaType";
    private static final String RENDER_KEY_COVER_URL = "coverUrl";
    private static final String RENDER_KEY_MEDIA_URL = "mediaUrl";
    private static final String RENDER_KEY_DURATION_MS = "durationMs";
    private static final String RENDER_KEY_WIDTH = "width";
    private static final String RENDER_KEY_HEIGHT = "height";
    private static final String RENDER_KEY_ASPECT_RATIO = "aspectRatio";
    private static final String RENDER_KEY_DESCRIPTION = "description";

    /** 团队成员 Mapper */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** COS 服务 */
    private final CosService cosService;

    /**
     * 按当前资源状态渲染视频轮播，逐项跳过已失效资源。
     *
     * @param normalizedConfig 规范化配置
     * @param context 团队作品集上下文
     * @return 渲染数据；无任何可用条目时返回 null
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        TeamVideoCarouselComponentConfig config = parseConfig(normalizedConfig);
        if (context == null || context.teamId() <= 0) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
        }
        Set<Long> memberUserIds = config.getItems().stream()
                .map(TeamVideoCarouselComponentConfig.Item::getMemberUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> workIds = config.getItems().stream()
                .map(TeamVideoCarouselComponentConfig.Item::getWorkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TeamMemberEntity> memberships = membershipsByUserId(context.teamId(), memberUserIds);
        Map<Long, UserEntity> users = activeUsersById(memberUserIds);
        Map<Long, WorkEntity> works = worksById(workIds);

        JSONArray renderedItems = new JSONArray();
        for (TeamVideoCarouselComponentConfig.Item item : config.getItems()) {
            UserEntity user = users.get(item.getMemberUserId());
            WorkEntity work = works.get(item.getWorkId());
            if (!isRenderable(item, memberships.get(item.getMemberUserId()), user, work)) {
                continue;
            }
            renderedItems.add(renderItem(item, user, work));
        }
        if (renderedItems.isEmpty()) {
            return null;
        }
        JSONObject rendered = new JSONObject();
        rendered.put(CONFIG_KEY_TITLE, config.getTitle());
        rendered.put(CONFIG_KEY_ITEMS, renderedItems);
        rendered.put(RENDER_KEY_WORKS, renderedItems);
        rendered.put(CONFIG_KEY_SHOW_TITLE, config.isShowTitle());
        rendered.put(CONFIG_KEY_SHOW_SWIPE_HINT, config.isShowSwipeHint());
        return rendered;
    }

    /** 严格解析已持久化配置，资源可用性由渲染阶段宽容处理。 */
    private TeamVideoCarouselComponentConfig parseConfig(JSONObject rawConfig) {
        if (rawConfig == null || !(rawConfig.get(CONFIG_KEY_ITEMS) instanceof JSONArray rawItems)
                || rawItems.size() < ITEM_MIN_COUNT || rawItems.size() > ITEM_MAX_COUNT) {
            throw new BusinessException(TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID);
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
        String title = rawConfig.getString(CONFIG_KEY_TITLE);
        title = title == null ? "" : title.strip();
        TeamVideoCarouselComponentConfig config = new TeamVideoCarouselComponentConfig();
        config.setTitle(title.isEmpty() ? DEFAULT_TITLE : title);
        config.setItems(items);
        config.setShowTitle(booleanValue(rawConfig.get(CONFIG_KEY_SHOW_TITLE), true));
        config.setShowSwipeHint(booleanValue(rawConfig.get(CONFIG_KEY_SHOW_SWIPE_HINT), true));
        return config;
    }

    /** 判断当前条目是否仍可渲染。 */
    private boolean isRenderable(
            TeamVideoCarouselComponentConfig.Item item,
            TeamMemberEntity membership,
            UserEntity user,
            WorkEntity work
    ) {
        return membership != null
                && user != null
                && work != null
                && item.getMemberUserId().equals(work.getUserId())
                && WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                && WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                && MediaTypeDict.VIDEO.getCode().equals(work.getMediaType());
    }

    /** 生成单个当前展示快照。 */
    private JSONObject renderItem(
            TeamVideoCarouselComponentConfig.Item item,
            UserEntity user,
            WorkEntity work
    ) {
        String mediaUrl = publicUrl(work.getMediaObjectKey());
        String coverUrl = hasText(work.getCoverObjectKey()) ? publicUrl(work.getCoverObjectKey()) : mediaUrl;
        JSONObject rendered = new JSONObject();
        rendered.put(CONFIG_KEY_MEMBER_USER_ID, item.getMemberUserId());
        rendered.put(RENDER_KEY_MEMBER_DISPLAY_NAME, defaultString(user.getNickname()));
        rendered.put(RENDER_KEY_MEMBER_AVATAR_URL, defaultString(user.getAvatarUrl()));
        rendered.put(CONFIG_KEY_WORK_ID, item.getWorkId());
        rendered.put(CONFIG_KEY_TITLE, work.getTitle());
        rendered.put(RENDER_KEY_MEDIA_TYPE, work.getMediaType());
        rendered.put(RENDER_KEY_COVER_URL, coverUrl);
        rendered.put(RENDER_KEY_MEDIA_URL, mediaUrl);
        rendered.put(RENDER_KEY_DURATION_MS, work.getDurationMs());
        rendered.put(RENDER_KEY_WIDTH, work.getWidth());
        rendered.put(RENDER_KEY_HEIGHT, work.getHeight());
        rendered.put(RENDER_KEY_ASPECT_RATIO, work.getAspectRatio());
        rendered.put(RENDER_KEY_DESCRIPTION, work.getDescription());
        return rendered;
    }

    /** 批量读取符合授权条件的成员。 */
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

    /** 批量读取正常用户。 */
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
        Map<Long, WorkEntity> result = new LinkedHashMap<>();
        List<WorkEntity> rows = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class).in(WorkEntity::getId, workIds));
        for (WorkEntity work : safeList(rows)) {
            if (work != null && work.getId() != null) {
                result.put(work.getId(), work);
            }
        }
        return result;
    }

    /** 仅对非空对象键生成公开地址，不校验 COS 对象是否存在。 */
    private String publicUrl(String objectKey) {
        return hasText(objectKey) ? cosService.publicUrl(objectKey) : null;
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

    private boolean booleanValue(Object value, boolean defaultValue) {
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 空字符串兜底。 */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
