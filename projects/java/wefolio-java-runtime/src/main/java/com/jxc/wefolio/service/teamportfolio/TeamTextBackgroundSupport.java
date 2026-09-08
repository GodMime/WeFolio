package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioTextMessage;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.PortfolioTextBackgroundConfigSupport;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;

/** 团队文字背景统一授权、资源展示及 WORK 引用支持。 */
@Component
@RequiredArgsConstructor
public class TeamTextBackgroundSupport {
    /** 已授权作品标识。 */
    private static final int ALLOW_WORKS = 1;
    /** 有效资源引用标识。 */
    private static final int REFERENCE_VALID = 1;
    /** 团队成员持久层。 */
    private final TeamMemberEntityMapper memberMapper;
    /** 成员账号持久层。 */
    private final UserEntityMapper userMapper;
    /** 作品持久层。 */
    private final WorkEntityMapper workMapper;
    /** 资源 URL 服务。 */
    private final CosService cosService;

    /** 判断配置是否启用文字背景，供顶层批量资源收集使用。 */
    public static boolean isEnabled(Map<String,Object> config) {
        return config != null && Boolean.TRUE.equals(config.get(PortfolioTextBackgroundConfigSupport.ENABLED));
    }

    /** 保存和发布都重新验证背景权限，禁止依赖过去的展示快照。 */
    public void validate(Map<String,Object> config, TeamPortfolioComponentContext context) {
        if (!Boolean.TRUE.equals(config.get(PortfolioTextBackgroundConfigSupport.ENABLED))) { return; }
        if (resolve(config, context, false) == null) {
            throw new BusinessException(PortfolioTextMessage.BACKGROUND_UNAVAILABLE);
        }
    }

    /** 一次批量加载所有文字背景，严格过滤所属团队、成员授权和账号状态。 */
    public Map<Long,WorkEntity> load(List<? extends Map<String,Object>> configs, TeamPortfolioComponentContext context) {
        Set<Long> workIds = new LinkedHashSet<>();
        Set<Long> memberIds = new LinkedHashSet<>();
        for (Map<String,Object> config : configs) {
            if (!Boolean.TRUE.equals(config.get(PortfolioTextBackgroundConfigSupport.ENABLED))) { continue; }
            Long workId = safeId(config.get(PortfolioTextBackgroundConfigSupport.WORK_ID));
            Long memberId = safeId(config.get(PortfolioTextBackgroundConfigSupport.MEMBER_ID));
            if (workId != null && memberId != null) { workIds.add(workId); memberIds.add(memberId); }
        }
        if (workIds.isEmpty()) { return Map.of(); }
        List<TeamMemberEntity> members = memberMapper.selectList(Wrappers.lambdaQuery(TeamMemberEntity.class)
                .eq(TeamMemberEntity::getTeamId,context.teamId())
                .in(TeamMemberEntity::getUserId,memberIds)
                .eq(TeamMemberEntity::getJoinStatus,JoinStatusDict.JOINED.getCode())
                .eq(TeamMemberEntity::getAllowWorks,ALLOW_WORKS));
        Set<Long> authorized = new LinkedHashSet<>();
        for (TeamMemberEntity member : safe(members)) {
            if (member != null && Objects.equals(member.getTeamId(),context.teamId())
                    && memberIds.contains(member.getUserId())
                    && JoinStatusDict.JOINED.getCode().equals(member.getJoinStatus())
                    && Integer.valueOf(ALLOW_WORKS).equals(member.getAllowWorks())) {
                authorized.add(member.getUserId());
            }
        }
        if (authorized.isEmpty()) { return Map.of(); }
        Set<Long> active = new LinkedHashSet<>();
        for (UserEntity user : safe(userMapper.selectBatchIds(authorized))) {
            if (user != null && authorized.contains(user.getId()) && UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                active.add(user.getId());
            }
        }
        if (active.isEmpty()) { return Map.of(); }
        Map<Long,WorkEntity> result = new LinkedHashMap<>();
        for (WorkEntity work : safe(workMapper.selectBatchIds(workIds))) {
            if (work != null && workIds.contains(work.getId()) && active.contains(work.getUserId())
                    && WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                    && WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                    && PortfolioTextBackgroundConfigSupport.supportsMedia(work.getMediaType())) {
                result.put(work.getId(),work);
            }
        }
        return result;
    }

