package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客作品集服务测试 — 覆盖已发布配置读取、维护中遮罩和访客档期。
 */
@ExtendWith(MockitoExtension.class)
class VisitorPortfolioServiceTest {

    /** 作品集 Mapper 模拟 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 档期 Mapper 模拟 */
    @Mock
    private ScheduleEntityMapper scheduleEntityMapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 访问服务模拟 */
    @Mock
    private PortfolioVisitService portfolioVisitService;

    /** 作品集渲染服务模拟 */
    @Mock
    private PortfolioRenderService portfolioRenderService;

    @Test
    void getPortfolioShouldRejectUnpublishedPortfolio() {
        PortfolioEntity portfolio = publishedPortfolio();
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);

        assertThatThrownBy(() -> service().getPortfolio("PF001", "visitor-a", "WECHAT_SHARE_CARD", "open-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集暂不可访问");
    }

    @Test
    void getPortfolioShouldReturnUnderMaintenanceWhenOwnerBalanceIsInsufficient() {
        PortfolioEntity portfolio = publishedPortfolio();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        org.mockito.Mockito.doThrow(new BusinessException("积分余额不足，请充值后再试"))
                .when(pointService).assertCanConsume(any(), any(), any(Integer.class));
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        renderData.setUnderMaintenance(true);
        renderData.setComponents(List.of());
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(true), any(), eq(null)))
                .thenReturn(renderData);

        VisitorPortfolioResponse response = service().getPortfolio("PF001", "visitor-a", "WECHAT_SHARE_CARD", "open-1");

        assertThat(response.isUnderMaintenance()).isTrue();
        assertThat(response.getMaintenanceText().getPrimary()).isEqualTo("UNDER MAINTENANCE");
        assertThat(response.getMaintenanceText().getSecondary()).isEqualTo("维护中");
        assertThat(response.getVisitRecordId()).isNull();
        assertThat(response.getRenderData()).isSameAs(renderData);
        verify(portfolioVisitService, never()).recordOpen(any(), any(), any(), any());
    }

    @Test
    void getPortfolioShouldReturnPublishedConfigAndRecordVisit() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(portfolioVisitService.recordOpen(portfolio, "visitor-a", "WECHAT_SHARE_CARD", "open-1")).thenReturn(record);
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        renderData.setPreview(false);
        renderData.setVisitRecordId(33L);
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(false), eq(null), eq(33L)))
                .thenReturn(renderData);

        VisitorPortfolioResponse response = service().getPortfolio("PF001", "visitor-a", "WECHAT_SHARE_CARD", "open-1");

        assertThat(response.isUnderMaintenance()).isFalse();
        assertThat(response.getPortfolioId()).isEqualTo(88L);
        assertThat(response.getPublishedRevision()).isEqualTo(3);
        assertThat(response.getTitle()).isEqualTo("林安婚礼司仪");
        assertThat(response.getConfig()).isNotNull();
        assertThat(response.getVisitRecordId()).isEqualTo(33L);
        assertThat(response.getRenderData()).isSameAs(renderData);
    }

    @Test
    void getPortfolioShouldRejectInvalidPublishedConfigWithUnavailableMessage() {
        PortfolioEntity portfolio = publishedPortfolio();
        portfolio.setPublishedConfigJson("{invalid-json");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);

        assertThatThrownBy(() -> service().getPortfolio("PF001", "visitor-a", "WECHAT_SHARE_CARD", "open-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        verify(pointService, never()).assertCanConsume(any(), any(), any(Integer.class));
        verify(portfolioVisitService, never()).recordOpen(any(), any(), any(), any());
    }

    @Test
    void queryScheduleShouldHideInternalContactAndNoteFields() {
        PortfolioEntity portfolio = publishedPortfolio();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        ScheduleEntity schedule = new ScheduleEntity();
        schedule.setId(9L);
        schedule.setUserId(7L);
        schedule.setScheduleDate(LocalDate.of(2026, 7, 18));
        schedule.setSlotNameSnapshot("午宴");
        schedule.setStartTimeSnapshot(LocalTime.of(10, 0));
        schedule.setEndTimeSnapshot(LocalTime.of(14, 0));
        schedule.setColorSnapshot("#2d5f9a");
        schedule.setStatus(ScheduleStatusDict.TENTATIVE.getCode());
        schedule.setContactNameCiphertext("内部客户");
        schedule.setContactPhoneCiphertext("13800138000");
        schedule.setNote("内部备注");
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of(schedule));

        VisitorPortfolioScheduleResponse response = service().querySchedule(
                "PF001",
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 18),
                "ALL",
                "visitor-a",
                "schedule-1"
        );

        assertThat(response.getSchedules()).hasSize(1);
        VisitorPortfolioScheduleResponse.Item item = response.getSchedules().get(0);
        assertThat(item.getSlotName()).isEqualTo("午宴");
        assertThat(item.getStatusText()).isEqualTo("待定");
        assertThat(item.getContactName()).isNull();
        assertThat(item.getContactPhone()).isNull();
        assertThat(item.getNote()).isNull();
        verify(portfolioVisitService).recordScheduleQuery(portfolio, "visitor-a", LocalDate.of(2026, 7, 18), "schedule-1");
    }

    private VisitorPortfolioService service() {
        return new VisitorPortfolioService(
                portfolioEntityMapper,
                scheduleEntityMapper,
                pointService,
                portfolioVisitService,
                portfolioRenderService
        );
    }

    private PortfolioEntity publishedPortfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(88L);
        portfolio.setShareCode("PF001");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(7L);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setPublishedConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"林安婚礼司仪\"},\"components\":[]}");
        return portfolio;
    }
}
