package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 团队作品集引用保护服务测试。 */
@ExtendWith(MockitoExtension.class)
class TeamPortfolioReferenceGuardServiceTest {

    /** 初始化 Lambda 查询列缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PortfolioEntity.class);
        TableInfoHelper.initTableInfo(assistant, PortfolioReferenceEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkEntity.class);
    }

    /** 作品集 Mapper 模拟 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 作品集引用 Mapper 模拟 */
    @Mock
    private PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 作品 Mapper 模拟 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    @Test
    void personalPortfolioGuardShouldBlockDraftAndPublishedStandardTeamReferences() {
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(
                teamPortfolio(701L, 100L),
                teamPortfolio(702L, 101L)
        ));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                reference(701L, PortfolioConfigScopeDict.DRAFT, ReferenceTypeDict.MEMBER_PORTFOLIO, 601L),
                reference(702L, PortfolioConfigScopeDict.PUBLISHED, ReferenceTypeDict.MEMBER_PORTFOLIO, 601L)
        ));

        assertThatThrownBy(() -> service().assertPersonalPortfolioNotReferenced(601L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集正在被团队作品集使用，请先移除引用。");

        ArgumentCaptor<Wrapper<PortfolioEntity>> parentCaptor = wrapperCaptor();
        verify(portfolioEntityMapper).selectList(parentCaptor.capture());
        assertWrapperContains(parentCaptor.getValue(),
                PortfolioOwnerTypeDict.TEAM.getCode(),
                PortfolioTemplateTypeDict.STANDARD.getCode(),
                PortfolioStatusDict.ACTIVE.getCode(),
                "standard-team-v1",
                0L);
        assertThat(parentCaptor.getValue().getSqlSegment()).contains("template_type");

        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> referenceCaptor = wrapperCaptor();
        verify(portfolioReferenceEntityMapper).selectList(referenceCaptor.capture());
        assertWrapperContains(referenceCaptor.getValue(),
                701L,
                702L,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                PortfolioConfigScopeDict.PUBLISHED.getCode(),
                ReferenceTypeDict.MEMBER_PORTFOLIO.getCode(),
                601L,
                1,
                0L);
        assertThat(referenceCaptor.getValue().getSqlSegment())
                .contains("portfolio_id", "config_scope", "reference_type", "reference_id", "is_valid", "deleted");
    }

    @Test
    void personalPortfolioGuardShouldIgnoreNonMatchingOrInactiveReferencesThroughExactWrappers() {
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(teamPortfolio(701L, 100L)));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service().assertPersonalPortfolioNotReferenced(601L)).doesNotThrowAnyException();

        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> referenceCaptor = wrapperCaptor();
        verify(portfolioReferenceEntityMapper).selectList(referenceCaptor.capture());
        assertWrapperContains(referenceCaptor.getValue(),
                ReferenceTypeDict.MEMBER_PORTFOLIO.getCode(), 601L, 1, 0L);
        assertThat(parameters(referenceCaptor.getValue()).values())
                .doesNotContain(ReferenceTypeDict.WORK.getCode(), ReferenceTypeDict.USER_PROFILE.getCode());
    }

    @Test
    void personalPortfolioGuardShouldSkipReferenceQueryWhenNoActiveTeamParentsExist() {
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service().assertPersonalPortfolioNotReferenced(601L)).doesNotThrowAnyException();

        verifyNoInteractions(portfolioReferenceEntityMapper, workEntityMapper);
    }

    @Test
    void memberLeaveGuardShouldBatchCurrentWorksAndPersonalStandardPortfoliosByTeam() {
        when(workEntityMapper.selectList(any())).thenReturn(List.of(work(501L)));
        when(portfolioEntityMapper.selectList(any()))
                .thenReturn(List.of(personalPortfolio(601L, 8L)))
                .thenReturn(List.of(teamPortfolio(701L, 100L)));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of(
                reference(701L, PortfolioConfigScopeDict.DRAFT, ReferenceTypeDict.WORK, 501L),
                reference(701L, PortfolioConfigScopeDict.PUBLISHED, ReferenceTypeDict.MEMBER_PORTFOLIO, 601L)
        ));

        assertThatThrownBy(() -> service().assertMemberCanLeave(100L, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无法移除，成员内容仍被团队作品集使用，请先移除引用。");

        ArgumentCaptor<Wrapper<WorkEntity>> workCaptor = wrapperCaptor();
        verify(workEntityMapper).selectList(workCaptor.capture());
        assertWrapperContains(workCaptor.getValue(), 8L, WorkStatusDict.ACTIVE.getCode(), 0L);
        assertThat(workCaptor.getValue().getSqlSegment()).contains("user_id", "status", "deleted");

        ArgumentCaptor<Wrapper<PortfolioEntity>> portfolioCaptor = wrapperCaptor();
        verify(portfolioEntityMapper, org.mockito.Mockito.times(2)).selectList(portfolioCaptor.capture());
        Wrapper<PortfolioEntity> memberWrapper = portfolioCaptor.getAllValues().get(0);
        assertWrapperContains(memberWrapper,
                PortfolioOwnerTypeDict.USER.getCode(), 8L,
                PortfolioTemplateTypeDict.STANDARD.getCode(),
                "standard-personal-v1", PortfolioStatusDict.ACTIVE.getCode(), 0L);
        Wrapper<PortfolioEntity> parentWrapper = portfolioCaptor.getAllValues().get(1);
        assertWrapperContains(parentWrapper,
                PortfolioOwnerTypeDict.TEAM.getCode(), 100L,
                PortfolioTemplateTypeDict.STANDARD.getCode(),
                PortfolioStatusDict.ACTIVE.getCode(), "standard-team-v1", 0L);
        assertThat(parentWrapper.getSqlSegment()).contains("template_type");

        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> referenceCaptor = wrapperCaptor();
        verify(portfolioReferenceEntityMapper).selectList(referenceCaptor.capture());
        assertWrapperContains(referenceCaptor.getValue(),
                701L,
                ReferenceTypeDict.WORK.getCode(), 501L,
                ReferenceTypeDict.MEMBER_PORTFOLIO.getCode(), 601L,
                PortfolioConfigScopeDict.DRAFT.getCode(),
                PortfolioConfigScopeDict.PUBLISHED.getCode(),
                1, 0L);
        assertThat(referenceCaptor.getValue().getSqlSegment())
                .contains("AND (", " OR ", "reference_type", "reference_id");
    }

    @Test
    void permissionsGuardShouldCheckOnlyWorksWhenOnlyWorksWillBeDisabled() {
        when(workEntityMapper.selectList(any())).thenReturn(List.of(work(501L)));
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of(teamPortfolio(701L, 100L)));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service().assertMemberPermissionsCanChange(100L, 8L, false, true))
                .doesNotThrowAnyException();

        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> captor = wrapperCaptor();
        verify(portfolioReferenceEntityMapper).selectList(captor.capture());
        assertWrapperContains(captor.getValue(), ReferenceTypeDict.WORK.getCode(), 501L);
        assertThat(parameters(captor.getValue()).values())
                .doesNotContain(ReferenceTypeDict.MEMBER_PORTFOLIO.getCode());
    }

    @Test
    void permissionsGuardShouldCheckOnlyMemberPortfoliosWhenOnlyPortfolioWillBeDisabled() {
        when(portfolioEntityMapper.selectList(any()))
                .thenReturn(List.of(personalPortfolio(601L, 8L)))
                .thenReturn(List.of(teamPortfolio(701L, 100L)));
        when(portfolioReferenceEntityMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service().assertMemberPermissionsCanChange(100L, 8L, true, false))
                .doesNotThrowAnyException();

        verify(workEntityMapper, never()).selectList(any());
        ArgumentCaptor<Wrapper<PortfolioReferenceEntity>> captor = wrapperCaptor();
        verify(portfolioReferenceEntityMapper).selectList(captor.capture());
        assertWrapperContains(captor.getValue(), ReferenceTypeDict.MEMBER_PORTFOLIO.getCode(), 601L);
        assertThat(parameters(captor.getValue()).values())
                .doesNotContain(ReferenceTypeDict.WORK.getCode());
    }

    @Test
    void permissionsGuardShouldPerformNoQueriesWhenBothPermissionsStayEnabled() {
        service().assertMemberPermissionsCanChange(100L, 8L, true, true);

        verifyNoInteractions(portfolioEntityMapper, portfolioReferenceEntityMapper, workEntityMapper);
    }

    @Test
    void memberGuardShouldAvoidEmptyInQueriesWhenMemberHasNoCurrentContent() {
        when(workEntityMapper.selectList(any())).thenReturn(List.of());
        when(portfolioEntityMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service().assertMemberCanLeave(100L, 8L)).doesNotThrowAnyException();

        verify(portfolioEntityMapper).selectList(any());
        verifyNoInteractions(portfolioReferenceEntityMapper);
    }

    /** 构造被测服务。 */
    private TeamPortfolioReferenceGuardService service() {
        return new TeamPortfolioReferenceGuardService(
                portfolioEntityMapper,
                portfolioReferenceEntityMapper,
                workEntityMapper
        );
    }

    /** 构造通用查询条件捕获器。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    /** 断言查询参数同时包含指定值。 */
    private void assertWrapperContains(Wrapper<?> wrapper, Object... values) {
        assertThat(parameters(wrapper).values()).contains(values);
    }

    /** 读取实际 MyBatis-Plus 查询对象中的绑定参数。 */
    private java.util.Map<String, Object> parameters(Wrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    /** 构造生效团队作品集。 */
    private PortfolioEntity teamPortfolio(Long id, Long teamId) {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(id);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(teamId);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion("standard-team-v1");
        portfolio.setDeleted(0L);
        return portfolio;
    }

    /** 构造生效标准个人作品集。 */
    private PortfolioEntity personalPortfolio(Long id, Long userId) {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(id);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(userId);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setSchemaVersion("standard-personal-v1");
        portfolio.setDeleted(0L);
        return portfolio;
    }

    /** 构造生效作品。 */
    private WorkEntity work(Long id) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setUserId(8L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setDeleted(0L);
        return work;
    }

    /** 构造生效引用。 */
    private PortfolioReferenceEntity reference(
            Long portfolioId,
            PortfolioConfigScopeDict scope,
            ReferenceTypeDict type,
            Long referenceId
    ) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(portfolioId);
        reference.setConfigScope(scope.getCode());
        reference.setReferenceType(type.getCode());
        reference.setReferenceId(referenceId);
        reference.setIsValid(1);
        reference.setDeleted(0L);
        return reference;
    }
}
