package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.dto.FeedbackStatusUpdateRequest;
import com.jxc.wefolio.dto.FeedbackStatusUpdateResponse;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.dto.MineFeedbackDetailResponse;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 当前用户问题反馈应用服务测试。 */
@ExtendWith(MockitoExtension.class)
class MineFeedbackApplicationServiceTest {

    /** 反馈事务服务模拟。 */
    @Mock
    private FeedbackTransactionService feedbackTransactionService;

    /** 反馈查询服务模拟。 */
    @Mock
    private MineFeedbackService mineFeedbackService;

    /** 飞书通知器模拟。 */
    @Mock
    private FeishuFeedbackNotifier notifier;

    /** 内部接口密钥校验器模拟。 */
    @Mock
    private AdminPointSecretValidator adminPointSecretValidator;

    /** 待测试应用服务。 */
    private MineFeedbackApplicationService applicationService;

    /** 每个用例创建独立应用服务。 */
    @BeforeEach
    void setUp() {
        applicationService = new MineFeedbackApplicationService(
                feedbackTransactionService,
                mineFeedbackService,
                notifier,
                adminPointSecretValidator);
    }

    /** 新建发生写入时必须在事务返回后通知一次并返回详情。 */
    @Test
    void createChangedNotifiesOnceAfterTransactionAndReturnsDetail() {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        FeedbackTransactionService.MutationResult mutation = mutation(true);
        MineFeedbackDetailResponse detail = new MineFeedbackDetailResponse();
        when(feedbackTransactionService.create(request)).thenReturn(mutation);
        when(mineFeedbackService.detail(91L)).thenReturn(detail);

        MineFeedbackDetailResponse result = applicationService.create(request);

        assertThat(result).isSameAs(detail);
        InOrder order = inOrder(feedbackTransactionService, notifier, mineFeedbackService);
        order.verify(feedbackTransactionService).create(request);
        order.verify(notifier).notifyCreated(mutation);
        order.verify(mineFeedbackService).detail(91L);
    }

    /** 新建幂等重放不得发送飞书通知，但仍返回首次问题详情。 */
    @Test
    void createUnchangedSkipsNotificationAndReturnsDetail() {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        FeedbackTransactionService.MutationResult mutation = mutation(false);
        MineFeedbackDetailResponse detail = new MineFeedbackDetailResponse();
        when(feedbackTransactionService.create(request)).thenReturn(mutation);
        when(mineFeedbackService.detail(91L)).thenReturn(detail);

        assertThat(applicationService.create(request)).isSameAs(detail);

        verify(notifier, never()).notifyCreated(mutation);
        verify(mineFeedbackService).detail(91L);
    }

    /** 飞书通知器意外抛出异常也不得影响新建成功详情。 */
    @Test
    void createNotifierExceptionStillReturnsDetail() {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        FeedbackTransactionService.MutationResult mutation = mutation(true);
        MineFeedbackDetailResponse detail = new MineFeedbackDetailResponse();
        when(feedbackTransactionService.create(request)).thenReturn(mutation);
        doThrow(new IllegalStateException("unexpected")).when(notifier).notifyCreated(mutation);
        when(mineFeedbackService.detail(91L)).thenReturn(detail);

        assertThat(applicationService.create(request)).isSameAs(detail);
    }

    /** 追加发生写入时必须通知一次并返回详情。 */
    @Test
    void appendChangedNotifiesOnceAndReturnsDetail() {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        FeedbackTransactionService.MutationResult mutation = mutation(true);
        MineFeedbackDetailResponse detail = new MineFeedbackDetailResponse();
        when(feedbackTransactionService.append(91L, request)).thenReturn(mutation);
        when(mineFeedbackService.detail(91L)).thenReturn(detail);

        assertThat(applicationService.append(91L, request)).isSameAs(detail);

        InOrder order = inOrder(feedbackTransactionService, notifier, mineFeedbackService);
        order.verify(feedbackTransactionService).append(91L, request);
        order.verify(notifier).notifyAppended(mutation);
        order.verify(mineFeedbackService).detail(91L);
    }

