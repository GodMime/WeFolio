package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dto.VisitActivityTrackingDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitActivitySessionEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitActivitySessionEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioVisitService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 活动归属、双键恢复与累计高水位分支；数据库原子性另用真实事务测试覆盖。 */
class VisitActivitySessionTransactionServiceTest {
    /** 活动持久化替身。 */
    private final VisitActivitySessionEntityMapper sessions = mock(VisitActivitySessionEntityMapper.class);
    /** 汇总持久化替身。 */
    private final VisitRecordEntityMapper records = mock(VisitRecordEntityMapper.class);
    /** 事件持久化替身。 */
    private final VisitEventEntityMapper events = mock(VisitEventEntityMapper.class);
    /** 个人打开替身。 */
    private final PortfolioVisitService personal = mock(PortfolioVisitService.class);
    /** 团队打开替身。 */
    private final TeamPortfolioVisitService team = mock(TeamPortfolioVisitService.class);
    /** 被测事务逻辑。 */
    private final VisitActivitySessionTransactionService service =
            new VisitActivitySessionTransactionService(sessions, records, events, personal, team);

    /** 跨计费窗口恢复只读持久化会话，基准不取所有会话汇总。 */
    @Test
    void recoversBoundSessionWithoutOpenOrBillingEvenAfterTwoHours() {
        VisitActivitySessionEntity session = session();
        session.setCreatedAt(LocalDateTime.now().minusHours(3));
        session.setActiveDurationMs(45000L);
        session.setOpenIdempotencyKey("open-key");
        when(sessions.lockVisitor(7L)).thenReturn(7L);
        when(sessions.findOwnedForOpen(7L, PortfolioTypeDict.PERSONAL.getCode(), 8L, "client-key")).thenReturn(session);
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(10L);record.setVisitorId(7L);record.setPortfolioId(8L);
        record.setPortfolioType(PortfolioTypeDict.PERSONAL.getCode());record.setForegroundDurationMs(90000L);
        when(records.selectById(10L)).thenReturn(record);
        PortfolioEntity portfolio = new PortfolioEntity();portfolio.setId(8L);
        VisitActivityTrackingDto request = new VisitActivityTrackingDto();request.setClientSessionKey("client-key");
        var result = service.open(portfolio, PortfolioTypeDict.PERSONAL.getCode(), 7L, "visitor", null,
                "open-key", request);
        assertThat(result.session().getActiveDurationMs()).isEqualTo(45000L);
        verifyNoInteractions(personal, team, events);
        assertThatThrownBy(() -> service.open(portfolio, PortfolioTypeDict.PERSONAL.getCode(), 7L,
                "visitor", null, "different-open-key", request)).isInstanceOf(BusinessException.class);
    }

    /** 授权失败及超出服务端经过时长不得触碰高水位或汇总。 */
    @Test
    void rejectsUnauthorizedOrImplausibleDurationWithoutWrites() {
        when(sessions.lockOwned(1L, 7L, PortfolioTypeDict.PERSONAL.getCode(), 8L)).thenReturn(null);
        assertThatThrownBy(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, 1L, 0L))
                .isInstanceOf(BusinessException.class);
        when(sessions.lockOwned(1L, 7L, PortfolioTypeDict.PERSONAL.getCode(), 8L)).thenReturn(session());
        assertThatThrownBy(() -> service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, 1L, Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class);
        verify(sessions, never()).accept(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(records);
    }

    /** 首个零样本必须调用汇总初始化，不能按累计未变化早返回。 */
    @Test
    void zeroSampleInitializesNullableAggregate() {
        when(sessions.lockOwned(1L, 7L, PortfolioTypeDict.PERSONAL.getCode(), 8L)).thenReturn(session());
        when(sessions.accept(eq(1L), eq(0L), eq(0L), any())).thenReturn(1);
        when(records.incrementForegroundDuration(10L, 0L)).thenReturn(1);
        assertThat(service.accept(8L, PortfolioTypeDict.PERSONAL.getCode(), 7L, 1L, 0L)).isZero();
        verify(records).incrementForegroundDuration(10L, 0L);
        verify(records, never()).incrementCounters(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /** 构造已存在一分钟的会话。 */
    private VisitActivitySessionEntity session() {
        VisitActivitySessionEntity session = new VisitActivitySessionEntity();
        session.setId(1L);session.setVisitorId(7L);session.setPortfolioId(8L);session.setVisitRecordId(10L);
        session.setPortfolioType(PortfolioTypeDict.PERSONAL.getCode());session.setActiveDurationMs(0L);
        session.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return session;
    }
}
