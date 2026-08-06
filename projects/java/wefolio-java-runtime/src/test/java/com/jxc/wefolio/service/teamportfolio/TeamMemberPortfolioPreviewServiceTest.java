package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.service.MinePortfolioService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 团队预览下钻成员个人作品集服务测试。 */
class TeamMemberPortfolioPreviewServiceTest {

    private static final long TEAM_PORTFOLIO_ID = 13L;
    private static final long MEMBER_PORTFOLIO_ID = 88L;
    private static final long USER_ID = 7L;

    @BeforeAll
    static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PortfolioReferenceEntity.class);
    }

    @Test
    void draftPreviewRequiresVisibleAccessAndDelegatesPublishedPersonalPreview() {
        TestContext context = context(1L);
        MinePortfolioDetailResponse expected = new MinePortfolioDetailResponse();
        when(context.minePortfolioService.previewPublishedReferencedPortfolio(MEMBER_PORTFOLIO_ID))
                .thenReturn(expected);

        MinePortfolioDetailResponse actual = context.service.preview(
                TEAM_PORTFOLIO_ID, MEMBER_PORTFOLIO_ID, "draft", USER_ID);

        assertThat(actual).isSameAs(expected);
        verify(context.accessService).requireVisiblePortfolio(TEAM_PORTFOLIO_ID, USER_ID);
        verify(context.accessService, never()).requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, USER_ID);
        verify(context.minePortfolioService).previewPublishedReferencedPortfolio(MEMBER_PORTFOLIO_ID);
    }

    @Test
    void publishedPreviewRequiresVisibleAccessAndDelegatesReadOnlyScheduleMethods() {
        TestContext context = context(1L);
        PortfolioScheduleOptionsResponse options = new PortfolioScheduleOptionsResponse();
        PortfolioScheduleQueryResponse result = new PortfolioScheduleQueryResponse();
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        when(context.minePortfolioService.queryPublishedReferencedPreviewScheduleOptions(
                MEMBER_PORTFOLIO_ID, "2026-07", "schedule-1")).thenReturn(options);
        when(context.minePortfolioService.submitPublishedReferencedPreviewScheduleQuery(
                MEMBER_PORTFOLIO_ID, request)).thenReturn(result);

        assertThat(context.service.scheduleOptions(
                TEAM_PORTFOLIO_ID, MEMBER_PORTFOLIO_ID, "2026-07", "schedule-1", "published", USER_ID))
                .isSameAs(options);
        assertThat(context.service.scheduleQueryPreview(
                TEAM_PORTFOLIO_ID, MEMBER_PORTFOLIO_ID, request, "published", USER_ID))
                .isSameAs(result);

        verify(context.accessService, times(2)).requireVisiblePortfolio(TEAM_PORTFOLIO_ID, USER_ID);
        verify(context.accessService, never()).requireMaintainablePortfolio(TEAM_PORTFOLIO_ID, USER_ID);
        verify(context.minePortfolioService).queryPublishedReferencedPreviewScheduleOptions(
                MEMBER_PORTFOLIO_ID, "2026-07", "schedule-1");
        verify(context.minePortfolioService).submitPublishedReferencedPreviewScheduleQuery(
                MEMBER_PORTFOLIO_ID, request);
    }

    @Test
    void missingMemberPortfolioReferenceIsRejectedBeforePersonalPreview() {
        TestContext context = context(0L);

        assertThatThrownBy(() -> context.service.preview(
                TEAM_PORTFOLIO_ID, MEMBER_PORTFOLIO_ID, "draft", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("成员个人作品集未被当前团队作品集引用");

        verify(context.minePortfolioService, never()).previewPublishedReferencedPortfolio(any());
    }

    private static TestContext context(long referenceCount) {
        TeamPortfolioAccessService accessService = mock(TeamPortfolioAccessService.class);
        PortfolioReferenceEntityMapper referenceMapper = mock(PortfolioReferenceEntityMapper.class);
        MinePortfolioService minePortfolioService = mock(MinePortfolioService.class);
        when(referenceMapper.selectCount(any())).thenReturn(referenceCount);
        TeamMemberPortfolioPreviewService service = new TeamMemberPortfolioPreviewService(
                accessService, referenceMapper, minePortfolioService);
        return new TestContext(service, accessService, minePortfolioService);
    }

    private record TestContext(
            TeamMemberPortfolioPreviewService service,
            TeamPortfolioAccessService accessService,
            MinePortfolioService minePortfolioService
    ) {
    }
}
