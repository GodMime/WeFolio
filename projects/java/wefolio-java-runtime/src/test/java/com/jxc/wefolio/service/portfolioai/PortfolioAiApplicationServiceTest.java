package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import com.jxc.wefolio.message.PortfolioTextMessage;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 任务应用边界：权限、留存、幂等、基准冲突、无改动和取消代次。 */
@ExtendWith(MockitoExtension.class)
class PortfolioAiApplicationServiceTest {
    /** 任务读写模拟，验证不会写入作品集。 */
    @Mock private AiGenerationTaskEntityMapper tasks;
    /** 提交短事务模拟。 */
    @Mock private PortfolioAiTaskTransactionService transactions;
    /** 来源授权模拟。 */
    @Mock private PortfolioAiFactsService facts;
    /** 完整普通业务边界模拟；真实组件校验另由十五类组件测试覆盖。 */
    @Mock private PortfolioAiConfigService configs;
    /** 独立分享图片快照模拟；没有来源图片的候选仍返回同一冻结请求。 */
    @Mock private PortfolioAiShareImageService shareImages;
    /** 当前运行配置。 */
    private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 待测应用服务。 */
    private PortfolioAiApplicationService service;

    /** 创建请求身份，不依赖真实令牌或环境配置。 */
    @BeforeEach void prepare() {
        AuthContextHolder.set(new AuthContext(101L, "test-token"));
        lenient().when(shareImages.snapshot(anyLong(), any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        service = new PortfolioAiApplicationService(properties, tasks, transactions, facts, configs, mock(PortfolioAiMediaPlanService.class), shareImages);
    }
    /** 清理线程身份，避免影响相邻测试。 */
    @AfterEach void clear() { AuthContextHolder.clear(); }
    /** 解析安全测试 JSON。 */
    private ObjectNode node(String text) { return StrictAiJson.parseObject(text, 262144, 16); }
    /** 最小合法 HTTP 修改请求，可在首次保存前无 ID 编辑。 */
    private ObjectNode http() {
        return node("""
                {"ownerType":"USER","ownerId":"101","portfolioId":null,"mode":"MODIFY_PAGE","targetMenuKey":"__root__",
                 "baseDraftRevision":0,"localEditRevision":3,"editorSchemaRevision":14,
                 "aiContractVersion":"ai-portfolio-v1","clientCapabilities":{"portfolioRemoteFont":1},
                 "aiContext":{"version":1,"nextSourceNo":1,"sources":[]},"prompt":"私密用户描述不应回传",
                 "idempotencyKey":"test-key","baseConfig":{"schemaVersion":"standard-personal-v1","editorSchemaRevision":14,
                 "fonts":{},"components":[{"componentKey":"intro","componentType":"TEXT_SECTION","sortOrder":1000,
                 "enabled":true,"config":{"content":"原始介绍"}}]}}
                """);
    }
    /** 能力只公布新建和整体修改，不再暴露已取消的编辑模式。 */
    @Test void capabilitiesExposeOnlyCreateAndPageModification() {
        assertThat(service.capabilities().get("modes")).isEqualTo(List.of("CREATE", "MODIFY_PAGE"));
    }

    /** 已取消的模式以及组件级目标均不属于当前请求结构。 */
    @Test void removedModesAndComponentTargetAreRejectedBeforeOwnerOrTaskAccess() {
        for (String mode : List.of("MODIFY_COMPONENT", "REGENERATE_PAGE")) {
            ObjectNode input = http(); input.remove("targetComponentKey"); input.put("mode", mode);
            assertThatThrownBy(() -> service.submit(input.toString())).isInstanceOf(BusinessException.class)
                    .hasMessage(PortfolioAiMessage.INVALID_INPUT);
        }
        ObjectNode input = http(); input.put("targetComponentKey", "intro");
        assertThatThrownBy(() -> service.submit(input.toString())).isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioAiMessage.INVALID_INPUT);
        verifyNoInteractions(facts, transactions, configs);
    }

    /** 构建冻结任务，输入仅供授权和编译，不进入查询响应。 */
    private AiGenerationTaskEntity task() {
        ObjectNode request = node("{\"facts\":{\"sources\":[],\"bindings\":[]}}");
        ObjectNode input = PortfolioAiInput.normalize(http(), properties);
        request.set("input", input);
        ((ObjectNode) request.path("facts")).set("baseConfig", input.path("baseConfig").deepCopy());
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setId(7001L); task.setRequestedBy(101L); task.setOwnerType("USER"); task.setOwnerId(101L);
        task.setStatus(AiTaskStatusDict.PENDING.getCode()); task.setAttemptCount(0); task.setVersion(2);
        task.setCreatedAt(LocalDateTime.now()); task.setRequestJson(request.toString());
        return task;
    }

    /** 最小合法成功信封，在查询时仍须完整校验和渲染。 */
    private AiGenerationTaskEntity succeededTask() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        task.setResultJson("{\"kind\":\"CANDIDATE\",\"output\":{\"schemaVersion\":\"ai-portfolio-v1\",\"promptVersion\":\"portfolio-ai-2026-10-10.2\",\"status\":\"CANDIDATE\",\"mode\":\"MODIFY_PAGE\",\"page\":null,\"operations\":[],\"questions\":[],\"notes\":[]}}");
        when(tasks.selectById(7001L)).thenReturn(task);
        return task;
    }

