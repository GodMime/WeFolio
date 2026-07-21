package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.BillingWindowScopeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointBillingWindowEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointBillingWindowEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 访客积分滚动扣费窗口服务测试。
 */
@ExtendWith(MockitoExtension.class)
class PointBillingWindowServiceTest {

    /** 窗口 Mapper 模拟 */
    @Mock
    private PointBillingWindowEntityMapper mapper;

    /** 积分服务模拟 */
    @Mock
    private PointService pointService;

    /** 公开扣费方法必须加入事务并在异常时回滚。 */
    @Test
    void consumeIfEligibleUsesRollbackTransaction() throws NoSuchMethodException {
        Method method = PointBillingWindowService.class.getMethod(
                "consumeIfEligible",
                Long.class, Long.class, String.class, String.class, Long.class,
                String.class, String.class, String.class, String.class);

        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    /** 首次创建窗口后必须扣费并写回账户、流水和数据库时间。 */
    @Test
    void firstEligibleRequestChargesAndAdvancesWindow() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 16, 10, 50);
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(1);
        when(mapper.selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L))
                .thenReturn(window(null));
        when(mapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(pointService.consume(
                7L, sceneVideo(), "PORTFOLIO_VIDEO", "88:11:visitor-a",
                1, "video-1", "访客播放作品集视频"))
                .thenReturn(mutation(20L, 91L));
        when(mapper.updateCharged(31L, 20L, 91L, databaseNow)).thenReturn(1);

        BillingWindowResult result = consumeVideo("video-1");

        assertThat(result).isEqualTo(BillingWindowResult.CHARGED);
        ArgumentCaptor<PointBillingWindowEntity> identityCaptor =
                ArgumentCaptor.forClass(PointBillingWindowEntity.class);
        verify(mapper).insertIgnore(identityCaptor.capture());
        assertThat(identityCaptor.getValue()).satisfies(identity -> {
            assertThat(identity.getUserId()).isEqualTo(7L);
            assertThat(identity.getVisitorId()).isEqualTo(1024L);
            assertThat(identity.getSceneCode()).isEqualTo(sceneVideo());
            assertThat(identity.getScopeType()).isEqualTo(scopeWork());
            assertThat(identity.getScopeId()).isEqualTo(11L);
        });
    }

    /** 已存在窗口也必须按完整唯一键加锁后处理。 */
    @Test
    void existingWindowFromInsertIgnoreStillLocksAndCharges() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 16, 12, 50);
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(0);
        when(mapper.selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L))
                .thenReturn(window(LocalDateTime.of(2026, 7, 16, 10, 50)));
        when(mapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(pointService.consume(any(), any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(mutation(20L, 92L));
        when(mapper.updateCharged(31L, 20L, 92L, databaseNow)).thenReturn(1);

        assertThat(consumeVideo("video-2")).isEqualTo(BillingWindowResult.CHARGED);

        verify(mapper).selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L);
    }

    /** 距上次扣费不足 120 分钟时只跳过扣费，不推进窗口。 */
    @Test
    void requestBeforeRollingBoundarySkipsWithoutPointMutation() {
        LocalDateTime lastChargedAt = LocalDateTime.of(2026, 7, 16, 10, 0, 0, 1_000_000);
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(0);
        when(mapper.selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L))
                .thenReturn(window(lastChargedAt));
        when(mapper.selectCurrentTimestamp()).thenReturn(LocalDateTime.of(2026, 7, 16, 12, 0));

        assertThat(consumeVideo("video-2"))
                .isEqualTo(BillingWindowResult.SKIPPED_WITHIN_WINDOW);

        verifyNoInteractions(pointService);
        verify(mapper, never()).updateCharged(any(), any(), any(), any());
    }

    /** 满 120 分钟的边界请求必须允许重新扣费。 */
    @Test
    void requestAtRollingBoundaryChargesAgain() {
        LocalDateTime lastChargedAt = LocalDateTime.of(2026, 7, 16, 10, 50);
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 16, 12, 50);
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(0);
        when(mapper.selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L))
                .thenReturn(window(lastChargedAt));
        when(mapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(pointService.consume(any(), any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(mutation(20L, 93L));
        when(mapper.updateCharged(31L, 20L, 93L, databaseNow)).thenReturn(1);

        assertThat(consumeVideo("video-3")).isEqualTo(BillingWindowResult.CHARGED);
    }

    /** 无效参数和场景作用域不匹配必须在写数据库前拒绝。 */
    @Test
    void invalidIdentityOrScopeIsRejectedBeforeInsertIgnore() {
        assertThatThrownBy(() -> service().consumeIfEligible(
                7L, 1024L,
                PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode(), scopeWork(), 11L,
                "PORTFOLIO_OPEN", "88:1024", "open-1", "访客打开个人作品集"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分扣费窗口作用域与场景不匹配");

        assertThatThrownBy(() -> service().consumeIfEligible(
                7L, 0L, sceneVideo(), scopeWork(), 11L,
                "PORTFOLIO_VIDEO", "88:11:visitor-a", "video-1", "访客播放作品集视频"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访客 ID 必须为正数");

        verifyNoInteractions(mapper, pointService);
    }

    /** INSERT IGNORE 异常影响行数不得继续按免费或已有窗口处理。 */
    @Test
    void unexpectedInsertAffectedRowsThrowsWindowError() {
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(2);

        assertThatThrownBy(() -> consumeVideo("video-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PointMessage.BILLING_WINDOW_ABNORMAL_MESSAGE);

        verify(mapper, never()).selectForUpdateByUniqueKey(any(), any(), any(), any(), any());
        verifyNoInteractions(pointService);
    }

    /** 占位后查询不到活动唯一键必须抛出统一异常。 */
    @Test
    void missingWindowAfterInsertIgnoreThrowsWindowError() {
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(0);
        when(mapper.selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L))
                .thenReturn(null);

        assertThatThrownBy(() -> consumeVideo("video-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PointMessage.BILLING_WINDOW_ABNORMAL_MESSAGE);

        verify(mapper, never()).selectCurrentTimestamp();
        verifyNoInteractions(pointService);
    }

    /** 扣费响应缺少流水或窗口更新失败时必须整体失败。 */
    @Test
    void invalidMutationOrFailedWindowUpdateThrowsWindowError() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 16, 10, 50);
        when(mapper.insertIgnore(any(PointBillingWindowEntity.class))).thenReturn(1);
        when(mapper.selectForUpdateByUniqueKey(7L, sceneVideo(), 1024L, scopeWork(), 11L))
                .thenReturn(window(null));
        when(mapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(pointService.consume(any(), any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(mutation(20L, 91L));
        when(mapper.updateCharged(31L, 20L, 91L, databaseNow)).thenReturn(0);

        assertThatThrownBy(() -> consumeVideo("video-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PointMessage.BILLING_WINDOW_ABNORMAL_MESSAGE);
    }

    /** 构造被测服务。 */
    private PointBillingWindowService service() {
        return new PointBillingWindowService(mapper, pointService);
    }

    /** 执行固定视频窗口请求。 */
    private BillingWindowResult consumeVideo(String idempotencyKey) {
        return service().consumeIfEligible(
                7L, 1024L, sceneVideo(), scopeWork(), 11L,
                "PORTFOLIO_VIDEO", "88:11:visitor-a", idempotencyKey, "访客播放作品集视频");
    }

    /** 构造窗口。 */
    private PointBillingWindowEntity window(LocalDateTime lastChargedAt) {
        PointBillingWindowEntity window = new PointBillingWindowEntity();
        window.setId(31L);
        window.setUserId(7L);
        window.setVisitorId(1024L);
        window.setSceneCode(sceneVideo());
        window.setScopeType(scopeWork());
        window.setScopeId(11L);
        window.setLastChargedAt(lastChargedAt);
        return window;
    }

    /** 构造成功扣费响应。 */
    private PointMutationResponse mutation(Long accountId, Long transactionId) {
        PointMutationResponse response = new PointMutationResponse();
        response.setAccountId(accountId);
        response.setTransactionId(transactionId);
        response.setCharged(true);
        return response;
    }

    /** 视频场景编码。 */
    private String sceneVideo() {
        return PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode();
    }

    /** 作品作用域编码。 */
    private String scopeWork() {
        return BillingWindowScopeDict.WORK.getCode();
    }
}
