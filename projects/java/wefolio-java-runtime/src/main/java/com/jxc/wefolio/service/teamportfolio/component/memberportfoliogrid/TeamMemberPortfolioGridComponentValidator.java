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
 * 双列成员作品集组件配置校验器。
 */
@RequiredArgsConstructor
@Component
public class TeamMemberPortfolioGridComponentValidator {

    /** 标准个人作品集 Schema 版本。 */
    private static final String STANDARD_PERSONAL_SCHEMA_VERSION = "standard-personal-v1";

    /** 配置条目字段。 */
    private static final String ITEMS_FIELD = "items";

    /** 成员用户 ID 字段。 */
    private static final String MEMBER_USER_ID_FIELD = "memberUserId";

    /** 个人作品集 ID 字段。 */
    private static final String PORTFOLIO_ID_FIELD = "portfolioId";

    /** 发布配置分享信息字段。 */
    private static final String SHARE_FIELD = "share";

    /** 分享标题字段。 */
    private static final String TITLE_FIELD = "title";

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
     * 严格规范化并校验双列作品集配置。
     *
     * @param config 原始组件配置
     * @param context 团队作品集上下文
     * @return 只包含 ID 的规范化配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamMemberPortfolioGridComponentConfig parsedConfig = parseConfig(config);
        List<TeamMemberPortfolioGridComponentConfig.Item> items = parsedConfig.getItems();
        Set<Long> memberUserIds = new LinkedHashSet<>();
        Set<Long> portfolioIds = new LinkedHashSet<>();
        for (TeamMemberPortfolioGridComponentConfig.Item item : items) {
            memberUserIds.add(item.getMemberUserId());
            if (!portfolioIds.add(item.getPortfolioId())) {
                throw new BusinessException(DUPLICATE_PORTFOLIO_MESSAGE);
            }
        }

