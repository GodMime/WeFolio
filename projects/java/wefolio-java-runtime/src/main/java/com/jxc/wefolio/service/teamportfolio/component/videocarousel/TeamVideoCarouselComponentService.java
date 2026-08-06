package com.jxc.wefolio.service.teamportfolio.component.videocarousel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.teamportfolio.TeamVideoCarouselWorkPageResponse;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队视频轮播候选作品来源服务。
 *
 * <p>候选查询只辅助编辑器筛选，保存与发布仍由组件校验器重新读取并校验当前数据。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamVideoCarouselComponentService {

    /** 默认页码。 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大页大小。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 允许作品授权标识。 */
    private static final int ALLOW_WORKS = 1;

    /** 单条成员查询限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 成员不可用提示。 */
    private static final String MEMBER_UNAVAILABLE_MESSAGE = "团队成员不存在或不可用";

    /** 团队作品集访问服务。 */
    private final TeamPortfolioAccessService accessService;

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper memberMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userMapper;

    /** 作品 Mapper。 */
    private final WorkEntityMapper workMapper;

    /** 作品标签关系 Mapper。 */
    private final WorkTagEntityMapper workTagMapper;

    /** 标签 Mapper。 */
    private final WfTagEntityMapper tagMapper;

    /** COS 服务。 */
    private final CosService cosService;

    /**
     * 分页查询指定团队成员可用于视频轮播的作品。
     *
     * @param portfolioId 团队作品集 ID
     * @param memberUserId 成员用户 ID
     * @param userId 当前维护者用户 ID
     * @param keyword 标题搜索词，可为空
     * @param page 页码
     * @param pageSize 页大小
     * @return 视频作品候选分页
     */
    public TeamVideoCarouselWorkPageResponse pageWorks(
            long portfolioId,
            long memberUserId,
            long userId,
            String keyword,
            int page,
            int pageSize
    ) {
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        TeamEntity team = accessService.requireMaintainablePortfolio(portfolioId, userId).team();
        UserEntity member = requireAvailableMember(team.getId(), memberUserId);

        Page<WorkEntity> resultPage = workMapper.selectPage(
                new Page<>(normalizedPage, normalizedPageSize), eligibleWorksQuery(memberUserId, keyword));
        List<WorkEntity> records = resultPage == null || resultPage.getRecords() == null
                ? List.of()
                : resultPage.getRecords();
        List<WorkEntity> eligibleWorks = records.stream()
                .filter(work -> isEligibleWork(work, memberUserId))
                .toList();
        Map<Long, List<TeamVideoCarouselWorkPageResponse.TagItem>> tagsByWorkId =
                loadTags(memberUserId, eligibleWorks);

        TeamVideoCarouselWorkPageResponse response = new TeamVideoCarouselWorkPageResponse();
        response.setPage(normalizedPage);
        response.setPageSize(normalizedPageSize);
        long total = resultPage == null ? 0L : resultPage.getTotal();
        response.setTotal(total);
        response.setHasMore((long) normalizedPage * normalizedPageSize < total);
        response.setWorks(eligibleWorks.stream()
                .map(work -> toWorkItem(work, member, tagsByWorkId.getOrDefault(work.getId(), List.of())))
                .toList());
        return response;
    }

    /**
     * 校验成员关系、作品授权与用户状态。
     *
     * @param teamId 团队 ID
     * @param memberUserId 成员用户 ID
     * @return 当前成员账号
     */
    private UserEntity requireAvailableMember(long teamId, long memberUserId) {
        TeamMemberEntity membership = memberMapper.selectOne(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getUserId, memberUserId)
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
                        .last(QUERY_LIMIT_ONE));
        UserEntity member = userMapper.selectById(memberUserId);
        if (membership == null
                || !Long.valueOf(teamId).equals(membership.getTeamId())
                || !Long.valueOf(memberUserId).equals(membership.getUserId())
                || !JoinStatusDict.JOINED.getCode().equals(membership.getJoinStatus())
                || !Integer.valueOf(ALLOW_WORKS).equals(membership.getAllowWorks())
                || member == null
                || !Long.valueOf(memberUserId).equals(member.getId())
                || !UserStatusDict.ACTIVE.getCode().equals(member.getStatus())) {
            throw new BusinessException(MEMBER_UNAVAILABLE_MESSAGE);
        }
        return member;
    }

    /**
     * 构造固定为有效、审核通过视频的候选查询。
     *
     * @param memberUserId 成员用户 ID
     * @param keyword 标题搜索词
     * @return 作品查询条件
     */
    private LambdaQueryWrapper<WorkEntity> eligibleWorksQuery(long memberUserId, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        return Wrappers.lambdaQuery(WorkEntity.class)
                .eq(WorkEntity::getUserId, memberUserId)
                .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                .eq(WorkEntity::getAuditStatus, WorkAuditStatusDict.PASSED.getCode())
                .eq(WorkEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .like(normalizedKeyword != null, WorkEntity::getTitle, normalizedKeyword)
                .orderByAsc(WorkEntity::getSortOrder)
                .orderByAsc(WorkEntity::getId);
    }

    /**
     * 防御性校验数据层返回的作品仍满足候选资格。
     *
     * @param work 作品记录
     * @param memberUserId 成员用户 ID
     * @return 是否为当前成员的有效视频
     */
    private boolean isEligibleWork(WorkEntity work, long memberUserId) {
        return work != null
                && work.getId() != null
                && Long.valueOf(memberUserId).equals(work.getUserId())
                && WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                && WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                && MediaTypeDict.VIDEO.getCode().equals(work.getMediaType());
    }

    /**
     * 批量读取本页有效作品的启用标签，并按关系顺序分组。
     *
     * @param memberUserId 成员用户 ID
     * @param works 本页有效作品
     * @return 作品 ID 到标签项列表的映射
     */
    private Map<Long, List<TeamVideoCarouselWorkPageResponse.TagItem>> loadTags(
            long memberUserId,
            Collection<WorkEntity> works
    ) {
        Set<Long> workIds = works.stream()
                .map(WorkEntity::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (workIds.isEmpty()) {
            return Map.of();
        }
        List<WorkTagEntity> relations = workTagMapper.selectList(
                Wrappers.lambdaQuery(WorkTagEntity.class)
                        .eq(WorkTagEntity::getUserId, memberUserId)
                        .in(WorkTagEntity::getWorkId, workIds)
                        .orderByAsc(WorkTagEntity::getWorkId)
                        .orderByAsc(WorkTagEntity::getSortOrder)
                        .orderByAsc(WorkTagEntity::getId));
        List<WorkTagEntity> safeRelations = relations == null ? List.of() : relations;
        Set<Long> tagIds = safeRelations.stream()
                .filter(relation -> relation != null && workIds.contains(relation.getWorkId()))
                .map(WorkTagEntity::getTagId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (tagIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, WfTagEntity> activeTags = new LinkedHashMap<>();
        List<WfTagEntity> tags = tagMapper.selectBatchIds(tagIds);
        for (WfTagEntity tag : tags == null ? List.<WfTagEntity>of() : tags) {
            if (tag != null
                    && tag.getId() != null
                    && Long.valueOf(memberUserId).equals(tag.getUserId())
                    && WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus())) {
                activeTags.put(tag.getId(), tag);
            }
        }
        Map<Long, List<TeamVideoCarouselWorkPageResponse.TagItem>> result = new LinkedHashMap<>();
        for (WorkTagEntity relation : safeRelations) {
            if (relation == null || !workIds.contains(relation.getWorkId())) {
                continue;
            }
            WfTagEntity tag = activeTags.get(relation.getTagId());
            if (tag != null) {
                result.computeIfAbsent(relation.getWorkId(), ignored -> new ArrayList<>()).add(toTagItem(tag));
            }
        }
        return result;
    }

    /** 构造公开作品候选项。 */
    private TeamVideoCarouselWorkPageResponse.WorkItem toWorkItem(
            WorkEntity work,
            UserEntity member,
            List<TeamVideoCarouselWorkPageResponse.TagItem> tags
    ) {
        TeamVideoCarouselWorkPageResponse.WorkItem item = new TeamVideoCarouselWorkPageResponse.WorkItem();
        item.setMemberUserId(member.getId());
        item.setMemberDisplayName(defaultString(member.getNickname()));
        item.setWorkId(work.getId());
        item.setTitle(defaultString(work.getTitle()));
        item.setMediaType(MediaTypeDict.VIDEO.getCode());
        item.setCoverUrl(publicUrl(work.getCoverObjectKey()));
        item.setMediaUrl(publicUrl(work.getMediaObjectKey()));
        item.setDurationMs(work.getDurationMs());
        item.setWidth(work.getWidth());
        item.setHeight(work.getHeight());
        item.setAspectRatio(defaultString(work.getAspectRatio()));
        item.setTags(tags == null ? List.of() : List.copyOf(tags));
        return item;
    }

    /** 构造标签响应项。 */
    private TeamVideoCarouselWorkPageResponse.TagItem toTagItem(WfTagEntity tag) {
        TeamVideoCarouselWorkPageResponse.TagItem item = new TeamVideoCarouselWorkPageResponse.TagItem();
        item.setTagId(tag.getId());
        item.setName(defaultString(tag.getName()));
        item.setColor(defaultString(tag.getColor()));
        return item;
    }

    /** 仅为非空对象键生成公开 URL。 */
    private String publicUrl(String objectKey) {
        return hasText(objectKey) ? defaultString(cosService.publicUrl(objectKey)) : "";
    }

    /** 规范化可选搜索词。 */
    private String normalizeKeyword(String keyword) {
        return hasText(keyword) ? keyword.trim() : null;
    }

    /** 将空文本转换为稳定空字符串。 */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    /** 判断文本是否包含非空白字符。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