    /** 将授权背景资源附加到展示数据，失效时只标记，不删除内容。 */
    public void render(JSONObject target, Map<String,Object> config, TeamPortfolioComponentContext context) {
        boolean enabled = Boolean.TRUE.equals(config.get(PortfolioTextBackgroundConfigSupport.ENABLED));
        WorkEntity work = enabled ? resolve(config,context,true) : null;
        PortfolioRenderDto.BackgroundWork background = work == null ? null : snapshot(work);
        target.remove(PortfolioTextBackgroundConfigSupport.WORK);
        target.put(PortfolioTextBackgroundConfigSupport.WORK,background);
        target.put(PortfolioTextBackgroundConfigSupport.INVALID,enabled && background == null);
    }

    /** 为背景位置生成独立 WORK 引用，沿用原引用重建事务和资源保护。 */
    public List<PortfolioReferenceEntity> extract(String componentKey,String componentPath,
                                                 JSONObject config,TeamPortfolioComponentContext context) {
        if (!Boolean.TRUE.equals(config.get(PortfolioTextBackgroundConfigSupport.ENABLED))) { return List.of(); }
        WorkEntity work = resolve(config,context,false);
        if (work == null) { throw new BusinessException(PortfolioTextMessage.BACKGROUND_UNAVAILABLE); }
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(context.portfolioId());
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode()); reference.setReferenceId(work.getId());
        reference.setComponentKey(componentKey);
        reference.setComponentPath(componentPath + PortfolioTextBackgroundConfigSupport.REFERENCE_PATH);
        reference.setSortOrder(0); reference.setIsValid(REFERENCE_VALID);
        reference.setSnapshotJson(JSON.toJSONString(snapshot(work), JSONWriter.Feature.WriteNulls));
        return List.of(reference);
    }

    /** 从本次批量授权上下文或即时查询中解析背景，并核验配置成员等于实际作者。 */
    private WorkEntity resolve(Map<String,Object> config,TeamPortfolioComponentContext context,boolean useCache) {
        Long workId = safeId(config.get(PortfolioTextBackgroundConfigSupport.WORK_ID));
        Long memberId = safeId(config.get(PortfolioTextBackgroundConfigSupport.MEMBER_ID));
        if (workId == null || memberId == null) { return null; }
        Map<Long,WorkEntity> works = useCache && context.textBackgroundWorks() != null
                ? context.textBackgroundWorks() : load(List.of(config),context);
        WorkEntity work = works.get(workId);
        return work != null && memberId.equals(work.getUserId()) ? work : null;
    }

    /** 输出原始图片或动图及其比例尺寸。 */
    private PortfolioRenderDto.BackgroundWork snapshot(WorkEntity work) {
        if (work.getMediaObjectKey() == null || work.getMediaObjectKey().isBlank()) { return null; }
        PortfolioRenderDto.BackgroundWork result = new PortfolioRenderDto.BackgroundWork();
        result.setWorkId(work.getId()); result.setMediaType(work.getMediaType());
        result.setUrl(cosService.publicUrl(work.getMediaObjectKey()));
        result.setWidth(work.getWidth()); result.setHeight(work.getHeight());
        return result;
    }

    /** 展示遇到历史非法资源标识时使用失效兜底。 */
    private Long safeId(Object raw) {
        try { return PortfolioTextBackgroundConfigSupport.positiveLong(raw); }
        catch (BusinessException exception) { return null; }
    }

    /** 空查询结果视为资源不可用。 */
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