        Map<Long, TeamMemberEntity> memberships = memberships(context, memberUserIds);
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
        for (TeamMemberPortfolioGridComponentConfig.Item item : items) {
            PortfolioEntity portfolio = portfolios.get(item.getPortfolioId());
            if (!isAvailablePortfolio(portfolio, item.getMemberUserId()) || shareTitle(portfolio) == null) {
                throw new BusinessException(UNAVAILABLE_PORTFOLIO_MESSAGE);
            }
        }
        return normalized(items);
    }

    /**
     * 解析原始配置并使用配置 Bean 承接结构。
     *
     * @param config 原始配置
     * @return 已完成严格 ID 校验的配置对象
     */
    private TeamMemberPortfolioGridComponentConfig parseConfig(JSONObject config) {
        if (config == null || !(config.get(ITEMS_FIELD) instanceof JSONArray rawItems)) {
            throw new BusinessException(INVALID_CONFIG_MESSAGE);
        }
        if (rawItems.isEmpty()) {
            throw new BusinessException(EMPTY_CONFIG_MESSAGE);
        }
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof JSONObject item)
                    || positiveLong(item.get(MEMBER_USER_ID_FIELD)) == null
                    || positiveLong(item.get(PORTFOLIO_ID_FIELD)) == null) {
                throw new BusinessException(INVALID_CONFIG_MESSAGE);
            }
        }
        TeamMemberPortfolioGridComponentConfig parsedConfig =
                config.toJavaObject(TeamMemberPortfolioGridComponentConfig.class);
        if (parsedConfig == null || parsedConfig.getItems() == null || parsedConfig.getItems().size() != rawItems.size()) {
            throw new BusinessException(INVALID_CONFIG_MESSAGE);
        }
        for (int index = 0; index < rawItems.size(); index++) {
            TeamMemberPortfolioGridComponentConfig.Item item = parsedConfig.getItems().get(index);
            JSONObject rawItem = rawItems.getJSONObject(index);
            if (item == null) {
                throw new BusinessException(INVALID_CONFIG_MESSAGE);
            }
            item.setMemberUserId(positiveLong(rawItem.get(MEMBER_USER_ID_FIELD)));
            item.setPortfolioId(positiveLong(rawItem.get(PORTFOLIO_ID_FIELD)));
        }
        return parsedConfig;
    }

    /**
     * 将无损正整数原值转为 Long。
     *
     * @param value JSON 原始值
     * @return 正整数，非法时为 null
     */
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

    /**
     * 批量查询有效的成员授权关系。
     *
     * @param context 团队作品集上下文
     * @param memberUserIds 成员用户 ID
     * @return 按用户 ID 索引的成员关系
     */
    private Map<Long, TeamMemberEntity> memberships(TeamPortfolioComponentContext context, Set<Long> memberUserIds) {
        if (context == null) {
            throw new BusinessException(INVALID_CONFIG_MESSAGE);
        }
        List<TeamMemberEntity> entities = safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, context.teamId())
                        .in(TeamMemberEntity::getUserId, memberUserIds)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowPortfolio, 1)));
        Map<Long, TeamMemberEntity> result = new LinkedHashMap<>();
        for (TeamMemberEntity entity : entities) {
            if (isAvailableMembership(entity, context.teamId()) && entity.getUserId() != null) {
                result.putIfAbsent(entity.getUserId(), entity);
            }
        }
        return result;
    }

    /**
     * 批量查询活跃用户。
     *
     * @param memberUserIds 成员用户 ID
     * @return 按用户 ID 索引的活跃用户
     */
    private Map<Long, UserEntity> activeUsers(Set<Long> memberUserIds) {
        Map<Long, UserEntity> result = new LinkedHashMap<>();
        for (UserEntity entity : safeList(userEntityMapper.selectBatchIds(memberUserIds))) {
            if (entity != null && entity.getId() != null && UserStatusDict.ACTIVE.getCode().equals(entity.getStatus())) {
                result.putIfAbsent(entity.getId(), entity);
            }
        }
        return result;
    }

    /**
     * 批量查询个人作品集。
     *
     * @param portfolioIds 个人作品集 ID
     * @return 按作品集 ID 索引的实体
     */
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

    /**
     * 判断成员关系是否仍满足授权条件。
     *
     * @param membership 成员关系
     * @param teamId 团队 ID
     * @return 是否可引用个人作品集
     */
    private boolean isAvailableMembership(TeamMemberEntity membership, long teamId) {
        return membership != null && membership.getTeamId() != null && membership.getTeamId() == teamId
                && JoinStatusDict.JOINED.getCode().equals(membership.getJoinStatus())
                && Integer.valueOf(1).equals(membership.getAllowPortfolio());
    }

    /**
     * 判断作品集是否满足成员个人已发布作品集条件。
     *
     * @param portfolio 作品集实体
     * @param memberUserId 对应成员用户 ID
     * @return 是否可引用
     */
    private boolean isAvailablePortfolio(PortfolioEntity portfolio, long memberUserId) {
        return portfolio != null && portfolio.getOwnerId() != null && portfolio.getOwnerId() == memberUserId
                && PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && STANDARD_PERSONAL_SCHEMA_VERSION.equals(portfolio.getSchemaVersion())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && hasText(portfolio.getPublishedConfigJson()) && hasText(portfolio.getShareCode());
    }

    /**
     * 从正式配置提取非空分享标题。
     *
     * @param portfolio 作品集实体
     * @return 分享标题，解析失败时为 null
     */
    private String shareTitle(PortfolioEntity portfolio) {
        try {
            JSONObject root = JSONObject.parseObject(portfolio.getPublishedConfigJson());
            Object rawShare = root == null ? null : root.get(SHARE_FIELD);
            if (!(rawShare instanceof JSONObject share)) {
                return null;
            }
            String title = share.getString(TITLE_FIELD);
            return hasText(title) ? title : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 组装只含 ID 的规范化配置。
     *
     * @param items 配置条目
     * @return 规范化 JSON
     */
    private JSONObject normalized(List<TeamMemberPortfolioGridComponentConfig.Item> items) {
        JSONArray normalizedItems = new JSONArray();
        for (TeamMemberPortfolioGridComponentConfig.Item item : items) {
            JSONObject normalizedItem = new JSONObject();
            normalizedItem.put(MEMBER_USER_ID_FIELD, item.getMemberUserId());
            normalizedItem.put(PORTFOLIO_ID_FIELD, item.getPortfolioId());
            normalizedItems.add(normalizedItem);
        }
        JSONObject result = new JSONObject();
        result.put(ITEMS_FIELD, normalizedItems);
        return result;
    }

    /**
     * 将 Mapper 空结果转换为安全空集合。
     *
     * @param values Mapper 返回值
     * @param <T> 元素类型
     * @return 非空集合
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    /**
     * 判断字符串是否包含非空白内容。
     *
     * @param value 字符串
     * @return 是否非空白
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
