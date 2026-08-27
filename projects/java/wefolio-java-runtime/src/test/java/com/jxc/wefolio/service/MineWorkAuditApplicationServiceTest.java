package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.MineWorkAuditResubmitResponse;
import com.jxc.wefolio.model.WorkManualAuditSubmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作品重审事务后人工审核通知编排测试。 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MineWorkAuditApplicationServiceTest {

    /** 重审事务服务模拟。 */
    @Mock
    private MineWorkAuditService auditService;

    /** 飞书通知器模拟。 */
    @Mock
    private FeishuWorkAuditNotifier notifier;

    /** 最终轮必须在事务服务返回后通知并保持原响应对象。 */
    @Test
    void finalRoundNotifiesAfterTransactionServiceAndReturnsExistingResponse() {
        MineWorkAuditResubmitResponse response = new MineWorkAuditResubmitResponse();
        WorkManualAuditSubmission submission = submission();
        when(auditService.resubmit(91L))
                .thenReturn(new MineWorkAuditService.ResubmitResult(response, submission));

        MineWorkAuditResubmitResponse actual = service().resubmit(91L);

        assertThat(actual).isSameAs(response);
        InOrder order = inOrder(auditService, notifier);
        order.verify(auditService).resubmit(91L);
        order.verify(notifier).notifySubmitted(submission);
    }

    /** 自动审核轮次没有通知快照时不得发送飞书。 */
    @Test
    void automatedRoundDoesNotNotify() {
        MineWorkAuditResubmitResponse response = new MineWorkAuditResubmitResponse();
        when(auditService.resubmit(91L))
                .thenReturn(new MineWorkAuditService.ResubmitResult(response, null));

        assertThat(service().resubmit(91L)).isSameAs(response);

        verify(notifier, never()).notifySubmitted(any());
    }

    /** 飞书通知失败不得改变已成功提交的重审响应，日志不得含异常消息。 */
    @Test
    void notificationFailureDoesNotChangeSuccessfulResubmitResponse(CapturedOutput output) {
        MineWorkAuditResubmitResponse response = new MineWorkAuditResubmitResponse();
        WorkManualAuditSubmission submission = submission();
        when(auditService.resubmit(91L))
                .thenReturn(new MineWorkAuditService.ResubmitResult(response, submission));
        doThrow(new IllegalStateException("secret-payload"))
                .when(notifier).notifySubmitted(submission);

        assertThat(service().resubmit(91L)).isSameAs(response);
        assertThat(output)
                .contains("作品人工审核飞书通知失败")
                .contains(submission.manualAuditNo())
                .contains("workId=91")
                .contains("round=3")
                .contains("event=SUBMITTED")
                .contains("exceptionType=IllegalStateException")
                .doesNotContain("secret-payload");
    }

    /** 创建被测重审应用服务。 */
    private MineWorkAuditApplicationService service() {
        return new MineWorkAuditApplicationService(auditService, notifier);
    }

    /** 构建最终轮通知快照。 */
    private WorkManualAuditSubmission submission() {
        return new WorkManualAuditSubmission(
                91L, 7L, "作品", "VIDEO", "user/work/video/demo.mp4",
                "WA20260827153042A7K2Q9", 3, 3,
                LocalDateTime.of(2026, 8, 26, 10, 0), List.of());
    }
}
