package com.jxc.wefolio.service.advancedportfolio;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/** 高级团队互动入口持续检查来源授权，不将已保存引用视为永久许可。 */
@Service
@RequiredArgsConstructor
public class AdvancedTeamSourceAccessService {
    /** 两类有效的个人来源模板。 */
    private static final Set<String> PERSONAL_TEMPLATES = Set.of(
            PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioTemplateTypeDict.ADVANCED.getCode());
    /** 当前团队成员关系。 */
    private final TeamMemberEntityMapper members;
    /** 来源作者当前状态。 */
    private final UserEntityMapper users;
    /** 成员作品集当前发布状态。 */
    private final PortfolioEntityMapper portfolios;

    /** 作品事件只可引用当前有效、已过审且允许团队使用的作品。 */
    public void requireWork(long teamId, WorkEntity work) {
        if (work == null || !Objects.equals(work.getDeleted(), 0L)
                || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())) { throw invalid(); }
        requireMember(teamId, work.getUserId(), true);
    }

    /** 成员下钻互动同时校验普通/高级个人发布状态与当前 allowPortfolio。 */
    public void requireMemberPortfolio(long teamId, Long portfolioId) {
        PortfolioEntity child = portfolios.selectById(portfolioId);
        if (child == null || !Objects.equals(child.getId(), portfolioId)
                || !Objects.equals(child.getDeleted(), 0L)
                || !PortfolioOwnerTypeDict.USER.getCode().equals(child.getOwnerType())
                || !PERSONAL_TEMPLATES.contains(child.getTemplateType())
                || !PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(child.getSchemaVersion())
                || !PortfolioStatusDict.ACTIVE.getCode().equals(child.getStatus())
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(child.getPublicationStatus())
                || child.getPublishedRevision() == null || child.getPublishedRevision() < 1
                || child.getPublishedConfigJson() == null || child.getPublishedConfigJson().isBlank()) { throw invalid(); }
        requireMember(teamId, child.getOwnerId(), false);
    }

    /** 两种团队来源授权相互独立，并逐次读取当前成员和有效用户。 */
    private void requireMember(long teamId, Long userId, boolean work) {
        if (userId == null) { throw invalid(); }
        TeamMemberEntity member = members.selectOne(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, teamId).eq(TeamMemberEntity::getUserId, userId)
                .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode()));
        UserEntity user = users.selectById(userId);
        if (member == null || !Objects.equals(member.getTeamId(), teamId)
                || !Objects.equals(member.getUserId(), userId) || !Objects.equals(member.getDeleted(), 0L)
                || !JoinStatusDict.JOINED.getCode().equals(member.getJoinStatus())
                || !Objects.equals(work ? member.getAllowWorks() : member.getAllowPortfolio(), 1)
                || user == null || !Objects.equals(user.getId(), userId) || !Objects.equals(user.getDeleted(), 0L)
                || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) { throw invalid(); }
    }

    /** 当前来源失效只返回固定文案，不泄漏旧资源或账户信息。 */
    private BusinessException invalid() { return new BusinessException(AdvancedPortfolioMessage.SOURCE_UNAVAILABLE); }
}
