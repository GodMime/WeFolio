package com.jxc.wefolio.service.teamportfolio.component.singlework;

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

import java.util.List;

/**
 * 团队单个作品组件渲染器。
 */
@Component
@RequiredArgsConstructor
public class TeamSingleWorkComponentRenderer {

    /** 允许作品授权标识。 */
    private static final int ALLOW_WORKS = 1;

    /** 团队成员 Mapper。 */
    private final TeamMemberEntityMapper teamMemberEntityMapper;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** 作品 Mapper。 */
    private final WorkEntityMapper workEntityMapper;

    /** COS 服务。 */
    private final CosService cosService;

    /**
     * 构建单个作品展示快照。
     *
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 渲染数据
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        TeamSingleWorkComponentConfig config = parseConfig(normalizedConfig);
        WorkEntity work = requireUsableWork(config, context);
        String mediaUrl = publicUrl(work.getMediaObjectKey());
        String coverUrl = hasText(work.getCoverObjectKey()) ? publicUrl(work.getCoverObjectKey()) : mediaUrl;
        JSONObject snapshot = new JSONObject();
        snapshot.put("memberUserId", config.getMemberUserId());
        snapshot.put("workId", config.getWorkId());
        snapshot.put("title", work.getTitle());
        snapshot.put("description", work.getDescription());
        snapshot.put("mediaType", work.getMediaType());
        snapshot.put("coverUrl", coverUrl);
        snapshot.put("mediaUrl", mediaUrl);
        snapshot.put("width", work.getWidth());
        snapshot.put("height", work.getHeight());
        snapshot.put("aspectRatio", work.getAspectRatio());
        snapshot.put("durationMs", work.getDurationMs());

        JSONObject rendered = new JSONObject();
        rendered.put("showTitle", config.getShowTitle());
        rendered.put("showDescription", config.getShowDescription());
        rendered.put("work", snapshot);
        return rendered;
    }

    /**
     * 解析规范化配置。
     */
    private TeamSingleWorkComponentConfig parseConfig(JSONObject config) {
        if (config == null
                || !(config.get("memberUserId") instanceof Number)
                || !(config.get("workId") instanceof Number)) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
        }
        Long memberUserId = config.getLong("memberUserId");
        Long workId = config.getLong("workId");
        if (memberUserId == null || memberUserId <= 0 || workId == null || workId <= 0) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID);
        }
        TeamSingleWorkComponentConfig parsed = new TeamSingleWorkComponentConfig();
        parsed.setMemberUserId(memberUserId);
        parsed.setWorkId(workId);
        parsed.setShowTitle(config.get("showTitle") instanceof Boolean value ? value : Boolean.TRUE);
        parsed.setShowDescription(config.get("showDescription") instanceof Boolean value ? value : Boolean.FALSE);
        return parsed;
    }

    /**
     * 重新校验资源可用性并返回作品。
     */
    private WorkEntity requireUsableWork(
            TeamSingleWorkComponentConfig config,
            TeamPortfolioComponentContext context
    ) {
        List<TeamMemberEntity> memberships = teamMemberEntityMapper.selectList(
                Wrappers.lambdaQuery(TeamMemberEntity.class)
                        .eq(TeamMemberEntity::getTeamId, context.teamId())
                        .eq(TeamMemberEntity::getUserId, config.getMemberUserId())
                        .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode())
                        .eq(TeamMemberEntity::getAllowWorks, ALLOW_WORKS)
        );
        List<UserEntity> users = userEntityMapper.selectBatchIds(List.of(config.getMemberUserId()));
        List<WorkEntity> works = workEntityMapper.selectList(
                Wrappers.lambdaQuery(WorkEntity.class).eq(WorkEntity::getId, config.getWorkId())
        );
        boolean validMembership = memberships != null && !memberships.isEmpty();
        boolean validUser = users != null && users.stream().anyMatch(user ->
                user != null
                        && config.getMemberUserId().equals(user.getId())
                        && UserStatusDict.ACTIVE.getCode().equals(user.getStatus()));
        WorkEntity work = works == null ? null : works.stream()
                .filter(item -> item != null && config.getWorkId().equals(item.getId()))
                .findFirst()
                .orElse(null);
        boolean validWork = work != null
                && config.getMemberUserId().equals(work.getUserId())
                && WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                && WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                && (MediaTypeDict.IMAGE.getCode().equals(work.getMediaType())
                || MediaTypeDict.VIDEO.getCode().equals(work.getMediaType()));
        if (!validMembership || !validUser || !validWork) {
            throw new BusinessException(TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE);
        }
        return work;
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
}
