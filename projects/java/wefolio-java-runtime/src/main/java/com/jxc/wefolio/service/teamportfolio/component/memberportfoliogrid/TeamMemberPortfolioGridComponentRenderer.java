package com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 双列成员作品集组件渲染器。
 */
@RequiredArgsConstructor
@Component
public class TeamMemberPortfolioGridComponentRenderer {

    /** 标准个人作品集 Schema 版本。 */
    private static final String STANDARD_PERSONAL_SCHEMA_VERSION = "standard-personal-v1";

    /** 配置条目字段。 */
    private static final String ITEMS_FIELD = "items";

    /** 成员用户 ID 字段。 */
    private static final String MEMBER_USER_ID_FIELD = "memberUserId";

    /** 个人作品集 ID 字段。 */
    private static final String PORTFOLIO_ID_FIELD = "portfolioId";

    /** 成员姓名展示开关字段。 */
    private static final String SHOW_MEMBER_NAME_FIELD = "showMemberName";

    /** 分享信息字段。 */
    private static final String SHARE_FIELD = "share";

    /** 分享标题字段。 */
    private static final String TITLE_FIELD = "title";

    /** 分享封面字段。 */
    private static final String COVER_URL_FIELD = "coverUrl";

    /** 成员展示名字段。 */
    private static final String MEMBER_DISPLAY_NAME_FIELD = "memberDisplayName";

    /** 成员头像字段。 */
    private static final String MEMBER_AVATAR_URL_FIELD = "memberAvatarUrl";

    /** 分享编码字段。 */
    private static final String SHARE_CODE_FIELD = "shareCode";

    /** 发布版本字段。 */
    private static final String PUBLISHED_REVISION_FIELD = "publishedRevision";

    /** 非法配置提示。 */
    private static final String INVALID_CONFIG_MESSAGE = "双列作品集配置不正确";

    /** 空配置提示。 */
    private static final String EMPTY_CONFIG_MESSAGE = "双列作品集至少选择一个作品集";

    /** 重复个人作品集提示。 */
    private static final String DUPLICATE_PORTFOLIO_MESSAGE = "双列作品集不能重复选择同一作品集";