    /** 构造仅把选中作品用作封面的真实全局修改，原页面和任务快照保持独立。 */
    private AiGenerationTaskEntity shareCoverTask() {
        AiGenerationTaskEntity task = succeededTask();
        ObjectNode request = node(task.getRequestJson());
        request.withObject("facts").withArray("sources").addObject().put("sourceNo", 1).put("kind", "WORK")
                .put("sourceId", "201").put("mediaType", "IMAGE").put("canBind", true);
        ObjectNode binding = request.withObject("facts").withArray("bindings").addObject().put("ref", "source_cover_1")
                .put("kind", "IMAGE").put("sourceNo", 1).put("reuseOnly", false)
                .put("value", "https://cdn.test/WFOWNER/work/image/photo.jpg");
        binding.putArray("allowedSlots").add("coverUrl");
        task.setRequestJson(request.toString());
        ObjectNode stored = node(task.getResultJson());
        ObjectNode operation = stored.withObject("output").withArray("operations").addObject()
                .put("op", "UPDATE_GLOBAL").put("reason", "调整作品集封面");
        operation.putObject("target").put("menuKey", "menu_current");
        operation.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.baseConfig(request).path("share")));
        operation.set("payload", node("{\"section\":\"share\",\"params\":{},\"bindings\":[{\"slot\":\"coverUrl\",\"ref\":\"source_cover_1\"}]}"));
        task.setResultJson(stored.toString());
        return task;
    }

    /** 可展示的封面候选替换为独立素材快照，二次校验与渲染都使用快照，素材统计仍准确。 */
    @Test void shareImageSnapshotIsRecompiledValidatedAndRenderedBeforeAdoption() {
        AiGenerationTaskEntity task = shareCoverTask();
        String originalRequest = task.getRequestJson();
        String snapshotUrl = "https://cdn.test/WFOWNER/protfolio/snapshot.jpg";
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenAnswer(invocation ->
                Map.of("coverUrl", ((ObjectNode) invocation.getArgument(2)).path("share").path("coverUrl").asText()));
        when(shareImages.snapshot(anyLong(), any(), any())).thenAnswer(invocation -> {
            ObjectNode request = ((ObjectNode) invocation.getArgument(1)).deepCopy();
            ((ObjectNode) request.path("facts").path("bindings").get(0)).put("value", snapshotUrl);
            return request;
        });

        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");

        assertThat(result.get("available")).isEqualTo(true);
        assertThat(result.get("renderData")).isEqualTo(Map.of("coverUrl", snapshotUrl));
        assertThat(result.get("usedSourceNos")).isEqualTo(List.of(1));
        assertThat(result.get("candidate").toString()).contains(snapshotUrl).doesNotContain("work/image/photo.jpg");
        assertThat(task.getRequestJson()).isEqualTo(originalRequest);
        var order = inOrder(configs, shareImages);
        order.verify(configs).validate(any(), isNull(), any());
        order.verify(configs).render(any(), isNull(), any());
        order.verify(shareImages).snapshot(eq(7001L), any(), any());
        order.verify(configs).validate(any(), isNull(), any());
        order.verify(configs).render(any(), isNull(), any());
    }

    /** 分享图片复制失败不能把原始作品地址作为可采用候选返回。 */
    @Test void shareImageSnapshotFailureLeavesCandidateUnavailable() {
        shareCoverTask();
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenReturn(Map.of("preview", true));
        when(shareImages.snapshot(anyLong(), any(), any())).thenThrow(new IllegalStateException("内部 COS 信息"));
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("available")).isEqualTo(false);
        assertThat(result.get("candidate")).isNull();
        assertThat(result.toString()).doesNotContain("内部 COS 信息");
    }

    /** 普通配置校验失败的结果不生成未采用的远端图片快照。 */
    @Test void invalidCandidateNeverCreatesShareImageSnapshot() {
        shareCoverTask();
        when(configs.validate(any(), isNull(), any())).thenThrow(new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID));
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("available")).isEqualTo(false);
        verifyNoInteractions(shareImages);
        verify(configs, never()).render(any(), any(), any());
    }

    /** 安全调用账本仅用于验证 query 的可选用量字段，不保存费用原文到响应。 */
    private String knownUsageLedger() {
        return """
                {"version":"media-budget-v1","calls":[
                 {"attemptNo":1,"callNo":1,"budgetDay":"2026-10-13","reservedCents":20,
                  "chargedCents":1,"usageKnown":true,"promptTokens":100,"completionTokens":40},
                 {"attemptNo":2,"callNo":2,"budgetDay":"2026-10-13","reservedCents":30,
                  "chargedCents":2,"usageKnown":true,"promptTokens":200,"completionTokens":60}]}
                """;
    }

    /** 可采用的成功候选新增安全用量，既有候选、渲染和修订语义不变。 */
    @Test void succeededCandidateAddsOptionalSafeTokenUsage() {
        AiGenerationTaskEntity task = succeededTask(); task.setMediaBudgetJson(knownUsageLedger());
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenReturn(Map.of("preview", true));
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("usage")).isEqualTo(Map.of("complete", true, "totalTokens", 400L));
        assertThat(response.get("status")).isEqualTo(AiTaskStatusDict.SUCCEEDED.getCode());
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertThat(result.get("available")).isEqualTo(true);
        assertThat(result.get("hasChanges")).isEqualTo(false);
        assertThat(result.get("renderData")).isEqualTo(Map.of("preview", true));
        assertThat(response.toString()).doesNotContain("chargedCents", "reservedCents", "promptTokens",
                "completionTokens", "calls", "mediaBudgetJson", "私密用户描述");
    }

    /** 失败任务仍有真实调用用量，原失败分类及固定提示继续保留。 */
    @Test void failedTaskAddsUsageWithoutChangingFailureContract() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.FAILED.getCode());
        task.setMediaBudgetJson(knownUsageLedger()); task.setErrorCode("REMOTE_TIMEOUT");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("usage")).isEqualTo(Map.of("complete", true, "totalTokens", 400L));
        assertThat(response.get("errorMessage")).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        assertThat(response.get("error")).isEqualTo(Map.of("code", "REMOTE_TIMEOUT", "message", "AI 服务响应超时，请重新生成"));
        assertThat(response).doesNotContainKey("result");
        verifyNoInteractions(configs);
    }

    /** 候选因来源失效不可用时用量仍可查询，任务终态和来源错误语义不变。 */
    @Test void unavailableCandidateKeepsUsageAndOriginalRejection() {
        AiGenerationTaskEntity task = succeededTask(); task.setMediaBudgetJson(knownUsageLedger());
        doThrow(new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE)).when(facts).revalidate(any(), any());
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("usage")).isEqualTo(Map.of("complete", true, "totalTokens", 400L));
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertThat(result.get("available")).isEqualTo(false);
        assertThat(result.get("unavailableReason")).isEqualTo(PortfolioAiMessage.SOURCE_UNAVAILABLE);
        assertThat(result.get("candidate")).isNull();
        verifyNoInteractions(configs);
    }

    /** 损坏费用账本降级为未知用量，不影响合法候选重验和可采用状态。 */
    @Test void malformedUsageNeverBreaksSuccessfulCandidate() {
        AiGenerationTaskEntity task = succeededTask(); task.setMediaBudgetJson("{不展示的内部账本");
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenReturn(Map.of("preview", true));
        Map<String, Object> response = service.query(7001L);
        Map<?, ?> usage = (Map<?, ?>) response.get("usage");
        assertThat(usage.get("complete")).isEqualTo(false); assertThat(usage.get("totalTokens")).isNull();
        assertThat(((Map<?, ?>) response.get("result")).get("available")).isEqualTo(true);
        assertThat(response.toString()).doesNotContain("不展示的内部账本");
    }

    /** 账本未知索引损坏同样只降级用量，不把合法候选改为不可用。 */
    @Test void inconsistentUsageIndexDoesNotRejectCandidate() {
        AiGenerationTaskEntity task = succeededTask(); task.setMediaBudgetJson(knownUsageLedger());
        task.setMediaBudgetUnknownCount(1);
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenReturn(Map.of("preview", true));
        Map<String, Object> response = service.query(7001L);
        Map<?, ?> usage = (Map<?, ?>) response.get("usage");
        assertThat(usage.get("complete")).isEqualTo(false); assertThat(usage.get("totalTokens")).isNull();
        assertThat(((Map<?, ?>) response.get("result")).get("available")).isEqualTo(true);
    }

    /** 请求 JSON 损坏仅拒绝候选，用量在授权后仍保留，不干扰原降级流程。 */
    @Test void malformedRequestStillReturnsAuthorizedUsage() {
        AiGenerationTaskEntity task = succeededTask(); task.setMediaBudgetJson(knownUsageLedger());
        task.setRequestJson("{不完整的冻结请求");
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("usage")).isEqualTo(Map.of("complete", true, "totalTokens", 400L));
        assertThat(((Map<?, ?>) response.get("result")).get("available")).isEqualTo(false);
        assertThat(response).doesNotContainKeys("baseDraftRevision", "localEditRevision");
        verifyNoInteractions(configs);
    }

    /** 历史任务没有账本时保持原响应，不新增假零用量。 */
    @Test void legacyQueryDoesNotAddUsage() {
        AiGenerationTaskEntity task = task(); when(tasks.selectById(7001L)).thenReturn(task);
        assertThat(service.query(7001L)).doesNotContainKey("usage");
    }

    /** 用量限定在授权后的单任务 query，能力查询、幂等提交与取消不扩散该字段。 */
    @Test void usageIsAddedOnlyToTaskQuery() {
        AiGenerationTaskEntity task = task(); task.setMediaBudgetJson(knownUsageLedger());
        when(tasks.selectList(any())).thenReturn(List.of(task));
        assertThat(((Map<?, ?>) service.capabilities().get("activeTask")).containsKey("usage")).isFalse();
        when(transactions.find(101L, "test-key")).thenReturn(task);
        assertThat(service.submit(http().toString())).doesNotContainKey("usage");
        task.setStatus(AiTaskStatusDict.CANCELLED.getCode()); when(tasks.selectById(7001L)).thenReturn(task);
        assertThat(service.cancel(7001L)).doesNotContainKey("usage");
    }

    /** 失败任务新增受控分类原因，既有失败文案保留且存库原文不外传。 */
    @Test void failedTaskIncludesSpecificSafeReasonWithoutChangingLegacyMessage() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.FAILED.getCode());
        task.setErrorCode("REMOTE_TIMEOUT"); task.setErrorMessage("内部地址和密钥不能展示");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("errorCode")).isEqualTo("REMOTE_TIMEOUT");
        assertThat(response.get("errorMessage")).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        assertThat(response.get("error")).isEqualTo(Map.of("code", "REMOTE_TIMEOUT", "message", "AI 服务响应超时，请重新生成"));
        assertThat(response.toString()).doesNotContain("内部地址和密钥");
    }

    /** 未知失败分类降级为固定原因，不把不可信分类和文案引入新增诊断对象。 */
    @Test void unknownFailedTaskReasonUsesSafeFallback() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.FAILED.getCode());
        task.setErrorCode("不可信内部分类"); task.setErrorMessage("不可信内部原文");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("error")).isEqualTo(Map.of("code", "GENERATION_FAILED", "message", "AI 生成未完成，请重新生成"));
        assertThat(response.get("errorCode")).isEqualTo("不可信内部分类");
        assertThat(response.get("errorMessage")).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        assertThat(response.toString()).doesNotContain("不可信内部原文");
    }

    /** 任务失败分类均有稳定中文解释，不受存库内部原因影响。 */
    @ParameterizedTest
    @CsvSource({
            "TIMEOUT,生成任务超过处理时限，请重新生成",
            "REMOTE_BUSY,AI 服务繁忙，请稍后重新生成",
            "REMOTE_ERROR,连接 AI 服务失败，请稍后重新生成",
            "AI_UNAVAILABLE,AI 服务配置或账户暂不可用，请稍后重试",
            "REMOTE_CONFIGURATION,AI 服务接口配置不符合要求，请稍后重试",
            "OUTPUT_TRUNCATED,AI 生成内容超过长度限制，未能完整返回，请简化描述后重新生成",
            "OUTPUT_BLOCKED,AI 未能完成本次内容生成，请调整描述后重新生成",
            "INVALID_OUTPUT,AI 返回的内容格式不符合要求，请重新生成",
            "RESPONSE_TOO_LARGE,AI 返回的内容超过处理上限，请简化描述后重新生成",
            "ATTEMPTS_EXHAUSTED,生成任务多次尝试仍未完成，请重新生成",
            "EXECUTION_INTERRUPTED,生成任务执行中断，请重新生成",
            "INTERNAL_ERROR,生成服务处理异常，请稍后重新生成",
            "CONTRACT_UNAVAILABLE,生成协议已更新，请重新生成"})
    void classifiedFailedTaskIncludesSpecificSafeReason(String code, String message) {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.FAILED.getCode());
        task.setErrorCode(code); task.setErrorMessage("不展示的内部原文");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("error")).isEqualTo(Map.of("code", code, "message", message));
        assertThat(response.get("errorMessage")).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        assertThat(response.toString()).doesNotContain("不展示的内部原文");
    }

    /** 媒体准备失败必须保留具体分类及安全原因，旧客户端的顶层提示仍然保持兼容。 */
    @ParameterizedTest
    @CsvSource({
            "MEDIA_INVALID,所选素材不符合 AI 处理要求，请调整素材后重新生成",
            "SOURCE_UNAVAILABLE,所选素材已失效或无权访问，请重新选择素材后生成",
            "MEDIA_NOT_PREPARED,所选视频尚未完成素材准备，请稍后重新生成",
            "MEDIA_BUDGET_EXHAUSTED,AI 媒体处理额度不足，请减少素材或稍后重试",
            "MEDIA_BUDGET_OVERRUN,本次 AI 媒体处理超出额度，请减少素材后重新生成",
            "MEDIA_BUDGET_INVALID,AI 媒体处理费用记录异常，请联系管理员核对",
            "MEDIA_ACCESS_RETRY,媒体对象服务暂时不可用，请稍后重新生成",
            "INVALID_MEDIA_SUMMARY,AI 返回的素材分析结果不符合要求，请重新生成"})
    void mediaFailedTaskIncludesSpecificSafeReason(String code, String message) {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.FAILED.getCode());
        task.setErrorCode(code); task.setErrorMessage("不展示的媒体地址及内部原文");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("errorCode")).isEqualTo(code);
        assertThat(response.get("errorMessage")).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        assertThat(response.get("error")).isEqualTo(Map.of("code", code, "message", message));
        assertThat(response.toString()).doesNotContain("不展示的媒体地址及内部原文");
    }

    /** 普通个人和团队组件的固定校验提示进入新增原因，旧不可用提示保持兼容。 */
    @Test void trustedComponentValidationReasonsArePreserved() {
        succeededTask();
        for (String message : List.of(PortfolioTextMessage.BACKGROUND_UNAVAILABLE, TeamPortfolioMessage.VIDEO_CAROUSEL_ITEM_COUNT_INVALID)) {
            doThrow(new BusinessException(message)).when(configs).validate(any(), isNull(), any());
            Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
            assertThat(result.get("unavailableReason")).isEqualTo(PortfolioAiMessage.CANDIDATE_INVALID);
            assertThat(result.get("error")).isEqualTo(Map.of("code", "CONFIG_INVALID", "message", "生成结果不符合组件要求：" + message));
            assertThat(result.get("candidate")).isNull(); assertThat(result.get("available")).isEqualTo(false);
        }
    }

    /** 非白名单业务异常仅显示当前处理阶段，不能把内部原文带到客户端。 */
    @Test void untrustedValidationReasonOnlyExposesSafeStage() {
        succeededTask();
        when(configs.validate(any(), isNull(), any())).thenThrow(new BusinessException("未知键、资源地址和用户原文"));
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("error")).isEqualTo(Map.of("code", "CONFIG_INVALID", "message", "生成结果未通过作品集组件配置校验，请重新生成"));
        assertThat(result.toString()).doesNotContain("未知键", "资源地址", "用户原文");
    }

    /** 协议异常只按固定分类解释，不采信异常携带的任意摘要。 */
    @Test void protocolExceptionReasonIsDerivedFromCodeOnly() {
        succeededTask();
        doThrow(new AiProtocolException("INVALID_OUTPUT", "隐藏模型原文")).when(facts).revalidate(any(), any());
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("error")).isEqualTo(Map.of("code", "INVALID_OUTPUT", "message", "AI 返回的内容格式不符合要求，请重新生成"));
        assertThat(result.toString()).doesNotContain("隐藏模型原文");
    }

    /** 普通渲染意外异常也显示固定失败阶段，不能留下可采用半成品或抛出内部细节。 */
    @Test void unexpectedRenderingFailureHasSpecificSafeReason() {
        succeededTask();
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenThrow(new IllegalStateException("不能展示的内部堆栈原因"));
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("error")).isEqualTo(Map.of("code", "RENDER_FAILED", "message", "生成结果未能完成页面展示，请重新生成"));
        assertThat(result.get("kind")).isEqualTo("CANDIDATE");
        assertThat(result.get("candidate")).isNull(); assertThat(result.get("available")).isEqualTo(false);
        assertThat(result.toString()).doesNotContain("不能展示的内部堆栈原因");
    }

    /** 源素材失效提供独立来源阶段，保留任务终态和既有提示。 */
    @Test void unavailableSourceIncludesSpecificReasonWithoutChangingTaskStatus() {
        AiGenerationTaskEntity task = succeededTask();
        doThrow(new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE)).when(facts).revalidate(any(), any());
        Map<String, Object> response = service.query(7001L);
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertThat(result.get("error")).isEqualTo(Map.of("code", "SOURCE_VALIDATION_FAILED", "message", PortfolioAiMessage.SOURCE_UNAVAILABLE));
        assertThat(result.get("unavailableReason")).isEqualTo(PortfolioAiMessage.SOURCE_UNAVAILABLE);
        assertThat(response.get("status")).isEqualTo(AiTaskStatusDict.SUCCEEDED.getCode());
        assertThat(task.getStatus()).isEqualTo(AiTaskStatusDict.SUCCEEDED.getCode());
        verifyNoInteractions(configs);
    }

    /** 规范化静默丢字段不能算无改动成功，新增原因指出兼容性拒绝且不包含字段名。 */
    @Test void normalizationMismatchHasSpecificSafeReason() {
        succeededTask();
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> {
            ObjectNode normalized = ((ObjectNode) invocation.getArgument(2)).deepCopy();
            ((ObjectNode) normalized.path("components").get(0).path("config")).remove("content");
            return normalized;
        });
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("error")).isEqualTo(Map.of("code", "NORMALIZATION_FIELD_DROPPED", "message", "生成配置包含无法保留的字段，未通过兼容性校验，请重新生成"));
        assertThat(result.get("unavailableReason")).isEqualTo(PortfolioAiMessage.CANDIDATE_INVALID);
        assertThat(result.get("candidate")).isNull(); assertThat(result.get("hasChanges")).isNull();
        verify(configs, never()).render(any(), any(), any());
    }

    /** 冻结请求损坏仍给出原因，而不暴露原始输入或误认为可采用。 */
    @Test void malformedSucceededRequestIncludesSafeReason() {
        AiGenerationTaskEntity task = succeededTask(); task.setRequestJson("{私密原文");
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("error")).isEqualTo(Map.of("code", "REQUEST_INVALID", "message", "生成任务的原始输入不完整或已失效，请重新生成"));
        assertThat(result.get("available")).isEqualTo(false);
        assertThat(result.toString()).doesNotContain("私密原文");
    }

    /** 同键重放仅检查当前维护权，不再次冻结事实或生成新任务，关闭开关也不复制旧任务。 */
    @Test void replayDoesNotRefreezeOrReexecute() {
        AiGenerationTaskEntity task = task();
        when(transactions.find(101L, "test-key")).thenReturn(task);
        assertThat(service.submit(http().toString()).get("taskId")).isEqualTo("7001");
        verify(transactions).requireSame(eq(task), any(ObjectNode.class));
        verifyNoInteractions(configs);
        verify(facts, never()).freeze(any(), any());
        verify(transactions, never()).submit(any(), any(), any());
    }

    /** 新任务冻结 TokenHub 身份、思考与采样参数，之后配置变化不改写既有任务。 */
    @Test void newSubmissionFreezesTokenHubModelConfiguration() {
        properties.setEnabled(true);
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(facts.freeze(any(), any())).thenAnswer(invocation -> {
            ObjectNode frozenFacts = node("{\"sources\":[],\"bindings\":[],\"safePrompt\":\"调整页面\"}");
            frozenFacts.set("baseConfig", invocation.getArgument(1, ObjectNode.class).deepCopy());
            return frozenFacts;
        });
        when(transactions.submit(eq(101L), eq("test-key"), any())).thenReturn(task());

        assertThat(service.submit(http().toString()).get("taskId")).isEqualTo("7001");

        ArgumentCaptor<ObjectNode> frozen = ArgumentCaptor.forClass(ObjectNode.class);
        verify(transactions).submit(eq(101L), eq("test-key"), frozen.capture());
        ObjectNode snapshot = frozen.getValue();
        String frozenJson = snapshot.toString();
        properties.setTemperature(0.5);
        properties.setMaxTokens(8192);
        properties.setReasoningEffort("low");
        assertThat(properties.getMaxTokens()).isEqualTo(8192);
        assertThat(properties.getReasoningEffort()).isEqualTo("low");
        assertThat(snapshot.toString()).isEqualTo(frozenJson);
        assertThat(snapshot.path("modelConfig").size()).isEqualTo(9);
        assertThat(snapshot.path("modelConfig").path("mediaEnabled").isBoolean()).isTrue();
        assertThat(snapshot.path("modelConfig").path("mediaEnabled").asBoolean()).isFalse();
        assertThat(snapshot.path("modelConfig").path("provider").asText()).isEqualTo("TOKENHUB");
        assertThat(snapshot.path("modelConfig").path("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(snapshot.path("modelConfig").path("baseUrl").asText()).isEqualTo("https://tokenhub.tencentmaas.com/v1");
        assertThat(snapshot.path("modelConfig").path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(snapshot.path("modelConfig").path("maxTokens").asInt()).isEqualTo(32768);
        assertThat(snapshot.path("modelConfig").path("reasoningEffort").asText()).isEqualTo("high");
        assertThat(snapshot.path("modelConfig").path("thinking").asText()).isEqualTo("enabled");
        assertThat(snapshot.path("modelConfig").path("stream").asBoolean()).isFalse();
        assertThat(snapshot.path("contractVersion").asText()).isEqualTo(AiPortfolioProtocol.CONTRACT_VERSION);
        assertThat(snapshot.path("promptVersion").asText()).isEqualTo(AiPortfolioProtocol.PROMPT_VERSION);
    }

    /** 前端来源上下文仍只允许稳定身份字段，任意媒体地址不能绕过服务端授权。 */
    @Test void submissionRejectsClientSuppliedSourceMediaUrl() {
        properties.setEnabled(true);
        ObjectNode request = http();
        request.withObject("aiContext").put("nextSourceNo", 2).withArray("sources").addObject()
                .put("sourceNo", 1).put("kind", "WORK").put("sourceId", "201")
                .put("mediaUrl", "https://untrusted.test/media.mp4");
        assertThatThrownBy(() -> service.submit(request.toString())).isInstanceOf(BusinessException.class)
                .hasMessage(AdvancedPortfolioMessage.INVALID_CONTEXT);
        verify(facts, never()).freeze(any(), any());
        verifyNoInteractions(configs); verify(transactions, never()).submit(any(), any(), any());
    }

    /** 新任务关闭时被拒绝，但不会改变当前编辑。 */
    @Test void disabledRejectsOnlyNewSubmission() {
        assertThatThrownBy(() -> service.submit(http().toString())).isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.DISABLED);
        verifyNoInteractions(configs); verify(transactions, never()).submit(any(), any(), any());
    }

    /** 任意层重复键的非法请求返回安全业务错误而非 500。 */
    @Test void invalidRawJsonReturnsBusinessFailure() {
        assertThatThrownBy(() -> service.submit("{\"prompt\":\"a\",\"prompt\":\"b\"}"))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.INVALID_INPUT);
        verifyNoInteractions(facts, configs, transactions);
    }

    /** 请求人不同不能读取原文，即使同为团队维护者。 */
    @Test void anotherRequesterCannotReadTask() {
        AiGenerationTaskEntity task = task(); task.setRequestedBy(102L); task.setMediaBudgetJson(knownUsageLedger());
        when(tasks.selectById(7001L)).thenReturn(task);
        assertThatThrownBy(() -> service.query(7001L)).isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.TASK_NOT_FOUND);
        verifyNoInteractions(facts, configs);
    }

    /** 终态从 completedAt 起三十天后立即不可查询，不等待每小时物理清理。 */
    @Test void terminalRetentionIsEnforcedBeforeReadingContent() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        task.setCompletedAt(LocalDateTime.now().minusDays(30).minusSeconds(1));
        when(tasks.selectById(7001L)).thenReturn(task);
        assertThatThrownBy(() -> service.query(7001L)).hasMessage(PortfolioAiMessage.TASK_NOT_FOUND);
        verifyNoInteractions(facts, configs);
    }

    /** 已成功但远端修订改变，仅返回候选不可用，不改任务终态或当前编辑。 */
    @Test void succeededTaskWithStaleRevisionIsUnavailable() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setDraftRevision(4);
        when(tasks.selectById(7001L)).thenReturn(task);
        when(facts.requireOwner(any(), eq(101L))).thenReturn(portfolio);
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("available")).isEqualTo(false);
        assertThat(result.get("unavailableReason")).isEqualTo(PortfolioAiMessage.STALE_DRAFT);
        assertThat(task.getStatus()).isEqualTo(AiTaskStatusDict.SUCCEEDED.getCode());
        verifyNoInteractions(configs);
    }

    /** 合法无改动候选返回 hasChanges=false，响应不泄露任务原文。 */
    @Test void noOpCandidateHasNoChangesAndDoesNotExposeRawInput() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        ObjectNode output = node("{\"schemaVersion\":\"ai-portfolio-v1\",\"promptVersion\":\"portfolio-ai-2026-10-10.2\",\"status\":\"CANDIDATE\",\"mode\":\"MODIFY_PAGE\",\"page\":null,\"operations\":[],\"questions\":[],\"notes\":[]}");
        ObjectNode stored = node("{\"kind\":\"CANDIDATE\"}"); stored.set("output", output); task.setResultJson(stored.toString());
        when(tasks.selectById(7001L)).thenReturn(task);
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenReturn(Map.of("preview", true));
        Map<String, Object> response = service.query(7001L);
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertThat(result.get("available")).isEqualTo(true); assertThat(result.get("hasChanges")).isEqualTo(false);
        assertThat(result.get("summary")).isEqualTo(List.of(PortfolioAiMessage.NO_CHANGES));
        assertThat(response.toString()).doesNotContain("私密用户描述", "requestJson", "request_json", "result_json");
        verify(transactions, never()).submit(any(), any(), any()); verify(tasks, never()).insert(any(AiGenerationTaskEntity.class));
    }

    /** 取消条件包含发起人、活动态和当前执行 version，迟到结果不能覆盖。 */
    @Test void cancellationUsesCurrentGenerationCas() {
        AiGenerationTaskEntity task = task();
        when(tasks.selectById(7001L)).thenReturn(task);
        when(tasks.cancel(eq(7001L), eq(101L), eq(AiTaskStatusDict.PENDING.getCode()), eq(2), any())).thenAnswer(invocation -> {
            task.setStatus(AiTaskStatusDict.CANCELLED.getCode()); task.setVersion(3); return 1;
        });
        assertThat(service.cancel(7001L).get("status")).isEqualTo(AiTaskStatusDict.CANCELLED.getCode());
        verify(tasks).cancel(eq(7001L), eq(101L), eq(AiTaskStatusDict.PENDING.getCode()), eq(2), any());
    }

    /** 已成功任务的损坏、超字节预算和超深度原文统一返回安全的不可用候选。 */
    @Test void malformedSucceededRequestIsUnavailableAfterColumnAuthorization() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        when(tasks.selectById(7001L)).thenReturn(task);
        List<String> invalidRequests = List.of("{", "{\"input\":null}", "{\"input\":[]}",
                "{\"private\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}",
                "{\"nested\":".repeat(25) + "{}" + "}".repeat(25));
        for (String request : invalidRequests) {
            task.setRequestJson(request);
            Map<String, Object> response = service.query(7001L);
            Map<?, ?> result = (Map<?, ?>) response.get("result");
            assertThat(response.get("status")).isEqualTo(AiTaskStatusDict.SUCCEEDED.getCode());
            assertThat(response).doesNotContainKeys("baseDraftRevision", "localEditRevision", "requestJson", "request_json");
            assertThat(result.get("available")).isEqualTo(false);
            assertThat(result.get("candidate")).isNull();
            assertThat(result.get("unavailableReason")).isEqualTo(PortfolioAiMessage.CANDIDATE_INVALID);
        }
        verify(facts, times(invalidRequests.size())).requireOwner(eq(node("{\"ownerType\":\"USER\",\"ownerId\":\"101\",\"portfolioId\":null}")), eq(101L));
        verifyNoInteractions(configs, transactions);
    }

    /** 请求解析损坏不能覆盖已失败状态或导致查询返回内部解析异常。 */
    @Test void malformedFailedRequestStillReturnsSafeStatus() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.FAILED.getCode());
        task.setRequestJson("{私密原文"); task.setErrorMessage("内部原文");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("status")).isEqualTo(AiTaskStatusDict.FAILED.getCode());
        assertThat(response.get("errorMessage")).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        assertThat(response).doesNotContainKeys("result", "baseDraftRevision", "localEditRevision");
        assertThat(response.toString()).doesNotContain("私密原文", "内部原文");
    }

    /** 损坏等待记录仅返回真实活动状态，不伪造修订号或更改数据库状态。 */
    @Test void malformedPendingRequestStillReturnsSafeStatus() {
        AiGenerationTaskEntity task = task(); task.setRequestJson("{不完整");
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<String, Object> response = service.query(7001L);
        assertThat(response.get("status")).isEqualTo(AiTaskStatusDict.PENDING.getCode());
        assertThat(response).doesNotContainKeys("result", "baseDraftRevision", "localEditRevision");
        verify(tasks, never()).cancel(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /** 原文不可信时依然按独立团队及作品集列鉴权，不能借用原文中的个人身份。 */
    @Test void frozenOwnershipMismatchNeverExposesCandidate() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        task.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode()); task.setOwnerId(77L); task.setPortfolioId(42L);
        when(tasks.selectById(7001L)).thenReturn(task);
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("available")).isEqualTo(false);
        verify(facts).requireOwner(eq(node("{\"ownerType\":\"TEAM\",\"ownerId\":\"77\",\"portfolioId\":\"42\"}")), eq(101L));
        verifyNoInteractions(configs);
    }

    /** 请求损坏不能绕过当前维护权；查询和取消均在降级前拒绝撤权用户。 */
    @Test void malformedRequestDoesNotHideRevokedOwnership() {
        AiGenerationTaskEntity task = task(); task.setRequestJson("{不完整"); task.setMediaBudgetJson(knownUsageLedger());
        when(tasks.selectById(7001L)).thenReturn(task);
        when(facts.requireOwner(any(), eq(101L))).thenThrow(new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE));
        assertThatThrownBy(() -> service.query(7001L)).hasMessage(PortfolioAiMessage.SOURCE_UNAVAILABLE);
        assertThatThrownBy(() -> service.cancel(7001L)).hasMessage(PortfolioAiMessage.SOURCE_UNAVAILABLE);
        verify(tasks, never()).cancel(anyLong(), anyLong(), anyString(), anyInt(), any());
        verifyNoInteractions(configs);
    }

    /** 仅本请求人可按独立列维护权取消损坏、缺输入或超预算记录，不解析原文获取授权。 */
    @Test void malformedActiveRequestCanBeCancelledByCurrentMaintainer() {
        AiGenerationTaskEntity task = task(); task.setRequestJson("{\"private\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}");
        when(tasks.selectById(7001L)).thenReturn(task);
        when(tasks.cancel(eq(7001L), eq(101L), eq(AiTaskStatusDict.PENDING.getCode()), eq(2), any())).thenAnswer(invocation -> {
            task.setStatus(AiTaskStatusDict.CANCELLED.getCode()); task.setVersion(3); return 1;
        });
        assertThat(service.cancel(7001L).get("status")).isEqualTo(AiTaskStatusDict.CANCELLED.getCode());
        verify(facts).requireOwner(eq(node("{\"ownerType\":\"USER\",\"ownerId\":\"101\",\"portfolioId\":null}")), eq(101L));
        verifyNoInteractions(configs);
    }

    /** 同为维护者也不能取消其他请求人的损坏任务。 */
    @Test void anotherRequesterCannotCancelMalformedTask() {
        AiGenerationTaskEntity task = task(); task.setRequestedBy(102L); task.setRequestJson("{不完整");
        when(tasks.selectById(7001L)).thenReturn(task);
        assertThatThrownBy(() -> service.cancel(7001L)).hasMessage(PortfolioAiMessage.TASK_NOT_FOUND);
        verifyNoInteractions(facts, configs);
        verify(tasks, never()).cancel(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /** 独立列中的非法归属不能被默认解释为团队或退回原文身份。 */
    @Test void invalidColumnOwnershipCannotFallBackToFrozenInput() {
        AiGenerationTaskEntity task = task(); task.setOwnerType(null);
        when(tasks.selectById(7001L)).thenReturn(task);
        assertThatThrownBy(() -> service.query(7001L)).hasMessage(PortfolioAiMessage.TASK_NOT_FOUND);
        assertThatThrownBy(() -> service.cancel(7001L)).hasMessage(PortfolioAiMessage.TASK_NOT_FOUND);
        verifyNoInteractions(facts, configs);
    }

    /** 描述预算按 Unicode 码点，emoji 不按 UTF-16 单元多算。 */
    @Test void unicodePromptBudgetCountsCodePoints() {
        ObjectNode request = http(); request.put("prompt", "😀".repeat(2000));
        assertThat(PortfolioAiInput.normalize(request, properties).path("prompt").asText()).hasSize(4000);
        request.put("prompt", "😀".repeat(2001));
        assertThatThrownBy(() -> PortfolioAiInput.normalize(request, properties)).isInstanceOf(BusinessException.class);
    }

    /** 字体能力整型溢出不能被 intValue 截断成合法0或1。 */
    @Test void fontCapabilityRejectsIntegerOverflow() {
        ObjectNode request = http(); ((ObjectNode) request.path("clientCapabilities")).put("portfolioRemoteFont", 4294967296L);
        assertThatThrownBy(() -> PortfolioAiInput.normalize(request, properties)).isInstanceOf(BusinessException.class);
    }

    /** 新建来源清单全失效时不入队，也不因仅存在旧编号而调用模型。 */
    @Test void createRequiresAtLeastOneCurrentlyAvailableSource() {
        properties.setEnabled(true);
        ObjectNode request = http().put("mode", "CREATE");
        ((ObjectNode) request.path("aiContext")).put("nextSourceNo", 2).withArray("sources")
                .addObject().put("sourceNo", 1).put("kind", "WORK").put("sourceId", "201");
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(facts.freeze(any(), any())).thenReturn(node("{\"sources\":[{\"sourceNo\":1,\"canBind\":false}]}"));
        assertThatThrownBy(() -> service.submit(request.toString())).hasMessage(PortfolioAiMessage.SOURCE_UNAVAILABLE);
        verify(transactions, never()).submit(any(), any(), any());
    }

    /** 完整渲染异常时整个候选不可采用，不残留半成功的candidate或available。 */
    @Test void renderingFailureNeverExposesAdoptableCandidate() {
        AiGenerationTaskEntity task = task(); task.setStatus(AiTaskStatusDict.SUCCEEDED.getCode());
        ObjectNode stored = node("{\"kind\":\"CANDIDATE\",\"output\":{\"schemaVersion\":\"ai-portfolio-v1\",\"promptVersion\":\"portfolio-ai-2026-10-10.2\",\"status\":\"CANDIDATE\",\"mode\":\"MODIFY_PAGE\",\"page\":null,\"operations\":[],\"questions\":[],\"notes\":[]}}");
        task.setResultJson(stored.toString()); when(tasks.selectById(7001L)).thenReturn(task);
        when(configs.validate(any(), isNull(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configs.render(any(), isNull(), any())).thenThrow(new BusinessException("内部敏感详情"));
        Map<?, ?> result = (Map<?, ?>) service.query(7001L).get("result");
        assertThat(result.get("available")).isEqualTo(false); assertThat(result.get("candidate")).isNull();
        assertThat(result.get("hasChanges")).isNull(); assertThat(result.containsKey("renderData")).isFalse();
        assertThat(result.toString()).doesNotContain("内部敏感详情");
    }
}
