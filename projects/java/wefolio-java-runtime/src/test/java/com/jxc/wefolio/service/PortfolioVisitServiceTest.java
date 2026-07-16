package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.BillingWindowScopeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** 积分滚动扣费窗口服务模拟 */
    @Mock
    private PointBillingWindowService pointBillingWindowService;

    @BeforeEach
    void setUp() {
        lenient().when(visitRecordEntityMapper.updateById(any(VisitRecordEntity.class))).thenReturn(1);
    }

    @Test
    void recordOpenShouldCreateVisitRecordAndConsumeByRollingWindow() {
        when(visitRecordEntityMapper.insert(any(VisitRecordEntity.class))).thenAnswer(invocation -> {
            VisitRecordEntity record = invocation.getArgument(0);
            record.setId(33L);
            return 1;
        });

        VisitRecordEntity record = service().recordOpen(
                portfolio(),
                1024L,
                "visitor-a",
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
        verify(pointBillingWindowService).consumeIfEligible(
                7L,
                1024L,
                PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode(),
                BillingWindowScopeDict.PORTFOLIO.getCode(),
                88L,
                "PORTFOLIO_OPEN",
                "88:1024",
                "PF_OPEN:open-1",
                "访客打开个人作品集"
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

    /** 打开事件缺少客户端幂等键时必须生成服务端窗口幂等键。 */
    @Test
    void recordOpenShouldGenerateWindowIdempotencyKeyWhenClientKeyIsMissing() {
        when(visitRecordEntityMapper.insert(any(VisitRecordEntity.class))).thenAnswer(invocation -> {
            VisitRecordEntity record = invocation.getArgument(0);
            record.setId(33L);
            return 1;
        });

        service().recordOpen(portfolio(), 1024L, "visitor-a", "WECHAT_SHARE_CARD", null);

        verify(pointBillingWindowService).consumeIfEligible(
                eq(7L),
                eq(1024L),
                eq(PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode()),
                eq(BillingWindowScopeDict.PORTFOLIO.getCode()),
                eq(88L),
                eq("PORTFOLIO_OPEN"),
                eq("88:1024"),
                startsWith("PF_OPEN:88:1024:"),
                eq("访客打开个人作品集")
        );
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

        service().recordOpen(portfolio(), 1024L, "visitor-a", "WECHAT_SHARE_CARD", "open-2");

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getPortfolioTitleSnapshot()).isEqualTo("林安婚礼司仪");
        assertThat(recordCaptor.getValue().getPortfolioShareCodeSnapshot()).isEqualTo("PF001");
        assertThat(recordCaptor.getValue().getVisitCount()).isEqualTo(2);
    }

    /** 乐观锁竞争失败时不得静默丢失打开次数。 */
    @Test
    void recordOpenShouldRejectOptimisticLockConflict() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setVisitCount(1);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        when(visitRecordEntityMapper.updateById(record)).thenReturn(0);

        assertThatThrownBy(() -> service().recordOpen(
                portfolio(),
                1024L,
                "visitor-a",
                "WECHAT_SHARE_CARD",
                "open-conflict-1"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("个人作品集访问记录保存失败");

        verifyNoInteractions(pointBillingWindowService);
        verify(visitEventEntityMapper, never()).insert(any(VisitEventEntity.class));
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

        service().recordEvent(portfolio(), 1024L, request);

        ArgumentCaptor<VisitRecordEntity> recordCaptor = ArgumentCaptor.forClass(VisitRecordEntity.class);
        verify(visitRecordEntityMapper).updateById(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getPlayVideoCount()).isEqualTo(1);
        verify(pointBillingWindowService).consumeIfEligible(
                7L,
                1024L,
                PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode(),
                BillingWindowScopeDict.WORK.getCode(),
                11L,
                "PORTFOLIO_VIDEO",
                "88:11:visitor-a",
                "video-1",
                "访客播放作品集视频"
        );
        verify(visitEventEntityMapper).insert(any(VisitEventEntity.class));
    }

    /** 乐观锁竞争失败时必须抛错，使事件与扣费事务一并回滚。 */
    @Test
    void recordEventShouldRejectOptimisticLockConflict() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setViewWorkCount(0);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        when(visitRecordEntityMapper.updateById(record)).thenReturn(0);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("visitor-a");
        request.setEventType(VisitEventTypeDict.WORK_VIEWED.getCode());
        request.setWorkId(11L);
        request.setIdempotencyKey("image-conflict-1");

        assertThatThrownBy(() -> service().recordEvent(portfolio(), 1024L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("个人作品集访问记录保存失败");
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

        service().recordEvent(portfolio(), 1024L, request);

        ArgumentCaptor<String> businessIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(pointBillingWindowService).consumeIfEligible(
                eq(7L),
                eq(1024L),
                eq(PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode()),
                eq(BillingWindowScopeDict.WORK.getCode()),
                eq(11L),
                eq("PORTFOLIO_IMAGE"),
                businessIdCaptor.capture(),
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

        service().recordEvent(portfolio(), 1024L, request);

        verify(pointBillingWindowService).consumeIfEligible(
                7L,
                1024L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(),
                BillingWindowScopeDict.WORK.getCode(),
                11L,
                "PORTFOLIO_IMAGE",
                "88:11:visitor-a",
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

        service().recordEvent(portfolio(), 1024L, request);

        verify(visitEventEntityMapper).selectOne(any());
        verify(visitRecordEntityMapper, never()).selectOne(any());
        verify(visitRecordEntityMapper, never()).updateById(any(VisitRecordEntity.class));
        verifyNoInteractions(pointBillingWindowService);
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

        assertThatCode(() -> service().recordEvent(portfolio(), 1024L, request))
                .doesNotThrowAnyException();

        verify(visitRecordEntityMapper, never()).updateById(any(VisitRecordEntity.class));
        verifyNoInteractions(pointBillingWindowService);
    }

    @Test
    void recordScheduleQueryShouldIgnoreRepeatedIdempotencyKey() {
        VisitEventEntity existingEvent = new VisitEventEntity();
        existingEvent.setId(91L);
        existingEvent.setIdempotencyKey("schedule-submit-1");
        when(visitEventEntityMapper.selectOne(any())).thenReturn(existingEvent);

        service().recordScheduleQuery(
                portfolio(),
                "visitor-a",
                LocalDate.of(2026, 7, 18),
                Map.of("slotDefinitionId", 12L),
                "schedule-submit-1"
        );

        verify(visitEventEntityMapper).selectOne(any());
        verify(visitRecordEntityMapper, never()).selectOne(any());
        verify(visitRecordEntityMapper, never()).updateById(any(VisitRecordEntity.class));
        verify(visitEventEntityMapper, never()).insert(any(VisitEventEntity.class));
    }

    @Test
    void recordScheduleQueryShouldKeepSnapshotRecordableWhenDuplicateInsertIsConcurrentConflict() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        record.setVisitorKey("visitor-a");
        record.setPortfolioId(88L);
        record.setScheduleQueryCount(2);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        when(visitEventEntityMapper.insert(any(VisitEventEntity.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'schedule-race-1' for key 'uk_visit_event_idempotency'"));

        PortfolioVisitService.ScheduleQueryRecordResult result = service().recordScheduleQuery(
                portfolio(),
                "visitor-a",
                LocalDate.of(2026, 7, 18),
                Map.of("slotDefinitionId", 12L),
                "schedule-race-1"
        );

        assertThat(result.isRecorded()).isFalse();
        assertThat(result.isSnapshotRecordable()).isTrue();
        assertThat(result.getRecord()).isSameAs(record);
        assertThat(result.getOccurredAt()).isNotNull();
        verify(visitRecordEntityMapper, never()).updateById(any(VisitRecordEntity.class));
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
        return new PortfolioVisitService(
                visitRecordEntityMapper,
                visitEventEntityMapper,
                pointBillingWindowService
        );
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
