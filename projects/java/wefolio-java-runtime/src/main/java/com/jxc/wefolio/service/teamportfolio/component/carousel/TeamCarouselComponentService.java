package com.jxc.wefolio.service.teamportfolio.component.carousel;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队轮播图成员和作品来源服务。
 */
@Service
@RequiredArgsConstructor
public class TeamCarouselComponentService {

    /** 允许作品授权标识。 */
    private static final int ALLOW_WORKS = 1;

    /** 单条成员关系查询限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 成员不可用提示。 */
    private static final String MEMBER_UNAVAILABLE_MESSAGE = "团队成员不存在或不可用";

    /** 团队作品集访问服务。 */
    private final TeamPortfolioAccessService teamPortfolioAccessService;

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper。 */
    private final WorkEntityMapper workEntityMapper;

    /** COS 服务。 */
    private final CosService cosService;

    /**
     * 列出可用于轮播图的活跃团队成员。
     *
     * @param portfolioId 团队作品集 ID
     * @param userId 当前维护者用户 ID
     * @return 按成员关系排序的成员选项
     */
    public List<MemberOption> listMembers(long portfolioId, long userId) {
        TeamEntity team = teamPortfolioAccessService.requireMaintainablePortfolio(portfolioId, userId).team();
        List<TeamMemberEntity> memberships = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, team.getId())
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
                        .orderByAsc(TeamMemberEntity::getId)
        );
        Set<Long> memberUserIds = (memberships == null ? List.<TeamMemberEntity>of() : memberships).stream()
                .map(TeamMemberEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<Long, UserEntity> activeUsers = activeUsersById(memberUserIds);
        return (memberships == null ? List.<TeamMemberEntity>of() : memberships).stream()
                .filter(membership -> membership != null && membership.getUserId() != null)
                .filter(membership -> activeUsers.containsKey(membership.getUserId()))
                .map(membership -> new MemberOption(membership, activeUsers.get(membership.getUserId())))
                .toList();
    }

    /**
     * 列出指定成员可用于轮播图的图片作品。
     *
     * @param portfolioId 团队作品集 ID
     * @param memberUserId 成员用户 ID
     * @param userId 当前维护者用户 ID
     * @return 按作品排序的作品选项
     */
    public List<WorkOption> listWorks(long portfolioId, long memberUserId, long userId) {
        TeamEntity team = teamPortfolioAccessService.requireMaintainablePortfolio(portfolioId, userId).team();
        TeamMemberEntity membership = teamMemberEntityMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, team.getId())
                        .eq(TeamMemberEntity::getUserId, memberUserId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
                        .last(QUERY_LIMIT_ONE)
        );
        if (membership == null) {
            throw new BusinessException(MEMBER_UNAVAILABLE_MESSAGE);
        }
        UserEntity member = userEntityMapper.selectById(memberUserId);
        if (member == null || !UserStatusDict.ACTIVE.getCode().equals(member.getStatus())) {
            throw new BusinessException(MEMBER_UNAVAILABLE_MESSAGE);
        }
        List<WorkEntity> works = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, memberUserId)
                        .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                        .eq(WorkEntity::getAuditStatus, WorkAuditStatusDict.PASSED.getCode())
                        .eq(WorkEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                        .orderByAsc(WorkEntity::getSortOrder)
                        .orderByAsc(WorkEntity::getId)
        );
        return (works == null ? List.<WorkEntity>of() : works).stream().map(this::toWorkOption).toList();
    }

    /**
     * 批量筛选正常用户。
     *
     * @param userIds 用户 ID 集合
     * @return 用户 ID 到正常用户的映射
     */
    private Map<Long, UserEntity> activeUsersById(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserEntity> users = userEntityMapper.selectBatchIds(userIds);
        Map<Long, UserEntity> activeUsers = new LinkedHashMap<>();
        for (UserEntity user : users == null ? List.<UserEntity>of() : users) {
            if (user != null && user.getId() != null && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                activeUsers.put(user.getId(), user);
            }
        }
        return activeUsers;
    }

    /**
     * 构造作品公开选项。
     *
     * @param work 作品记录
     * @return 作品选项
     */
    private WorkOption toWorkOption(WorkEntity work) {
        String mediaUrl = publicUrl(work.getMediaObjectKey());
        String coverUrl = hasText(work.getCoverObjectKey()) ? publicUrl(work.getCoverObjectKey()) : mediaUrl;
        return new WorkOption(work.getId(), work.getTitle(), coverUrl, mediaUrl,
                work.getWidth(), work.getHeight(), work.getAspectRatio());
    }

    /**
     * 仅为非空对象键生成公开地址。
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

    /**
     * 团队成员选择项。
     *
     * @param memberUserId 成员用户 ID
     * @param displayName 展示名称
     * @param avatarUrl 头像地址
     * @param profession 团队职业身份
     */
    public record MemberOption(Long memberUserId, String displayName, String avatarUrl, String profession) {

        /**
         * 基于成员关系和账号记录构造内部候选项。
         *
         * @param membership 成员关系
         * @param user 用户记录
         */
        private MemberOption(TeamMemberEntity membership, UserEntity user) {
            this(membership == null ? null : membership.getUserId(),
                    user == null ? null : user.getNickname(),
                    user == null ? null : user.getAvatarUrl(),
                    membership == null ? null : membership.getProfession());
        }

    }

    /**
     * 轮播图作品选择项。
     *
     * @param workId 作品 ID
     * @param title 作品标题
     * @param coverUrl 封面地址
     * @param mediaUrl 媒体地址
     * @param width 宽度
     * @param height 高度
     * @param aspectRatio 长宽比
     */
    public record WorkOption(
            Long workId,
            String title,
            String coverUrl,
            String mediaUrl,
            Integer width,
            Integer height,
            String aspectRatio
    ) {
    }
}