    /** 成员作品集不可用提示。 */
    private static final String UNAVAILABLE_PORTFOLIO_MESSAGE = "成员作品集不存在或未发布";

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 作品集 Mapper。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /**
     * 重建双列成员作品集的渲染数据。
     *
     * @param normalizedConfig 已规范化的组件配置
     * @param context 团队作品集上下文
     * @return 脱离输入对象的渲染数据
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        RenderConfig renderConfig = parseConfig(normalizedConfig);
        List<Pair> items = renderConfig.items();
        if (context == null) {
            throw new BusinessException(INVALID_CONFIG_MESSAGE);
        }
        Set<Long> memberUserIds = new LinkedHashSet<>();
        Set<Long> portfolioIds = new LinkedHashSet<>();
        for (Pair item : items) {
            memberUserIds.add(item.memberUserId());
            if (!portfolioIds.add(item.portfolioId())) {
                throw new BusinessException(DUPLICATE_PORTFOLIO_MESSAGE);
            }
        }

        Map<Long, TeamMemberEntity> memberships = memberships(context.teamId(), memberUserIds);
        for (Long memberUserId : memberUserIds) {
            if (!memberships.containsKey(memberUserId)) {
                throw new BusinessException(UNAVAILABLE_PORTFOLIO_MESSAGE);
            }
        }
        Map<Long, UserEntity> users = activeUsers(memberUserIds);
        for (Long memberUserId : memberUserIds) {
            if (!users.containsKey(memberUserId)) {
                throw new BusinessException(UNAVAILABLE_PORTFOLIO_MESSAGE);
            }
        }
        Map<Long, PortfolioEntity> portfolios = portfolios(portfolioIds);
        JSONArray renderedItems = new JSONArray();
        for (Pair item : items) {
            PortfolioEntity portfolio = portfolios.get(item.portfolioId());
            ShareMetadata share = metadata(portfolio);
            if (!isAvailablePortfolio(portfolio, item.memberUserId()) || share == null) {
                throw new BusinessException(UNAVAILABLE_PORTFOLIO_MESSAGE);
            }
            UserEntity user = users.get(item.memberUserId());
            JSONObject renderedItem = new JSONObject();
            renderedItem.put(MEMBER_USER_ID_FIELD, item.memberUserId());
            renderedItem.put(MEMBER_DISPLAY_NAME_FIELD, user.getNickname());
            renderedItem.put(MEMBER_AVATAR_URL_FIELD, user.getAvatarUrl());
            renderedItem.put(PORTFOLIO_ID_FIELD, item.portfolioId());
            renderedItem.put(TITLE_FIELD, share.title());
            renderedItem.put(COVER_URL_FIELD, share.coverUrl());
            renderedItem.put(SHARE_CODE_FIELD, portfolio.getShareCode());
            renderedItem.put(PUBLISHED_REVISION_FIELD, portfolio.getPublishedRevision());
            renderedItems.add(renderedItem);
        }
        JSONObject result = new JSONObject();
        result.put(SHOW_MEMBER_NAME_FIELD, renderConfig.showMemberName());
        result.put(ITEMS_FIELD, renderedItems);
        return result;
    }

    /**
     * 在所有 Mapper 查询前严格解析配置。
     *
     * @param config 原始配置
     * @return 展示开关、成员和作品集 ID 对
     */
    private RenderConfig parseConfig(JSONObject config) {
        if (config == null || !(config.get(ITEMS_FIELD) instanceof JSONArray rawItems)) {
            throw new BusinessException(INVALID_CONFIG_MESSAGE);
        }
        if (rawItems.isEmpty()) {
            throw new BusinessException(EMPTY_CONFIG_MESSAGE);
        }
        List<Pair> items = new java.util.ArrayList<>();
        Set<Long> portfolioIds = new LinkedHashSet<>();
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof JSONObject item)) {
                throw new BusinessException(INVALID_CONFIG_MESSAGE);
            }
            Long memberUserId = positiveLong(item.get(MEMBER_USER_ID_FIELD));
            Long portfolioId = positiveLong(item.get(PORTFOLIO_ID_FIELD));
            if (memberUserId == null || portfolioId == null) {
                throw new BusinessException(INVALID_CONFIG_MESSAGE);
            }
            if (!portfolioIds.add(portfolioId)) {
                throw new BusinessException(DUPLICATE_PORTFOLIO_MESSAGE);
            }
            items.add(new Pair(memberUserId, portfolioId));
        }
        return new RenderConfig(items, showMemberName(config));
    }

    /** 解析成员姓名展示开关，兼容历史缺省配置。 */
    private boolean showMemberName(JSONObject config) {
        if (!config.containsKey(SHOW_MEMBER_NAME_FIELD)) {
            return true;
        }
        Object rawValue = config.get(SHOW_MEMBER_NAME_FIELD);
        if (!(rawValue instanceof Boolean value)) {
            throw new BusinessException(INVALID_CONFIG_MESSAGE);
        }
        return value;
    }

    /** 将无损正整数原值转为 Long。 */
    private Long positiveLong(Object value) {
        try {
            long result;
            if (value instanceof BigDecimal decimal) {
                result = decimal.longValueExact();
            } else if (value instanceof BigInteger integer) {
                result = integer.longValueExact();
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                result = ((Number) value).longValue();
            } else {
                return null;
            }
            return result > 0 ? result : null;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    /** 批量查询可引用成员关系。 */
    private Map<Long, TeamMemberEntity> memberships(long teamId, Set<Long> memberUserIds) {
        Map<Long, TeamMemberEntity> result = new LinkedHashMap<>();
        for (TeamMemberEntity entity : safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class).eq(TeamMemberEntity::getTeamId, teamId)
                        .in(TeamMemberEntity::getUserId, memberUserIds)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowPortfolio, 1)))) {
            if (entity != null && entity.getUserId() != null && entity.getTeamId() != null && entity.getTeamId() == teamId
                    && JoinStatusDict.JOINED.getCode().equals(entity.getJoinStatus())
                    && Integer.valueOf(1).equals(entity.getAllowPortfolio())) {
                result.putIfAbsent(entity.getUserId(), entity);
            }
        }
        return result;
    }

    /** 批量查询活跃用户。 */
    private Map<Long, UserEntity> activeUsers(Set<Long> memberUserIds) {
        Map<Long, UserEntity> result = new LinkedHashMap<>();
        for (UserEntity entity : safeList(userEntityMapper.selectBatchIds(memberUserIds))) {
            if (entity != null && entity.getId() != null && UserStatusDict.ACTIVE.getCode().equals(entity.getStatus())) {
                result.putIfAbsent(entity.getId(), entity);
            }
        }
        return result;
    }

    /** 批量查询个人作品集。 */
    private Map<Long, PortfolioEntity> portfolios(Set<Long> portfolioIds) {
        Map<Long, PortfolioEntity> result = new LinkedHashMap<>();
        for (PortfolioEntity entity : safeList(portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class).in(PortfolioEntity::getId, portfolioIds)))) {
            if (entity != null && entity.getId() != null) {
                result.putIfAbsent(entity.getId(), entity);
            }
        }
        return result;
    }

    /** 判断作品集是否可渲染。 */
    private boolean isAvailablePortfolio(PortfolioEntity portfolio, long memberUserId) {
        return portfolio != null && portfolio.getOwnerId() != null && portfolio.getOwnerId() == memberUserId
                && PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && STANDARD_PERSONAL_SCHEMA_VERSION.equals(portfolio.getSchemaVersion())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && hasText(portfolio.getPublishedConfigJson()) && hasText(portfolio.getShareCode());
    }

    /** 从发布 JSON 提取分享元数据。 */
    private ShareMetadata metadata(PortfolioEntity portfolio) {
        if (portfolio == null || !hasText(portfolio.getPublishedConfigJson())) {
            return null;
        }
        try {
            JSONObject root = JSONObject.parseObject(portfolio.getPublishedConfigJson());
            Object rawShare = root == null ? null : root.get(SHARE_FIELD);
            if (!(rawShare instanceof JSONObject share)) {
                return null;
            }
            String title = share.getString(TITLE_FIELD);
            return hasText(title) ? new ShareMetadata(title, share.getString(COVER_URL_FIELD)) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 将 Mapper 空结果转换为安全空集合。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    /** 判断字符串是否包含非空白内容。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 成员用户和作品集 ID 对。 */
    private record Pair(long memberUserId, long portfolioId) {
    }

    /** 双列组件的完整渲染配置。 */
    private record RenderConfig(List<Pair> items, boolean showMemberName) {
    }

    /** 分享元数据。 */
    private record ShareMetadata(String title, String coverUrl) {
    }
}
