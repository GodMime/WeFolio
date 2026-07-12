package com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist;

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
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 单列成员作品集组件的数据来源服务。
 */
@Service
@RequiredArgsConstructor
public class TeamMemberPortfolioListComponentService {

    /** 标准个人作品集 Schema 版本。 */
    private static final String STANDARD_PERSONAL_SCHEMA_VERSION = "standard-personal-v1";

    /** 单条成员查询限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 已发布配置非空白 SQL 条件。 */
    private static final String NONBLANK_PUBLISHED_CONFIG_SQL = "TRIM(published_config_json) <> {0}";

    /** 分享编码非空白 SQL 条件。 */
    private static final String NONBLANK_SHARE_CODE_SQL = "TRIM(share_code) <> {0}";

    /** 空字符串查询参数。 */
    private static final String EMPTY_STRING = "";

    /** 分享信息字段。 */
    private static final String SHARE_FIELD = "share";

    /** 分享标题字段。 */
    private static final String TITLE_FIELD = "title";

    /** 分享封面字段。 */
    private static final String COVER_URL_FIELD = "coverUrl";

    /** 成员不可用提示。 */
    private static final String UNAVAILABLE_MEMBER_MESSAGE = "团队成员不存在或不可用";

    /** 团队作品集访问控制服务。 */
    private final TeamPortfolioAccessService teamPortfolioAccessService;

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 作品集 Mapper。 */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /**
     * 获取可供选择的活跃成员。
     *
     * @param portfolioId 团队作品集 ID
     * @param userId 当前用户 ID
     * @return 按成员关系 ID 排序的成员选项
     */
    public List<MemberOption> listMembers(long portfolioId, long userId) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                teamPortfolioAccessService.requireMaintainablePortfolio(portfolioId, userId);
        List<TeamMemberEntity> memberships = safeList(teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class).eq(TeamMemberEntity::getTeamId, access.team().getId())
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowPortfolio, 1).orderByAsc(TeamMemberEntity::getId)));
        if (memberships.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> memberUserIds = memberships.stream()
                .filter(membership -> isAvailableMembership(membership, access.team().getId()))
                .map(TeamMemberEntity::getUserId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (memberUserIds.isEmpty()) {
            return List.of();
        }
        Map<Long, UserEntity> users = new LinkedHashMap<>();
        for (UserEntity user : safeList(userEntityMapper.selectBatchIds(memberUserIds))) {
            if (user != null && user.getId() != null && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                users.putIfAbsent(user.getId(), user);
            }
        }
        List<MemberOption> result = new ArrayList<>();
        for (TeamMemberEntity membership : memberships) {
            UserEntity user = membership == null ? null : users.get(membership.getUserId());
            if (isAvailableMembership(membership, access.team().getId()) && user != null) {
                result.add(new MemberOption(user.getId(), user.getNickname(), user.getAvatarUrl(), membership.getProfession()));
            }
        }
        return result;
    }

    /**
     * 获取指定成员可选择的已发布个人作品集。
     *
     * @param portfolioId 团队作品集 ID
     * @param memberUserId 成员用户 ID
     * @param userId 当前用户 ID
     * @return 按发布时间和 ID 倒序的作品集选项
     */
    public List<PortfolioOption> listPortfolios(long portfolioId, long memberUserId, long userId) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                teamPortfolioAccessService.requireMaintainablePortfolio(portfolioId, userId);
        TeamMemberEntity membership = teamMemberEntityMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class).eq(TeamMemberEntity::getTeamId, access.team().getId())
                        .eq(TeamMemberEntity::getUserId, memberUserId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowPortfolio, 1).last(QUERY_LIMIT_ONE));
        if (!isAvailableMembership(membership, access.team().getId()) || membership.getUserId() != memberUserId) {
            throw new BusinessException(UNAVAILABLE_MEMBER_MESSAGE);
        }
        UserEntity user = userEntityMapper.selectById(memberUserId);
        if (user == null || user.getId() == null || user.getId() != memberUserId
                || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(UNAVAILABLE_MEMBER_MESSAGE);
        }
        List<PortfolioOption> result = new ArrayList<>();
        for (PortfolioEntity portfolio : safeList(portfolioEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioEntity.class).eq(PortfolioEntity::getOwnerId, memberUserId)
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode())
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getSchemaVersion, STANDARD_PERSONAL_SCHEMA_VERSION)
                        .eq(PortfolioEntity::getPublicationStatus, PortfolioPublicationStatusDict.PUBLISHED.getCode())
                        .isNotNull(PortfolioEntity::getPublishedConfigJson).apply(NONBLANK_PUBLISHED_CONFIG_SQL, EMPTY_STRING)
                        .isNotNull(PortfolioEntity::getShareCode).apply(NONBLANK_SHARE_CODE_SQL, EMPTY_STRING)
                        .orderByDesc(PortfolioEntity::getPublishedAt).orderByDesc(PortfolioEntity::getId)))) {
            ShareMetadata metadata = metadata(portfolio);
            if (isAvailablePortfolio(portfolio, memberUserId) && metadata != null) {
                result.add(new PortfolioOption(portfolio.getId(), metadata.title(), metadata.coverUrl(),
                        portfolio.getShareCode(), portfolio.getPublishedRevision()));
            }
        }
        return result;
    }

    /** 判断成员关系是否可用。 */
    private boolean isAvailableMembership(TeamMemberEntity membership, long teamId) {
        return membership != null && membership.getTeamId() != null && membership.getTeamId() == teamId
                && membership.getUserId() != null
                && JoinStatusDict.JOINED.getCode().equals(membership.getJoinStatus())
                && Integer.valueOf(1).equals(membership.getAllowPortfolio());
    }

    /** 判断个人作品集是否仍满足选择条件。 */
    private boolean isAvailablePortfolio(PortfolioEntity portfolio, long memberUserId) {
        return portfolio != null && portfolio.getId() != null && portfolio.getOwnerId() != null
                && portfolio.getOwnerId() == memberUserId
                && PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                && PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                && PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                && STANDARD_PERSONAL_SCHEMA_VERSION.equals(portfolio.getSchemaVersion())
                && PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                && hasText(portfolio.getPublishedConfigJson()) && hasText(portfolio.getShareCode());
    }

    /** 从发布 JSON 安全提取分享信息。 */
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

    /** 可选择成员。 */
    public record MemberOption(Long memberUserId, String displayName, String avatarUrl, String profession) {
    }

    /** 可选择个人作品集。 */
    public record PortfolioOption(Long portfolioId, String title, String coverUrl, String shareCode,
                                  Integer publishedRevision) {
    }

    /** 分享元数据。 */
    private record ShareMetadata(String title, String coverUrl) {
    }
}
