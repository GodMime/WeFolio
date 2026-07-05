package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
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
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void recordOpenShouldCreateVisitRecordWithGlobalVisitorId() {
        when(visitRecordEntityMapper.insert(any(VisitRecordEntity.class))).thenAnswer(invocation -> {
            VisitRecordEntity record = invocation.getArgument(0);
            record.setId(33L);
            return 1;
        });

        service().recordOpen(
                portfolio(),
                1024L,
                "visitor-stable-key",
                "WX_OPENID:digest-123",
                "WECHAT_SHARE_CARD",
                "open-visitor-1"
        );

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getVisitorId()).isEqualTo(1024L);
        assertThat(recordCaptor.getValue().getVisitorKey()).isEqualTo("visitor-stable-key");
        ArgumentCaptor<VisitEventEntity> eventCaptor = ArgumentCaptor.forClass(VisitEventEntity.class);
        verify(visitEventEntityMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getVisitorKey()).isEqualTo("visitor-stable-key");
    }

    /**
     * 迁移期旧访问汇总 — 全局访客 ID 未命中时回退 visitorKey 并绑定访客 ID。
     */
    @Test
    void recordOpenShouldFallbackToVisitorKeyWhenGlobalVisitorRecordIsMissing() {
        VisitRecordEntity legacyRecord = new VisitRecordEntity();
        legacyRecord.setId(44L);
        legacyRecord.setVisitorKey("legacy-key");
        legacyRecord.setPortfolioId(88L);
        legacyRecord.setVisitCount(2);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null, legacyRecord);

        VisitRecordEntity record = service().recordOpen(
                portfolio(),
                1024L,
                "legacy-key",
                "WX_OPENID:digest-123",
                VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode(),
                "open-migrated-1"
        );

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper, times(2)).selectOne(any());
        verify(visitRecordEntityMapper, never()).insert(any(VisitRecordEntity.class));
        verify(visitRecordEntityMapper).updateById(recordCaptor.capture());
        assertThat(record.getId()).isEqualTo(44L);
        assertThat(recordCaptor.getValue().getVisitorId()).isEqualTo(1024L);
        assertThat(recordCaptor.getValue().getVisitorKey()).isEqualTo("legacy-key");
        assertThat(recordCaptor.getValue().getVisitCount()).isEqualTo(3);
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

    @Test
    void recordImageEventShouldKeepRawVisitorKeyInBusinessId() {
        String longVisitorKey = "a".repeat(64);
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey(longVisitorKey);
        record.setPortfolioId(88L);
        record.setViewWorkCount(0);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey(longVisitorKey);
        request.setEventType(VisitEventTypeDict.WORK_VIEWED.getCode());
        request.setWorkId(11L);
        request.setIdempotencyKey("image-long-1");

        service().recordEvent(portfolio(), request);

        ArgumentCaptor<String> businessIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(pointService).consumeWithMeterBusinessId(
                eq(7L),
                eq(PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode()),
                eq("PORTFOLIO_IMAGE"),
                businessIdCaptor.capture(),
                eq("88:" + longVisitorKey),
                eq(1),
                eq("image-long-1"),
                eq("访客查看作品集图片")
        );
        assertThat(businessIdCaptor.getValue()).isEqualTo("88:11:" + longVisitorKey);
    }

    @Test
    void recordImageEventShouldAccumulateByPortfolioVisitorAndKeepWorkInTransactionBusinessId() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setViewWorkCount(0);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("visitor-a");
        request.setEventType(VisitEventTypeDict.WORK_VIEWED.getCode());
        request.setWorkId(11L);
        request.setIdempotencyKey("image-1");

        service().recordEvent(portfolio(), request);

        verify(pointService).consumeWithMeterBusinessId(
                7L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(),
                "PORTFOLIO_IMAGE",
                "88:11:visitor-a",
                "88:visitor-a",
                1,
                "image-1",
                "访客查看作品集图片"
        );
    }

    @Test
    void recordEventShouldIgnoreRepeatedIdempotencyKey() {
        VisitEventEntity existingEvent = new VisitEventEntity();
        existingEvent.setId(91L);
        existingEvent.setIdempotencyKey("video-1");
        lenient().when(visitEventEntityMapper.selectOne(any())).thenReturn(existingEvent);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("visitor-a");
        request.setEventType(VisitEventTypeDict.VIDEO_PLAYED.getCode());
        request.setWorkId(11L);
        request.setDurationSeconds(18);
        request.setIdempotencyKey("video-1");

        service().recordEvent(portfolio(), request);

        verify(visitEventEntityMapper).selectOne(any());
        verify(visitRecordEntityMapper, never()).selectOne(any());
        verify(visitRecordEntityMapper, never()).updateById(any(VisitRecordEntity.class));
        verify(pointService, never()).consume(any(), any(), any(), any(), anyInt(), any(), any());
        verify(visitEventEntityMapper, never()).insert(any(VisitEventEntity.class));
    }

    @Test
    void recordEventShouldTreatDuplicateInsertAsIdempotentRaceWithoutSideEffects() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setViewWorkCount(0);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        when(visitEventEntityMapper.insert(any(VisitEventEntity.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'image-race-1' for key 'uk_visit_event_idempotency'"));
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("visitor-a");
        request.setEventType(VisitEventTypeDict.WORK_VIEWED.getCode());
        request.setWorkId(11L);
        request.setIdempotencyKey("image-race-1");

        assertThatCode(() -> service().recordEvent(portfolio(), request))
                .doesNotThrowAnyException();

        verify(visitRecordEntityMapper, never()).updateById(any(VisitRecordEntity.class));
        verify(pointService, never()).consumeWithMeterBusinessId(
                any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void recordScheduleQueryShouldStoreSlotMetadata() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setScheduleQueryCount(2);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);

        service().recordScheduleQuery(
                portfolio(),
                "visitor-a",
                LocalDate.of(2026, 7, 18),
                Map.of(
                        "componentKey", "c_schedule",
                        "displayMode", "MODAL_CALENDAR",
                        "slotDefinitionId", 12L,
                        "slotName", "午宴",
                        "available", true
                ),
                "schedule-submit-1"
        );

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getScheduleQueryCount()).isEqualTo(3);
        ArgumentCaptor<VisitEventEntity> eventCaptor = ArgumentCaptor.forClass(VisitEventEntity.class);
        verify(visitEventEntityMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(VisitEventTypeDict.SCHEDULE_QUERIED.getCode());
        assertThat(eventCaptor.getValue().getQueriedDate()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(eventCaptor.getValue().getMetadata()).contains("\"slotDefinitionId\":12");
        assertThat(eventCaptor.getValue().getMetadata()).contains("\"slotName\":\"午宴\"");
        assertThat(eventCaptor.getValue().getIdempotencyKey()).isEqualTo("schedule-submit-1");
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
