package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.FeedbackStatusUpdateRequest;
import com.jxc.wefolio.dto.FeedbackStatusUpdateResponse;
import com.jxc.wefolio.dto.MineFeedbackCreateRequest;
import com.jxc.wefolio.dto.MineFeedbackDetailResponse;
import com.jxc.wefolio.entity.FeedbackEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 问题反馈应用服务，编排事务后通知和状态更新。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineFeedbackApplicationService {

    /** 新建反馈通知事件。 */
    private static final String CREATED_EVENT = "CREATED";

    /** 追加反馈通知事件。 */
    private static final String APPENDED_EVENT = "APPENDED";

    /** 反馈事务服务。 */
    private final FeedbackTransactionService feedbackTransactionService;

    /** 反馈查询服务。 */
    private final MineFeedbackService mineFeedbackService;

    /** 飞书反馈通知器。 */
    private final FeishuFeedbackNotifier notifier;

    /**
     * 创建当前用户的问题反馈并在事务完成后发送一次飞书通知。
     *
     * @param request 创建请求
     * @return 创建后的完整反馈详情
     */
    public MineFeedbackDetailResponse create(MineFeedbackCreateRequest request) {
        FeedbackTransactionService.MutationResult result = feedbackTransactionService.create(request);
        if (result.changed()) {
            notifyCreatedSafely(result);
        }
        return mineFeedbackService.detail(result.feedback().getId());
    }

    /**
     * 追加当前用户的问题反馈轮次并在事务完成后发送一次飞书通知。
     *
     * @param feedbackId 反馈 ID
     * @param request 追加请求
     * @return 追加后的完整反馈详情
     */
    public MineFeedbackDetailResponse append(Long feedbackId, MineFeedbackCreateRequest request) {
        FeedbackTransactionService.MutationResult result =
                feedbackTransactionService.append(feedbackId, request);
        if (result.changed()) {
            notifyAppendedSafely(result);
        }
        return mineFeedbackService.detail(result.feedback().getId());
    }

    /**
     * 更新问题状态。
     *
     * @param feedbackNo 对外问题反馈编号
     * @param request 状态更新请求
     * @return 不含用户身份和原始轮次 JSON 的最小响应
     */
    public FeedbackStatusUpdateResponse updateStatus(
            String feedbackNo,
            FeedbackStatusUpdateRequest request
    ) {
        FeedbackTransactionService.MutationResult result =
                feedbackTransactionService.updateStatus(feedbackNo, request);
        FeedbackEntity feedback = result.feedback();
        FeedbackStatusUpdateResponse response = new FeedbackStatusUpdateResponse();
        response.setFeedbackNo(feedback.getFeedbackNo());
        response.setStatus(feedback.getStatus());
        response.setFeedbackResult(feedback.getFeedbackResult());
        response.setFeedbackResultAt(feedback.getFeedbackResultAt());
        response.setChanged(result.changed());
        return response;
    }

    /** 新建通知异常只记录非敏感摘要，不影响已提交业务结果。 */
    private void notifyCreatedSafely(FeedbackTransactionService.MutationResult result) {
        try {
            notifier.notifyCreated(result);
        } catch (RuntimeException exception) {
            logNotificationException(CREATED_EVENT, result, exception);
        }
    }

    /** 追加通知异常只记录非敏感摘要，不影响已提交业务结果。 */
    private void notifyAppendedSafely(FeedbackTransactionService.MutationResult result) {
        try {
            notifier.notifyAppended(result);
        } catch (RuntimeException exception) {
            logNotificationException(APPENDED_EVENT, result, exception);
        }
    }

    /** 记录不含用户内容和远端密钥的通知编排异常摘要。 */
    private void logNotificationException(
            String event,
            FeedbackTransactionService.MutationResult result,
            RuntimeException exception
    ) {
        log.error("飞书反馈通知失败 feedbackNo={} roundNo={} event={} exceptionType={}",
                result.feedback().getFeedbackNo(), result.submittedRound().getRoundNo(), event,
                exception.getClass().getSimpleName());
    }
}
