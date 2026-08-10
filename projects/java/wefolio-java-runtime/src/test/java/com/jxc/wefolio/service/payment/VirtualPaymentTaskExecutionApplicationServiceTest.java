package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dto.VirtualPaymentDebitTaskRecoveryResponse;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.service.AdminPointSecretValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信虚拟支付单任务执行应用服务测试。
 */
@ExtendWith(OutputCaptureExtension.class)
class VirtualPaymentTaskExecutionApplicationServiceTest {

    /** 赠送执行必须先校验密钥并原样映射处理结果。 */
    @Test
    void executeGiftOrderShouldValidateSecretMapOutcomeAndLogResult(CapturedOutput output) {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        PointGiftOrderProcessor processor = mock(PointGiftOrderProcessor.class);
        when(processor.process(17L)).thenReturn(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);
        VirtualPaymentTaskExecutionApplicationService service = service(
                validator, processor, mock(PointDebitTaskProcessor.class), mock(PointDebitTaskService.class));

        VirtualPaymentTaskExecutionResponse response = service.executeGiftOrder("secret", 17L);

        assertThat(response).isEqualTo(new VirtualPaymentTaskExecutionResponse(
                17L, "GIFT_ORDER", "SKIPPED_NOT_CLAIMABLE"));
        verify(validator).validate("secret");
        verify(processor).process(17L);
        assertThat(output).contains("taskType=GIFT_ORDER targetId=17 "
                        + "outcome=SKIPPED_NOT_CLAIMABLE")
                .doesNotContain("secret");
    }

