package com.jxc.wefolio.service.teamportfolio.component.singlework;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.dto.teamportfolio.TeamSingleWorkPageResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队单个作品成员和作品候选服务。
 */
@Service
@RequiredArgsConstructor
public class TeamSingleWorkComponentService {

    /** 默认页码。 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大页大小。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 允许作品授权标识。 */
    private static final int ALLOW_WORKS = 1;

    /** 单条查询限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 可维护团队作品集的团队角色。 */
    private static final Set<String> MAINTAINABLE_ROLES = Set.of(
            TeamRoleDict.OWNER.getCode(),
            TeamRoleDict.MANAGER.getCode()
    );

    /** 单个作品组件支持的媒体类型。 */
    private static final List<String> SUPPORTED_MEDIA_TYPES = List.of(
            MediaTypeDict.IMAGE.getCode(),
            MediaTypeDict.VIDEO.getCode(),
            MediaTypeDict.ANIMATION.getCode()
    );

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
     * 查询可授权作品的正常团队成员。
     */
    public List<MemberOption> listMembers(long portfolioId, long userId) {
        TeamEntity team = teamPortfolioAccessService.requireMaintainablePortfolio(portfolioId, userId).team();
        return listMembersForTeam(team.getId());
    }

    /**
     * 按团队查询可授权作品的正常成员。
     *
     * @param teamId 团队 ID
     * @param userId 当前维护者用户 ID
     * @return 成员候选
     */
    public List<MemberOption> listTeamMembers(long teamId, long userId) {
        TeamEntity team = teamPortfolioAccessService.requireTeamRole(teamId, userId, MAINTAINABLE_ROLES).team();
        return listMembersForTeam(team.getId());
    }

    /**
     * 查询指定团队下可授权作品的正常成员。
     *
     * @param teamId 已校验团队 ID
     * @return 成员候选
     */
    private List<MemberOption> listMembersForTeam(long teamId) {
        List<TeamMemberEntity> memberships = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
                        .orderByAsc(TeamMemberEntity::getId)
        );
        Set<Long> userIds = (memberships == null ? List.<TeamMemberEntity>of() : memberships).stream()
                .map(TeamMemberEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<Long, UserEntity> users = activeUsers(userIds);
        return (memberships == null ? List.<TeamMemberEntity>of() : memberships).stream()
                .filter(member -> member != null && users.containsKey(member.getUserId()))
                .map(member -> new MemberOption(
                        member.getUserId(),
                        users.get(member.getUserId()).getNickname(),
                        users.get(member.getUserId()).getAvatarUrl(),
                        member.getProfession()))
                .toList();
    }

    /**
     * 查询指定成员可展示的图片、视频和动图作品。
     */
    public List<WorkOption> listWorks(long portfolioId, long memberUserId, long userId) {
        TeamEntity team = teamPortfolioAccessService.requireMaintainablePortfolio(portfolioId, userId).team();
        return listWorksForTeam(team.getId(), memberUserId);
    }

    /**
     * 按团队查询指定成员可展示的图片、视频和动图作品。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     * @param userId 当前维护者用户 ID
     * @return 作品候选
     */
    public List<WorkOption> listTeamWorks(long teamId, long memberUserId, long userId) {
        TeamEntity team = teamPortfolioAccessService.requireTeamRole(teamId, userId, MAINTAINABLE_ROLES).team();
        return listWorksForTeam(team.getId(), memberUserId);
    }

    /**
     * 按团队分页查询指定成员可展示的图片、视频和动图作品。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     * @param userId 当前维护者用户 ID
     * @param page 页码
     * @param pageSize 页大小
     * @param selectedWorkId 当前配置引用的作品 ID
     * @return 作品分页与当前选择
     */
    public TeamSingleWorkPageResponse pageTeamWorks(
            long teamId,
            long memberUserId,
            long userId,
            int page,
            int pageSize,
            Long selectedWorkId
    ) {
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        TeamEntity team = teamPortfolioAccessService.requireTeamRole(teamId, userId, MAINTAINABLE_ROLES).team();
        requireAvailableMember(team.getId(), memberUserId);

        Page<WorkEntity> resultPage = workEntityMapper.selectPage(
                new Page<>(normalizedPage, normalizedPageSize),
                eligibleWorksQuery(memberUserId));
        List<WorkEntity> records = resultPage.getRecords() == null ? List.of() : resultPage.getRecords();
        TeamSingleWorkPageResponse response = new TeamSingleWorkPageResponse();
        response.setPage(normalizedPage);
        response.setPageSize(normalizedPageSize);
        response.setTotal(resultPage.getTotal());
        response.setHasMore(resultPage.getCurrent() < resultPage.getPages());
        response.setWorks(records.stream().map(this::toPageWorkItem).toList());
        response.setSelectedWork(findSelectedWork(memberUserId, selectedWorkId));
        return response;
    }

    /**
     * 查询已校验团队下指定成员的可展示作品。
     *
     * @param teamId 已校验团队 ID
     * @param memberUserId 成员用户 ID
     * @return 作品候选
     */
    private List<WorkOption> listWorksForTeam(long teamId, long memberUserId) {
        requireAvailableMember(teamId, memberUserId);
        List<WorkEntity> works = workEntityMapper.selectList(eligibleWorksQuery(memberUserId));
        return (works == null ? List.<WorkEntity>of() : works).stream()
                .map(this::toWorkOption)
                .toList();
    }

