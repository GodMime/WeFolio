package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 附件独立快照、两处重放共用的比较规则和普通任务容量保护。 */
class PortfolioAiReferenceSubmissionTest {
    /** 同一文件事实与临时回执分离，测试不包含真实对象。 */
    private static final String ID = "11111111111111111111111111111111";
    /** 合法且独立于 request_json 的任务快照。 */
    private static final String SNAPSHOT = """
            {"version":1,"items":[{"attachmentId":"11111111111111111111111111111111","referenceNo":"A1",
            "objectKey":"WFTEST/protfolio/ai-reference/11111111111111111111111111111111.jpg","mediaType":"IMAGE",
            "originalFileName":"参考.jpg","mimeType":"image/jpeg","sizeBytes":100,"durationMs":null,"width":10,"height":10,
            "contentVersion":{"etag":"fixture","lastModifiedEpochMs":1000000000000},"note":"配色"}]}
            """;

    /** 刷新凭证可以重放；换文件、编号、说明或省略原附件不能复用同一个任务键。 */
    @Test void replayComparesBusinessProjectionAcrossColumns() {
        PortfolioAiTaskTransactionService service = new PortfolioAiTaskTransactionService(null, null, null, null, null);
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        ObjectNode frozen = frozen(); task.setRequestJson(frozen.toString()); task.setReferenceAttachmentsJson(SNAPSHOT);
        service.requireSame(task, (ObjectNode) frozen.path("input"), items("refreshed-token"));
        ArrayNode changed = items("old-token"); ((ObjectNode) changed.get(0)).put("note", "布局");
        assertThatThrownBy(() -> service.requireSame(task, (ObjectNode) frozen.path("input"), changed))
                .hasMessage(PortfolioAiMessage.IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> service.requireSame(task, (ObjectNode) frozen.path("input")))
                .hasMessage(PortfolioAiMessage.IDEMPOTENCY_CONFLICT);
    }

    /** 附件已达到 maxActiveTasks-1 时拒绝接单，即使更宽的附件配置还剩名额。 */
    @Test void referenceAdmissionReservesOneGlobalSlotForOrdinaryTasks() {
        UserEntityMapper users = mock(UserEntityMapper.class);
        AiGenerationTaskEntityMapper tasks = mock(AiGenerationTaskEntityMapper.class);
        PortfolioAiReferenceService references = mock(PortfolioAiReferenceService.class);
        PortfolioAiProperties properties = new PortfolioAiProperties();
        properties.setReferenceEnabled(true); properties.setMaxActiveTasks(2); properties.setReferenceMaxActiveTasks(10);
        when(users.lockActiveUserById(101L)).thenReturn(101L);
        when(users.lockAiAdmissionGuard()).thenReturn(1L);
        when(tasks.selectCount(any())).thenReturn(0L, 1L, 1L);
        PortfolioAiTaskTransactionService service = new PortfolioAiTaskTransactionService(references, users, tasks, properties, mock(PortfolioAiFactsService.class));
        assertThatThrownBy(() -> service.submit(101L, "reference-request", frozen(), items("token")))
                .hasMessage(PortfolioAiMessage.ACTIVE_TASK);
        verifyNoInteractions(references);
        verify(tasks, never()).insert(any(AiGenerationTaskEntity.class));
    }

    /** 关闭接单后同键同附件仍先重放，不重新验签或再次接单。 */
    @Test void referenceReplayPrecedesSwitchAndReceiptValidation() {
        UserEntityMapper users = mock(UserEntityMapper.class);
        AiGenerationTaskEntityMapper tasks = mock(AiGenerationTaskEntityMapper.class);
        PortfolioAiReferenceService references = mock(PortfolioAiReferenceService.class);
        AiGenerationTaskEntity original = new AiGenerationTaskEntity();
        original.setRequestJson(frozen().toString()); original.setReferenceAttachmentsJson(SNAPSHOT);
        when(users.lockActiveUserById(101L)).thenReturn(101L);
        when(tasks.selectList(any())).thenReturn(List.of(original));
        PortfolioAiTaskTransactionService service = new PortfolioAiTaskTransactionService(references, users, tasks,
                new PortfolioAiProperties(), mock(PortfolioAiFactsService.class));
        assertThat(service.submit(101L, "original-key", frozen(), items("expired-receipt"))).isSameAs(original);
        verifyNoInteractions(references);
        verify(users, never()).lockAiAdmissionGuard();
        verify(tasks, never()).insert(any(AiGenerationTaskEntity.class));
    }

    /** 附件失败以白名单提示说明原因，不能把内部对象或供应商原文返回前端。 */
    @Test void referenceFailureHasActionableSafeMessage() {
        assertThat(PortfolioAiFailureDetails.task("REFERENCE_UNAVAILABLE").get("message")).contains("重新选择", "移除");
        assertThat(PortfolioAiFailureDetails.task("REFERENCE_ANALYSIS_FAILED").get("message")).contains("重试", "更换");
    }

    /** 新任务同时写两列，但临时令牌与附件业务列表不污染原冻结输入。 */
    @Test void acceptanceStoresImmutableReferenceSnapshotSeparately() {
        UserEntityMapper users = mock(UserEntityMapper.class);
        AiGenerationTaskEntityMapper tasks = mock(AiGenerationTaskEntityMapper.class);
        PortfolioAiReferenceService references = mock(PortfolioAiReferenceService.class);
        PortfolioAiProperties properties = new PortfolioAiProperties(); properties.setReferenceEnabled(true);
        when(users.lockActiveUserById(101L)).thenReturn(101L); when(users.lockAiAdmissionGuard()).thenReturn(1L);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(references.resolveForTask(eq("USER"), eq(101L), any(), isNull())).thenReturn(SNAPSHOT);
        PortfolioAiTaskTransactionService service = new PortfolioAiTaskTransactionService(references, users, tasks, properties, mock(PortfolioAiFactsService.class));
        AiGenerationTaskEntity accepted = service.submit(101L, "reference-request", frozen(), items("temporary-token"));
        assertThat(accepted.getReferenceAttachmentsJson()).isEqualTo(SNAPSHOT);
        assertThat(accepted.getRequestJson()).isEqualTo(frozen().toString()).doesNotContain("attachment", "temporary-token");
        assertThat(accepted.getPortfolioId()).isNull();
        verify(tasks).insert(accepted);
    }

    /** 最小归属冻结，只验证任务事务本身，不调用模型。 */
    private ObjectNode frozen() {
        return StrictAiJson.parseObject("{\"input\":{\"ownerType\":\"USER\",\"ownerId\":\"101\",\"portfolioId\":null}}", 1024, 4);
    }

    /** 前端只回传身份、编号、说明和回执。 */
    private ArrayNode items(String token) {
        ArrayNode values = PortfolioAiReferenceProtocol.NODES.arrayNode();
        values.addObject().put("attachmentId", ID).put("referenceNo", "A1").put("note", "配色").put("attachmentToken", token);
        return values;
    }
}