    /** 赠送处理异常时也必须按本次调用记录失败结果，并保持原异常语义。 */
    @Test
    void executeGiftOrderShouldLogFailureResultAndRethrow(CapturedOutput output) {
        PointGiftOrderProcessor processor = mock(PointGiftOrderProcessor.class);
        when(processor.process(17L)).thenThrow(new IllegalStateException("处理失败"));
        VirtualPaymentTaskExecutionApplicationService service = service(
                mock(AdminPointSecretValidator.class), processor,
                mock(PointDebitTaskProcessor.class), mock(PointDebitTaskService.class));

        assertThatThrownBy(() -> service.executeGiftOrder("secret", 17L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("处理失败");

        assertThat(output).contains("taskType=GIFT_ORDER targetId=17 outcome=FAILED "
                        + "exceptionType=IllegalStateException")
                .doesNotContain("secret");
    }

    /** 扣币执行必须先校验密钥并映射已处理结果。 */
    @Test
    void executeDebitTaskShouldValidateSecretMapOutcomeAndLogResult(CapturedOutput output) {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        PointDebitTaskProcessor processor = mock(PointDebitTaskProcessor.class);
        when(processor.process(23L)).thenReturn(TaskExecutionOutcome.PROCESSED);
        VirtualPaymentTaskExecutionApplicationService service = service(
                validator, mock(PointGiftOrderProcessor.class), processor, mock(PointDebitTaskService.class));

        VirtualPaymentTaskExecutionResponse response = service.executeDebitTask("secret", 23L);

        assertThat(response).isEqualTo(new VirtualPaymentTaskExecutionResponse(
                23L, "DEBIT_TASK", "PROCESSED"));
        verify(validator).validate("secret");
        verify(processor).process(23L);
        assertThat(output).contains("taskType=DEBIT_TASK targetId=23 outcome=PROCESSED")
                .doesNotContain("secret");
    }

    /** 有待扣账户必须通过现有服务保障活动任务，job 不直接写任务表。 */
    @Test
    void recoverDebitTaskShouldReturnEnsuredTaskIdAndLogResult(CapturedOutput output) {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        PointDebitTaskService debitTaskService = mock(PointDebitTaskService.class);
        PointDebitTaskEntity task = new PointDebitTaskEntity();
        task.setId(31L);
        when(debitTaskService.ensureActiveTask(7L)).thenReturn(task);
        VirtualPaymentTaskExecutionApplicationService service = service(
                validator, mock(PointGiftOrderProcessor.class),
                mock(PointDebitTaskProcessor.class), debitTaskService);

        VirtualPaymentDebitTaskRecoveryResponse response = service.recoverDebitTask("secret", 7L);

        assertThat(response).isEqualTo(new VirtualPaymentDebitTaskRecoveryResponse(
                7L, 31L, "ACTIVE_TASK_ENSURED"));
        verify(validator).validate("secret");
        verify(debitTaskService).ensureActiveTask(7L);
        assertThat(output).contains("taskType=DEBIT_TASK_RECOVERY userId=7 taskId=31 "
                        + "outcome=ACTIVE_TASK_ENSURED")
                .doesNotContain("secret");
    }

    /** 无待扣账户时应返回明确的空任务结果。 */
    @Test
    void recoverDebitTaskShouldReportNoPendingDebit() {
        PointDebitTaskService debitTaskService = mock(PointDebitTaskService.class);
        when(debitTaskService.ensureActiveTask(7L)).thenReturn(null);
        VirtualPaymentTaskExecutionApplicationService service = service(
                mock(AdminPointSecretValidator.class), mock(PointGiftOrderProcessor.class),
                mock(PointDebitTaskProcessor.class), debitTaskService);

        VirtualPaymentDebitTaskRecoveryResponse response = service.recoverDebitTask("secret", 7L);

        assertThat(response).isEqualTo(new VirtualPaymentDebitTaskRecoveryResponse(
                7L, null, "NO_PENDING_DEBIT"));
    }

    /** runtime 功能关闭时，赠送入口不得领取或执行任务。 */
    @Test
    void disabledVirtualPaymentShouldSkipGiftOrderExecution(CapturedOutput output) {
        PointGiftOrderProcessor processor = mock(PointGiftOrderProcessor.class);
        VirtualPaymentTaskExecutionApplicationService service = service(
                mock(AdminPointSecretValidator.class), processor,
                mock(PointDebitTaskProcessor.class), mock(PointDebitTaskService.class), false);

        VirtualPaymentTaskExecutionResponse response = service.executeGiftOrder("secret", 17L);

        assertThat(response).isEqualTo(new VirtualPaymentTaskExecutionResponse(
                17L, "GIFT_ORDER", "SKIPPED_DISABLED"));
        verify(processor, never()).process(17L);
        assertThat(output).contains("taskType=GIFT_ORDER targetId=17 outcome=SKIPPED_DISABLED")
                .doesNotContain("secret");
    }

    /** runtime 功能关闭时，扣币入口不得领取或执行任务。 */
    @Test
    void disabledVirtualPaymentShouldSkipDebitTaskExecution() {
        PointDebitTaskProcessor processor = mock(PointDebitTaskProcessor.class);
        VirtualPaymentTaskExecutionApplicationService service = service(
                mock(AdminPointSecretValidator.class), mock(PointGiftOrderProcessor.class),
                processor, mock(PointDebitTaskService.class), false);

        VirtualPaymentTaskExecutionResponse response = service.executeDebitTask("secret", 23L);

        assertThat(response).isEqualTo(new VirtualPaymentTaskExecutionResponse(
                23L, "DEBIT_TASK", "SKIPPED_DISABLED"));
        verify(processor, never()).process(23L);
    }

    /** runtime 功能关闭时，恢复入口不得创建活动扣币任务。 */
    @Test
    void disabledVirtualPaymentShouldSkipDebitTaskRecovery() {
        PointDebitTaskService debitTaskService = mock(PointDebitTaskService.class);
        VirtualPaymentTaskExecutionApplicationService service = service(
                mock(AdminPointSecretValidator.class), mock(PointGiftOrderProcessor.class),
                mock(PointDebitTaskProcessor.class), debitTaskService, false);

        VirtualPaymentDebitTaskRecoveryResponse response = service.recoverDebitTask("secret", 7L);

        assertThat(response).isEqualTo(new VirtualPaymentDebitTaskRecoveryResponse(
                7L, null, "VIRTUAL_PAYMENT_DISABLED"));
        verify(debitTaskService, never()).ensureActiveTask(7L);
    }

    /** 构造应用服务。 */
    private VirtualPaymentTaskExecutionApplicationService service(
            AdminPointSecretValidator validator,
            PointGiftOrderProcessor giftProcessor,
            PointDebitTaskProcessor debitProcessor,
            PointDebitTaskService debitTaskService
    ) {
        return service(validator, giftProcessor, debitProcessor, debitTaskService, true);
    }

    /** 构造指定 runtime 功能开关的应用服务。 */
    private VirtualPaymentTaskExecutionApplicationService service(
            AdminPointSecretValidator validator,
            PointGiftOrderProcessor giftProcessor,
            PointDebitTaskProcessor debitProcessor,
            PointDebitTaskService debitTaskService,
            boolean enabled
    ) {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.setEnabled(enabled);
        return new VirtualPaymentTaskExecutionApplicationService(
                validator, giftProcessor, debitProcessor, debitTaskService, properties);
    }
}
