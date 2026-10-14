package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 独立验证跨阶段费用凭证、并发结算与费用硬上限。 */
class VideoSummaryRunBudgetTest {
    /** 用量对应一分，便于直接核对每次向上取整结果。 */
    private static final PortfolioAiModelClient.Completion ONE_FEN =
            new PortfolioAiModelClient.Completion(null, 20L, 10L);

    /** 未发送或其它运行的凭证不得凭空结算，重复响应也不能重复退款。 */
    @Test void onlySentOwnedReservationCanBeSettledOnce() {
        var run = new VideoSummaryService.RunBudget(100, 500);
        var otherRun = new VideoSummaryService.RunBudget(100, 500);
        var reservation = run.reserve(86);
        run.recordUsage(reservation, ONE_FEN);
        assertEquals(86, run.reservedCostFen());
        assertFalse(otherRun.sent(reservation));
        assertTrue(run.sent(reservation)); assertFalse(run.sent(reservation));
        otherRun.recordUsage(reservation, ONE_FEN);
        assertEquals(0, otherRun.knownCostFen());
        run.recordUsage(reservation, ONE_FEN);
        run.recordUsage(reservation, ONE_FEN);
        assertEquals(1, run.knownCostFen()); assertEquals(1, run.reservedCostFen());
        assertEquals(30, run.knownTokens()); assertEquals(0, run.unknownUsageCount());
    }

    /** 请求可乱序完成，只释放各自预留，已知费用加未知费用继续挡住上限。 */
    @Test void outOfOrderSettlementPreservesOtherOutstandingReservation() {
        var run = new VideoSummaryService.RunBudget(100, 100);
        var first = run.reserve(20);
        var second = run.reserve(80);
        assertNull(run.reserve(1));
        assertTrue(run.sent(first)); assertTrue(run.sent(second));
        run.recordUsage(second, ONE_FEN);
        assertEquals(21, run.reservedCostFen());
        var third = run.reserve(79);
        assertNotNull(third); assertNull(run.reserve(1));
        run.recordUsage(first, ONE_FEN);
        assertEquals(81, run.reservedCostFen());
        assertEquals(2, run.knownCostFen()); assertEquals(2, run.sentRequests());
        assertFalse(run.stopped());
    }

    /** 缺失或负数计量保留整个预留，不能把未知请求视为免费。 */
    @Test void missingOrNegativeUsageNeverReleasesReservation() {
        List<PortfolioAiModelClient.Completion> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new PortfolioAiModelClient.Completion(null, null, 1L));
        invalid.add(new PortfolioAiModelClient.Completion(null, 1L, null));
        invalid.add(new PortfolioAiModelClient.Completion(null, -1L, 1L));
        invalid.add(new PortfolioAiModelClient.Completion(null, 1L, -1L));
        var run = new VideoSummaryService.RunBudget(100, 500);
        for (var value : invalid) {
            var reservation = run.reserve(86);
            assertTrue(run.sent(reservation));
            run.recordUsage(reservation, value);
        }
        assertEquals(430, run.reservedCostFen()); assertEquals(5, run.unknownUsageCount());
        assertEquals(0, run.knownCostFen()); assertNull(run.reserve(86));
    }

    /** 多线程准入和重复结算仍遵守同一硬预算，结算不会出现重复退款。 */
    @Test void concurrentReservationAndSettlementNeverExceedOrDuplicateBudget() throws Exception {
        var run = new VideoSummaryService.RunBudget(100, 500);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VideoSummaryService.RunBudget.Reservation>> attempts = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                attempts.add(executor.submit(() -> { start.await(); return run.reserve(86); }));
            }
            start.countDown();
            List<VideoSummaryService.RunBudget.Reservation> accepted = new ArrayList<>();
            for (var attempt : attempts) {
                var reservation = attempt.get();
                if (reservation != null) { accepted.add(reservation); }
            }
            assertEquals(5, accepted.size()); assertEquals(430, run.reservedCostFen());
            List<Future<?>> settlements = new ArrayList<>();
            for (var reservation : accepted) {
                assertTrue(run.sent(reservation));
                for (int duplicate = 0; duplicate < 3; duplicate++) {
                    settlements.add(executor.submit(() -> run.recordUsage(reservation, ONE_FEN)));
                }
            }
            for (var settlement : settlements) { settlement.get(); }
            assertEquals(5, run.knownCostFen()); assertEquals(5, run.reservedCostFen());
            assertEquals(150, run.knownTokens()); assertEquals(0, run.unknownUsageCount());
            assertNotNull(run.reserve(86)); assertEquals(91, run.reservedCostFen());
        }
    }
}