    /** 追加幂等重放不得通知。 */
    @Test
    void appendUnchangedSkipsNotification() {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        FeedbackTransactionService.MutationResult mutation = mutation(false);
        MineFeedbackDetailResponse detail = new MineFeedbackDetailResponse();
        when(feedbackTransactionService.append(91L, request)).thenReturn(mutation);
        when(mineFeedbackService.detail(91L)).thenReturn(detail);

        assertThat(applicationService.append(91L, request)).isSameAs(detail);

        verify(notifier, never()).notifyAppended(mutation);
    }

    /** 追加通知异常不得影响详情响应。 */
    @Test
    void appendNotifierFailureOrExceptionStillReturnsDetail() {
        MineFeedbackCreateRequest request = new MineFeedbackCreateRequest();
        FeedbackTransactionService.MutationResult thrownMutation = mutation(true);
        thrownMutation.feedback().setId(92L);
        thrownMutation.feedback().setFeedbackNo("FB1123456789abcdef0123456789abcdef");
        MineFeedbackDetailResponse secondDetail = new MineFeedbackDetailResponse();
        when(feedbackTransactionService.append(91L, request)).thenReturn(thrownMutation);
        doThrow(new IllegalStateException("unexpected")).when(notifier).notifyAppended(thrownMutation);
        when(mineFeedbackService.detail(92L)).thenReturn(secondDetail);

        assertThat(applicationService.append(91L, request)).isSameAs(secondDetail);
        verify(notifier).notifyAppended(thrownMutation);
    }

    /** 内部密钥校验必须先于状态事务，并返回不含用户或 JSON 的最小响应。 */
    @Test
    void updateStatusValidatesThenReturnsMinimalResponse() {
        FeedbackStatusUpdateRequest request = statusRequest();
        FeedbackTransactionService.MutationResult mutation = mutation(true);
        mutation.feedback().setFeedbackResult("请补充最新录屏");
        mutation.feedback().setFeedbackResultAt(LocalDateTime.of(2026, 8, 25, 12, 0));
        when(feedbackTransactionService.updateStatus(
                "FB0123456789abcdef0123456789abcdef", request)).thenReturn(mutation);

        FeedbackStatusUpdateResponse response = applicationService.updateStatus(
                "admin-secret", "FB0123456789abcdef0123456789abcdef", request);

        assertThat(response.getFeedbackNo()).isEqualTo("FB0123456789abcdef0123456789abcdef");
        assertThat(response.getStatus()).isEqualTo(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());
        assertThat(response.getFeedbackResult()).isEqualTo("请补充最新录屏");
        assertThat(response.getFeedbackResultAt()).isEqualTo(LocalDateTime.of(2026, 8, 25, 12, 0));
        assertThat(response.isChanged()).isTrue();
        assertThat(FeedbackStatusUpdateResponse.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("userId", "roundsJson", "feedback");
        InOrder order = inOrder(adminPointSecretValidator, feedbackTransactionService);
        order.verify(adminPointSecretValidator).validate("admin-secret");
        order.verify(feedbackTransactionService).updateStatus(
                "FB0123456789abcdef0123456789abcdef", request);
    }

    /** 内部密钥无效时不得进入反馈状态事务。 */
    @Test
    void invalidAdminSecretFailsBeforeStatusTransaction() {
        doThrow(new BusinessException("后台积分密钥无效"))
                .when(adminPointSecretValidator).validate("wrong-secret");

        assertThatThrownBy(() -> applicationService.updateStatus(
                "wrong-secret", "FB0123456789abcdef0123456789abcdef", statusRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("后台积分密钥无效");

        verifyNoInteractions(feedbackTransactionService);
    }

    /** 构造内部状态更新请求。 */
    private FeedbackStatusUpdateRequest statusRequest() {
        FeedbackStatusUpdateRequest request = new FeedbackStatusUpdateRequest();
        request.setStatus(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());
        request.setFeedbackResult("请补充最新录屏");
        return request;
    }

    /** 构造指定变更标记的事务结果。 */
    private FeedbackTransactionService.MutationResult mutation(boolean changed) {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setId(91L);
        feedback.setFeedbackNo("FB0123456789abcdef0123456789abcdef");
        feedback.setUserId(7L);
        feedback.setStatus(FeedbackStatusDict.WAITING_FOLLOW_UP.getCode());
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(1);
        return new FeedbackTransactionService.MutationResult(feedback, round, changed);
    }
}
