package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.AiGenerationTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用可控调度器验证媒体独立续约、排空、租约丢失和凭证关闭。 */
class PortfolioAiMediaLeaseHeartbeatTest {
    /** 租约短 SQL 替身。 */ private final AiGenerationTaskRepository repository = mock(AiGenerationTaskRepository.class);
    /** 手动驱动回调，不等待真实十秒。 */ private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    /** 可验证终止续约的句柄。 */ private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    /** 恢复窗口及总期限。 */ private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 被测独立租约调度器。 */ private final PortfolioAiMediaLeaseHeartbeat heartbeat = new PortfolioAiMediaLeaseHeartbeat(repository, properties, scheduler);
    /** 固定当前领取代次。 */ private final AiGenerationTaskEntity task = task();

    /** 测试后明确结束心跳线程资源。 */ @AfterEach void close() { heartbeat.close(); }

    /** 仅使用当前领取的 ID、版本和尝试次数。 */
    private AiGenerationTaskEntity task() {
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setId(1L); task.setVersion(2); task.setAttemptCount(1); return task;
    }

    /** 手动取出独立调度回调，间隔随配置验证。 */
    private Runnable callback(int interval) {
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(callback.capture(), eq((long) interval), eq((long) interval), eq(TimeUnit.SECONDS));
        return callback.getValue();
    }

    /** 调度准入排空时，既有媒体仍续约；结束后回调不能再刷新租约。 */
    @Test void drainingDoesNotSuspendHeartbeatAndClosingStopsFurtherRenewal() {
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        var lease = heartbeat.track(task); Runnable callback = callback(10);
        var lifecycle = new JobExecutionLifecycle();
        try (var permit = lifecycle.tryAcquire().orElseThrow()) {
            lifecycle.pause(Duration.ofMinutes(1));
            assertThat(lifecycle.tryAcquire()).isEmpty();
            callback.run();
            verify(repository, times(2)).renewMediaLease(eq(task), eq(90), eq(180));
        }
        lease.close(); lease.close(); callback.run();
        verify(future, times(1)).cancel(false);
        verify(repository, times(2)).renewMediaLease(any(), anyInt(), anyInt());
        assertThatThrownBy(lease::checkHeld).hasMessage(PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE);
    }

    /** 可调恢复窗口缩短时不会保留大于窗口的固定十秒间隔。 */
    @Test void heartbeatIntervalShrinksWithRecoveryWindow() {
        properties.setRecoverySeconds(9);
        when(repository.renewMediaLease(eq(task), eq(9), eq(180))).thenReturn(true);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        try (var lease = heartbeat.track(task)) { callback(3).run(); lease.checkHeld(); }
    }

    /** 取消或失权后的凭证永久无效，不因随后数据库返回有效而重新领取。 */
    @Test void lostLeaseCancelsHeartbeatAndNeverResurrects() {
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true, false, true);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        try (var lease = heartbeat.track(task)) {
            Runnable callback = callback(10); callback.run(); callback.run();
            assertThatThrownBy(lease::checkHeld).hasMessage(PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE);
            assertThat(lease.lostOwnership()).isTrue();
            verify(repository, times(2)).renewMediaLease(any(), anyInt(), anyInt());
        }
    }

    /** 短暂数据库故障下继续尝试，但每次仍由数据库过期边界决定是否有效。 */
    @Test void transientHeartbeatFailureDoesNotBypassNextDatabaseLeaseCheck() {
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true)
                .thenThrow(new IllegalStateException("测试故障")) .thenReturn(false);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        try (var lease = heartbeat.track(task)) {
            Runnable callback = callback(10); callback.run(); callback.run();
            assertThatThrownBy(lease::checkHeld).hasMessage(PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE);
        }
    }

    /** 初次同步确认不成功，不能登记独立心跳或开始媒体执行。 */
    @Test void staleLeaseCannotStartHeartbeat() {
        assertThatThrownBy(() -> heartbeat.track(task)).hasMessage(PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE);
        verifyNoInteractions(scheduler);
    }

    /** 关闭执行服务会立即使旧凭证失效，数据库心跳新鲜也不能在停机后继续付费。 */
    @Test void stoppingHeartbeatInvalidatesCurrentLeaseDespiteFreshDatabaseRow() {
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        try (var lease = heartbeat.track(task)) {
            Runnable callback = callback(10); heartbeat.close(); callback.run();
            assertThatThrownBy(lease::checkHeld).hasMessage(PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE);
            verify(repository, times(1)).renewMediaLease(any(), anyInt(), anyInt());
        }
    }

    /** SDK 保留线程中断标志时，阶段凭证先停止，不尝试续约或发送下一次请求。 */
    @Test void interruptedThreadCannotRenewCurrentMediaLease() {
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        try (var lease = heartbeat.track(task)) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(lease::checkHeld).hasMessage(PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE);
            verify(repository, times(1)).renewMediaLease(any(), anyInt(), anyInt());
        } finally { Thread.interrupted(); }
    }
}