    /**
     * 校验成员仍可向团队作品集授权作品。
     *
     * @param teamId 已校验团队 ID
     * @param memberUserId 成员用户 ID
     */
    private void requireAvailableMember(long teamId, long memberUserId) {
        TeamMemberEntity membership = teamMemberEntityMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getUserId, memberUserId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
                        .last(QUERY_LIMIT_ONE)
        );
        UserEntity user = userEntityMapper.selectById(memberUserId);
        if (membership == null || user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_MEMBER_UNAVAILABLE);
        }
    }

    /**
     * 构造可展示作品的稳定排序查询条件。
     *
     * @param memberUserId 成员用户 ID
     * @return 作品候选查询条件
     */
    private LambdaQueryWrapper<WorkEntity> eligibleWorksQuery(long memberUserId) {
        return Wrappers.lambdaQuery(WorkEntity.class)
                .eq(WorkEntity::getUserId, memberUserId)
                .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                .eq(WorkEntity::getAuditStatus, WorkAuditStatusDict.PASSED.getCode())
                .in(WorkEntity::getMediaType, SUPPORTED_MEDIA_TYPES)
                .orderByAsc(WorkEntity::getSortOrder)
                .orderByAsc(WorkEntity::getId);
    }

    /**
     * 查询当前配置引用的有效作品。
     *
     * @param memberUserId 成员用户 ID
     * @param selectedWorkId 当前配置引用的作品 ID
     * @return 有效作品项，不可用时返回空
     */
    private TeamSingleWorkPageResponse.WorkItem findSelectedWork(long memberUserId, Long selectedWorkId) {
        if (selectedWorkId == null || selectedWorkId <= 0) {
            return null;
        }
        WorkEntity selectedWork = workEntityMapper.selectOne(
                eligibleWorksQuery(memberUserId)
                        .eq(WorkEntity::getId, selectedWorkId)
                        .last(QUERY_LIMIT_ONE));
        if (!isEligibleSelectedWork(memberUserId, selectedWorkId, selectedWork)) {
            return null;
        }
        return toPageWorkItem(selectedWork);
    }

    /**
     * 防御性校验数据层返回的当前作品仍满足成员归属和展示资格。
     *
     * @param memberUserId 成员用户 ID
     * @param selectedWorkId 当前配置引用的作品 ID
     * @param selectedWork 数据层返回的作品
     * @return 是否允许作为当前选择返回
     */
    private boolean isEligibleSelectedWork(
            long memberUserId,
            Long selectedWorkId,
            WorkEntity selectedWork
    ) {
        return selectedWork != null
                && selectedWorkId.equals(selectedWork.getId())
                && Long.valueOf(memberUserId).equals(selectedWork.getUserId())
                && WorkStatusDict.ACTIVE.getCode().equals(selectedWork.getStatus())
                && WorkAuditStatusDict.PASSED.getCode().equals(selectedWork.getAuditStatus())
                && SUPPORTED_MEDIA_TYPES.contains(selectedWork.getMediaType());
    }

    /**
     * 批量读取正常用户。
     */
    private Map<Long, UserEntity> activeUsers(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserEntity> result = new LinkedHashMap<>();
        List<UserEntity> users = userEntityMapper.selectBatchIds(userIds);
        for (UserEntity user : users == null ? List.<UserEntity>of() : users) {
            if (user != null && user.getId() != null && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                result.put(user.getId(), user);
            }
        }
        return result;
    }

    /**
     * 转换作品候选。
     */
    private WorkOption toWorkOption(WorkEntity work) {
        String mediaUrl = publicUrl(work.getMediaObjectKey());
        String coverUrl = hasText(work.getCoverObjectKey()) ? publicUrl(work.getCoverObjectKey()) : mediaUrl;
        return new WorkOption(
                work.getId(),
                work.getTitle(),
                work.getDescription(),
                work.getMediaType(),
                coverUrl,
                mediaUrl,
                work.getWidth(),
                work.getHeight(),
                work.getAspectRatio(),
                work.getDurationMs());
    }

    /**
     * 将原全量候选项转换为分页响应项。
     *
     * @param work 作品实体
     * @return 分页作品候选项
     */
    private TeamSingleWorkPageResponse.WorkItem toPageWorkItem(WorkEntity work) {
        WorkOption option = toWorkOption(work);
        TeamSingleWorkPageResponse.WorkItem item = new TeamSingleWorkPageResponse.WorkItem();
        item.setWorkId(option.workId());
        item.setTitle(option.title());
        item.setDescription(option.description());
        item.setMediaType(option.mediaType());
        item.setCoverUrl(option.coverUrl());
        item.setMediaUrl(option.mediaUrl());
        item.setWidth(option.width());
        item.setHeight(option.height());
        item.setAspectRatio(option.aspectRatio());
        item.setDurationMs(option.durationMs());
        return item;
    }

    /**
     * 生成公开 URL。
     */
    private String publicUrl(String objectKey) {
        return hasText(objectKey) ? cosService.publicUrl(objectKey) : null;
    }

    /**
     * 判断文本是否非空。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 成员候选项。
     *
     * @param memberUserId 成员用户 ID
     * @param displayName 展示名称
     * @param avatarUrl 头像地址
     * @param profession 职业身份
     */
    public record MemberOption(Long memberUserId, String displayName, String avatarUrl, String profession) {
    }

    /**
     * 作品候选项。
     *
     * @param workId 作品 ID
     * @param title 标题
     * @param description 说明
     * @param mediaType 媒体类型
     * @param coverUrl 封面地址
     * @param mediaUrl 原文件地址
     * @param width 宽度
     * @param height 高度
     * @param aspectRatio 比例
     * @param durationMs 视频时长
     */
    public record WorkOption(
            Long workId,
            String title,
            String description,
            String mediaType,
            String coverUrl,
            String mediaUrl,
            Integer width,
            Integer height,
            String aspectRatio,
            Integer durationMs
    ) {
    }
}
