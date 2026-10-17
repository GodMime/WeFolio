package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.*;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.*;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.*;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 高级团队混合来源查询，作品和成员作品集分别按其授权检查。 */
@Service
@RequiredArgsConstructor
public class AdvancedPortfolioSourceService {
    /** 来源类型白名单。 */
    private static final Set<String> KINDS = Set.of("WORK", "MEMBER_PORTFOLIO");
    /** 公开素材地址生成。 */
    private final CosService cos;
    /** 团队成员查询。 */
    private final TeamMemberEntityMapper members;
    /** 有效用户查询。 */
    private final UserEntityMapper users;
    /** 成员作品查询。 */
    private final WorkEntityMapper works;
    /** 成员发布作品集查询。 */
    private final PortfolioEntityMapper portfolios;
    /** 当前用户自定义标签。 */
    private final WfTagEntityMapper tags;
    /** 作品与标签当前关联。 */
    private final WorkTagEntityMapper workTags;

    /** 授权范围内混合查询，编号始终由本地编辑会话分配。 */
    public JSONObject listSources(long teamId, String kind, Long memberId, String keyword, int page, int pageSize) {
        if (kind != null && !KINDS.contains(kind) || page < 1 || pageSize < 1 || pageSize > 100) { throw invalid(); }
        List<TeamMemberEntity> relations = members.selectList(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, teamId).eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                .orderByAsc(TeamMemberEntity::getId));
        if (memberId != null && relations.stream().noneMatch(row -> Objects.equals(row.getUserId(), memberId))) { throw invalid(); }
        JSONArray items = new JSONArray();
        JSONArray options = new JSONArray();
        Map<Long,WorkEntity> selectedWorks = new LinkedHashMap<>();
        Map<Long,JSONObject> workItems = new LinkedHashMap<>();
        for (TeamMemberEntity relation : relations) {
            UserEntity user = users.selectById(relation.getUserId());
            if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) { continue; }
            boolean allowWorks = Integer.valueOf(1).equals(relation.getAllowWorks());
            boolean allowPortfolio = Integer.valueOf(1).equals(relation.getAllowPortfolio());
            if (!allowWorks && !allowPortfolio) { continue; }
            JSONObject option = new JSONObject();
            option.put("memberUserId", user.getId().toString()); option.put("displayName", user.getNickname());
            option.put("memberName", user.getNickname()); option.put("avatarUrl", user.getAvatarUrl()); options.add(option);
            if (memberId != null && !Objects.equals(memberId, user.getId())) { continue; }
            if (allowWorks && (kind == null || "WORK".equals(kind))) {
                for (WorkEntity work : works.selectList(Wrappers.<WorkEntity>lambdaQuery()
                        .eq(WorkEntity::getUserId, user.getId()).eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                        .eq(WorkEntity::getAuditStatus, WorkAuditStatusDict.PASSED.getCode()))) {
                    if (!Objects.equals(work.getUserId(), user.getId()) || !Objects.equals(work.getDeleted(), 0L)
                            || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                            || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())) { continue; }
                    JSONObject item = source("WORK", work.getId(), user, work.getTitle());
                    item.put("mediaType", work.getMediaType()); item.put("coverUrl", cos.publicUrl(work.getCoverObjectKey() == null ? work.getMediaObjectKey() : work.getCoverObjectKey()));
                    item.put("width", work.getWidth()); item.put("height", work.getHeight()); item.put("tags", new JSONArray()); item.put("thumbnailUrl", item.get("coverUrl")); item.put("available", true);
                    selectedWorks.put(work.getId(), work); workItems.put(work.getId(), item);
                }
            }
            if (allowPortfolio && (kind == null || "MEMBER_PORTFOLIO".equals(kind))) {
                for (PortfolioEntity child : portfolios.selectList(Wrappers.<PortfolioEntity>lambdaQuery()
                        .eq(PortfolioEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode()).eq(PortfolioEntity::getOwnerId, user.getId())
                        .in(PortfolioEntity::getTemplateType, PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioTemplateTypeDict.ADVANCED.getCode())
                        .eq(PortfolioEntity::getSchemaVersion, PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1)
                        .eq(PortfolioEntity::getStatus, PortfolioStatusDict.ACTIVE.getCode())
                        .eq(PortfolioEntity::getPublicationStatus, PortfolioPublicationStatusDict.PUBLISHED.getCode()))) {
                    if (child.getPublishedConfigJson() == null || child.getPublishedRevision() == null || child.getPublishedRevision() < 1) { continue; }
                    JSONObject share = JSON.parseObject(child.getPublishedConfigJson()).getJSONObject("share");
                    String title = share == null ? "" : share.getString("title");
                    if (keyword != null && !keyword.isBlank() && (title == null || !title.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))) { continue; }
                    JSONObject item = source("MEMBER_PORTFOLIO", child.getId(), user, title);
                    item.put("templateType", child.getTemplateType()); item.put("shareCode", child.getShareCode());
                    item.put("coverUrl", share == null ? null : share.getString("coverUrl")); item.put("tags", new JSONArray()); item.put("thumbnailUrl", item.get("coverUrl")); item.put("available", true); items.add(item);
                }
            }
        }
        JSONArray visibleTags = attachTags(selectedWorks, workItems);
        for (Map.Entry<Long,JSONObject> entry : workItems.entrySet()) {
            WorkEntity work = selectedWorks.get(entry.getKey());
            if (matchesKeyword(keyword, work.getTitle()) || matchesKeyword(keyword, work.getOriginalFileName())
                    || entry.getValue().getJSONArray("tags").stream().anyMatch(tag -> matchesKeyword(keyword, ((JSONObject) tag).getString("name")))) {
                items.add(entry.getValue());
            }
        }
        List<JSONObject> sorted = items.toJavaList(JSONObject.class).stream()
                .sorted(Comparator.comparing((JSONObject item) -> item.getString("kind")).thenComparing(item -> item.getLong("sourceId"), Comparator.reverseOrder())).toList();
        long offset = (long) (page - 1) * pageSize;
        JSONObject result = new JSONObject(); result.put("page", page); result.put("pageSize", pageSize);
        result.put("hasMore", offset + pageSize < sorted.size()); result.put("total", sorted.size());
        result.put("items", sorted.stream().skip(offset).limit(pageSize).toList()); result.put("records", result.get("items")); result.put("members", options); result.put("tags", visibleTags);
        return result;
    }
    /** 真实用户标签按当前关联批量回填，防止跨用户错误关联暴露标签。 */
    private JSONArray attachTags(Map<Long,WorkEntity> selectedWorks, Map<Long,JSONObject> items) {
        if (selectedWorks.isEmpty()) { return new JSONArray(); }
        List<WorkTagEntity> relations = workTags.selectList(Wrappers.<WorkTagEntity>lambdaQuery()
                .in(WorkTagEntity::getWorkId, selectedWorks.keySet()).orderByAsc(WorkTagEntity::getSortOrder));
        if (relations == null || relations.isEmpty()) { return new JSONArray(); }
        List<Long> tagIds = relations.stream().filter(relation -> relation != null && selectedWorks.containsKey(relation.getWorkId()))
                .map(WorkTagEntity::getTagId).filter(Objects::nonNull).distinct().toList();
        if (tagIds.isEmpty()) { return new JSONArray(); }
        Map<Long,WfTagEntity> currentTags = new LinkedHashMap<>();
        for (WfTagEntity tag : tags.selectList(Wrappers.<WfTagEntity>lambdaQuery()
                .in(WfTagEntity::getId, tagIds).eq(WfTagEntity::getStatus, WfTagStatusDict.ACTIVE.getCode()))) {
            if (tag != null && Objects.equals(tag.getDeleted(), 0L) && WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus())) { currentTags.put(tag.getId(), tag); }
        }
        Map<Long,JSONObject> options = new LinkedHashMap<>();
        for (WorkTagEntity relation : relations) {
            if (relation == null || !Objects.equals(relation.getDeleted(), 0L)) { continue; }
            WorkEntity work = selectedWorks.get(relation.getWorkId()); WfTagEntity tag = currentTags.get(relation.getTagId());
            if (work == null || tag == null || !Objects.equals(work.getUserId(), relation.getUserId())
                    || !Objects.equals(work.getUserId(), tag.getUserId())) { continue; }
            JSONObject summary = JSONObject.of("id", tag.getId().toString(), "name", tag.getName(), "color", tag.getColor());
            items.get(work.getId()).getJSONArray("tags").add(summary);
            options.putIfAbsent(tag.getId(), summary);
        }
        return new JSONArray(new ArrayList<>(options.values()));
    }
    /** 与普通作品搜索保持标题、原文件名、真实标签名称的命中语义。 */
    private boolean matchesKeyword(String keyword, String value) {
        return keyword == null || keyword.isBlank() || value != null && value.toLowerCase(Locale.ROOT).contains(keyword.strip().toLowerCase(Locale.ROOT));
    }
    /** 统一来源摘要不展开成员作品集的内部作品。 */
    private JSONObject source(String kind, Long id, UserEntity user, String title) {
        JSONObject source = new JSONObject(); source.put("kind", kind); source.put("sourceId", id.toString());
        source.put("memberUserId", user.getId().toString()); source.put("memberName", user.getNickname()); source.put("title", title);
        return source;
    }
    /** 安全固定错误。 */
    private BusinessException invalid() { return new BusinessException(AdvancedPortfolioMessage.SOURCE_UNAVAILABLE); }
}
