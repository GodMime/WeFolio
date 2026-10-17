package com.jxc.wefolio.service.advancedportfolio;

import com.jxc.wefolio.dict.*;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 高级访客当前来源授权测试；不依赖保存时的引用快照。 */
class AdvancedTeamSourceAccessServiceTest {
    /** 当前有效成员持久层。 */
    private final TeamMemberEntityMapper members = mock(TeamMemberEntityMapper.class);
    /** 成员账号持久层。 */
    private final UserEntityMapper users = mock(UserEntityMapper.class);
    /** 成员作品集持久层。 */
    private final PortfolioEntityMapper portfolios = mock(PortfolioEntityMapper.class);
    /** 当前源授权服务。 */
    private final AdvancedTeamSourceAccessService service = new AdvancedTeamSourceAccessService(members, users, portfolios);

    /** 作品与个人作品集分别使用各自授权开关。 */
    @Test void checksIndependentCurrentPermissions() {
        TeamMemberEntity member = arrangeMember(); member.setAllowWorks(1); member.setAllowPortfolio(0);
        service.requireWork(31L, work());
        when(portfolios.selectById(21L)).thenReturn(portfolio());
        assertThatThrownBy(() -> service.requireMemberPortfolio(31L, 21L)).isInstanceOf(BusinessException.class);
        member.setAllowWorks(0); member.setAllowPortfolio(1);
        service.requireMemberPortfolio(31L, 21L);
        assertThatThrownBy(() -> service.requireWork(31L, work())).isInstanceOf(BusinessException.class);
    }

    /** 退出团队、跨团队关系和禁用账号不能利用旧发布引用上报事件。 */
    @Test void rejectsRevocationAndWrongCurrentMembership() {
        TeamMemberEntity member = arrangeMember(); member.setJoinStatus(JoinStatusDict.REMOVED.getCode());
        assertThatThrownBy(() -> service.requireWork(31L, work())).isInstanceOf(BusinessException.class);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setTeamId(99L);
        assertThatThrownBy(() -> service.requireWork(31L, work())).isInstanceOf(BusinessException.class);
        member.setTeamId(31L); when(users.selectById(7L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireWork(31L, work())).isInstanceOf(BusinessException.class);
    }

    /** 高级子作品集仅在当前个人发布版本存在时可作为来源。 */
    @Test void rejectsDeletedUnpublishedAndUnsupportedChild() {
        arrangeMember(); PortfolioEntity child = portfolio(); when(portfolios.selectById(21L)).thenReturn(child);
        service.requireMemberPortfolio(31L, 21L);
        child.setDeleted(21L);
        assertThatThrownBy(() -> service.requireMemberPortfolio(31L, 21L)).isInstanceOf(BusinessException.class);
        child.setDeleted(0L); child.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        assertThatThrownBy(() -> service.requireMemberPortfolio(31L, 21L)).isInstanceOf(BusinessException.class);
        child.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode()); child.setSchemaVersion("future-schema");
        assertThatThrownBy(() -> service.requireMemberPortfolio(31L, 21L)).isInstanceOf(BusinessException.class);
    }

    /** 当前过审状态撤销后停止作品互动。 */
    @Test void rejectsInactiveWorkEvenWhenMemberStillAuthorized() {
        WorkEntity work = work(); work.setStatus(WorkStatusDict.PROCESSING.getCode());
        assertThatThrownBy(() -> service.requireWork(31L, work)).isInstanceOf(BusinessException.class);
    }

    /** 安排具有独立两类授权的当前团队成员。 */
    private TeamMemberEntity arrangeMember() {
        TeamMemberEntity member = new TeamMemberEntity(); member.setTeamId(31L); member.setUserId(7L);
        member.setDeleted(0L); member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(1); member.setAllowPortfolio(1);
        UserEntity user = new UserEntity(); user.setId(7L); user.setDeleted(0L); user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(members.selectOne(any())).thenReturn(member); when(users.selectById(7L)).thenReturn(user); return member;
    }
    /** 当前有效个人高级发布快照。 */
    private PortfolioEntity portfolio() {
        PortfolioEntity child = new PortfolioEntity(); child.setId(21L); child.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        child.setOwnerId(7L); child.setDeleted(0L); child.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode());
        child.setStatus(PortfolioStatusDict.ACTIVE.getCode()); child.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        child.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1); child.setPublishedRevision(1); child.setPublishedConfigJson("{}"); return child;
    }
    /** 当前可用的作品主数据。 */
    private WorkEntity work() {
        WorkEntity work = new WorkEntity(); work.setId(10L); work.setUserId(7L); work.setDeleted(0L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode()); return work;
    }
}
