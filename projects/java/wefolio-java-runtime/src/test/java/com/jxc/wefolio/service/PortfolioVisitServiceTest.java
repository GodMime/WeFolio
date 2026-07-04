package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作品集访问服务测试 — 覆盖打开访问、事件计数和扣费场景。
 */
@ExtendWith(MockitoExtension.class)
class PortfolioVisitServiceTest {

    /** 访问汇总 Mapper 模拟 */
    @Mock
    private VisitRecordEntityMapper visitRecordEntityMapper;

    /** 访问事件 Mapper 模拟 */
    @Mock
    private VisitEventEntityMapper visitEventEntityMapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    @Test
    void recordOpenShouldCreateVisitRecordAndConsumeByTwoHourWindow() {
        when(visitRecordEntityMapper.insert(any(VisitRecordEntity.class))).thenAnswer(invocation -> {
            VisitRecordEntity record = invocation.getArgument(0);
            record.setId(33L);
            return 1;
        });

        VisitRecordEntity record = service().recordOpen(
                portfolio(),
                "visitor-a",
                "wx-openid-hash",
                "WECHAT_SHARE_CARD",
                "open-1"
        );

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).insert(recordCaptor.capture());
        assertThat(record.getId()).isEqualTo(33L);
        assertThat(recordCaptor.getValue().getVisitorKey()).isEqualTo("visitor-a");
        assertThat(recordCaptor.getValue().getPortfolioTitleSnapshot()).isEqualTo("林安婚礼司仪");
        assertThat(recordCaptor.getValue().getPortfolioShareCodeSnapshot()).isEqualTo("PF001");
        assertThat(recordCaptor.getValue().getPortfolioType()).isEqualTo(PortfolioTypeDict.PERSONAL.getCode());
        assertThat(recordCaptor.getValue().getVisitCount()).isEqualTo(1);
        verify(pointService).consume(
                eq(7L),
                eq(PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode()),
                eq("PORTFOLIO_OPEN"),
                startsWith("88:wx-openid-hash:"),
                eq(1),
                startsWith("PF_OPEN:88:wx-openid-hash:"),
                eq("访客打开个人作品集")
        );
        verify(visitEventEntityMapper).insert(any(VisitEventEntity.class));
    }

    @Test
    void recordOpenShouldRefreshPortfolioSnapshotOnExistingRecord() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setVisitCount(1);
        record.setPortfolioTitleSnapshot("旧标题");
        record.setPortfolioShareCodeSnapshot("OLD001");
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);

        service().recordOpen(portfolio(), "visitor-a", "wx-openid-hash", "WECHAT_SHARE_CARD", "open-2");

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getPortfolioTitleSnapshot()).isEqualTo("林安婚礼司仪");
        assertThat(recordCaptor.getValue().getPortfolioShareCodeSnapshot()).isEqualTo("PF001");
        assertThat(recordCaptor.getValue().getVisitCount()).isEqualTo(2);
    }

    @Test
    void recordEventShouldUpdateCountersAndConsumeVideoPlayback() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setViewWorkCount(0);
        record.setPlayVideoCount(0);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("visitor-a");
        request.setEventType(VisitEventTypeDict.VIDEO_PLAYED.getCode());
        request.setWorkId(11L);
        request.setDurationSeconds(18);
        request.setIdempotencyKey("video-1");

        service().recordEvent(portfolio(), request);

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getPlayVideoCount()).isEqualTo(1);
        verify(pointService).consume(
                7L,
                PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode(),
                "PORTFOLIO_VIDEO",
                "88:11:visitor-a",
                1,
                "video-1",
                "访客播放作品集视频"
        );
        verify(visitEventEntityMapper).insert(any(VisitEventEntity.class));
    }

    private PortfolioVisitService service() {
        return new PortfolioVisitService(visitRecordEntityMapper, visitEventEntityMapper, pointService);
    }

    private PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(88L);
        portfolio.setShareCode("PF001");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(7L);
        portfolio.setPublishedRevision(3);
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪"},"components":[]}
                """);
        return portfolio;
    }
}
