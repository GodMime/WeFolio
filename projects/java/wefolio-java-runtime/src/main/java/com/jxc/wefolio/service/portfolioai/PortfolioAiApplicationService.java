package com.jxc.wefolio.service.portfolioai;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioSaveMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 高级 AI 用例编排：提交、查询、取消，不保存、不发布、不扣积分。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioAiApplicationService {
    /** 节点创建工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** 任务记录内部最大预算，包含两层冻结包装与必要事实。 */
    private static final int TASK_MAX_BYTES = 2 * 1024 * 1024;
    /** 包装对象的附加深度预算。 */
    private static final int WRAPPER_DEPTH = 8;
    /** 等待轮询初始间隔。 */
    private static final int INITIAL_POLL_MS = 2000;
    /** Runtime 负责新任务能力冻结，Job 可诊断部署差异而不改变已冻结契约。 */
    private static final String FROZEN_MEDIA_ENABLED = "mediaEnabled";
    /** 所有终态。 */
    private static final Set<String> TERMINAL = Set.of(AiTaskStatusDict.SUCCEEDED.getCode(), AiTaskStatusDict.FAILED.getCode(), AiTaskStatusDict.CANCELLED.getCode());
    /** 新附件 HTTP 字段，独立于旧 input 冻结。 */
    private static final String REFERENCE_ATTACHMENTS = "referenceAttachments";
    /** 本次终态任务可保存窗口截止字段。 */
    private static final String REFERENCE_SAVE_UNTIL = "referenceSaveUntil";
    /** 可信附件快照和 owner 锁。 */
    private final PortfolioAiReferenceService references;
    /** 当前有效运行预算与开关。 */
    private final PortfolioAiProperties properties;
    /** 任务读写访问器。 */
    private final AiGenerationTaskEntityMapper tasks;
    /** 短事务保证同请求人活动额度和提交幂等。 */
    private final PortfolioAiTaskTransactionService transactions;
    /** 归属、来源权限与事实冻结。 */
    private final PortfolioAiFactsService facts;
    /** 真实普通配置业务校验及预览渲染。 */
    private final PortfolioAiConfigService configs;
    /** 多模态任务仅冻结经授权的对象身份，不保存短期签名。 */
    private final PortfolioAiMediaPlanService mediaPlans;
    /** 已选作品用于分享图片时创建独立素材快照，避免作品变更使作品集封面失效。 */
    private final PortfolioAiShareImageService shareImages;

    /** 返回服务当前能力与本人活动任务，仅包含安全状态。 */
    public Map<String, Object> capabilities() {
        Long requester = AuthContextHolder.requireUserId();
        expire(requester);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_ENABLED, properties.isEnabled());
        result.put(REFERENCE_ATTACHMENTS, references.capabilities());
        result.put(KEY_MODES, List.of(MODE_CREATE, MODE_MODIFY_PAGE));
        result.put(KEY_AI_CONTRACT_VERSION, AiPortfolioProtocol.CONTRACT_VERSION);
        result.put(KEY_PROMPT_VERSION, AiPortfolioProtocol.PROMPT_VERSION);
        Map<String, Object> limits = new LinkedHashMap<>(JSON.parseObject(AiPortfolioProtocol.limits().toString()));
        limits.put(KEY_MAX_PROMPT_CODE_POINTS, properties.getMaxPromptCodePoints());
        limits.put(KEY_MAX_CONFIG_BYTES, properties.getMaxConfigBytes());
        limits.put(KEY_MAX_CONTEXT_BYTES, properties.getMaxContextBytes());
        limits.put(KEY_MAX_JSON_DEPTH, properties.getMaxJsonDepth());
        result.put(KEY_LIMITS, limits);
        List<AiGenerationTaskEntity> active = tasks.selectList(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                .eq(AiGenerationTaskEntity::getRequestedBy, requester).in(AiGenerationTaskEntity::getStatus, PortfolioAiTaskTransactionService.ACTIVE));
        result.put(KEY_ACTIVE_TASK, active.isEmpty() ? null : status(active.getFirst()));
        return result;
    }

    /** 新提交先检查幂等，只有确实新任务才冻结当前模型参数和授权事实。 */
    public Map<String, Object> submit(String raw) {
        try { return submitChecked(raw); }
        catch (AiProtocolException exception) { throw new BusinessException(PortfolioAiMessage.INVALID_INPUT); }
    }

    /** 所有解析与受限协议错误在应用边界统一映射为业务错误。 */
    private Map<String, Object> submitChecked(String raw) {
        Long requester = AuthContextHolder.requireUserId();
        ObjectNode http = StrictAiJson.parseObject(raw, TASK_MAX_BYTES, properties.getMaxJsonDepth() + WRAPPER_DEPTH);
        JsonNode referenceValue = http.remove(REFERENCE_ATTACHMENTS);
        ArrayNode referenceProjection = PortfolioAiReferenceService.normalizeBusinessProjection(referenceValue);
        ArrayNode referenceItems = referenceValue instanceof ArrayNode values ? values : NODES.arrayNode();
        ObjectNode input = PortfolioAiInput.normalize(http, properties);
        PortfolioEntity portfolio = facts.requireOwner(input, requester);
        String key = http.path(KEY_IDEMPOTENCY_KEY).textValue();
        expire(requester);
        AiGenerationTaskEntity replay = transactions.find(requester, key);
        if (replay != null) {
            requireRetained(replay);
            if (referenceProjection.isEmpty() && replay.getReferenceAttachmentsJson() == null) { transactions.requireSame(replay, input); }
            else { transactions.requireSame(replay, input, referenceItems); }
            return status(replay);
        }
        if (!properties.isEnabled()) { throw new BusinessException(PortfolioAiMessage.DISABLED); }
        if (!referenceProjection.isEmpty() && (!properties.isReferenceEnabled() || properties.getMaxActiveTasks() < 2)) {
            throw new BusinessException(PortfolioAiMessage.DISABLED);
        }
        if (portfolio != null && !input.path(KEY_BASE_DRAFT_REVISION).isIntegralNumber()) { throw new BusinessException(PortfolioAiMessage.INVALID_INPUT); }
        requireRevision(input, portfolio);
        JsonNode context = input.path(KEY_AI_CONTEXT);
        StrictAiJson.parseObject(context.toString(), properties.getMaxContextBytes(), properties.getMaxJsonDepth());
        AdvancedPortfolioSaveMetadata.validateContext(JSON.parseObject(context.toString()), portfolio == null ? null : portfolio.getAiContextJson());
        if (context.path(KEY_SOURCES).size() > AiPortfolioProtocol.limits().path(KEY_MAX_SOURCE_COUNT).intValue()) { throw new BusinessException(PortfolioAiMessage.INVALID_INPUT); }
        if (MODE_CREATE.equals(input.path(KEY_MODE).asText()) && context.path(KEY_SOURCES).isEmpty()) { throw new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE); }
        ObjectNode base = input.path(KEY_BASE_CONFIG).isObject() ? (ObjectNode) input.path(KEY_BASE_CONFIG) : configs.initialConfig(input);
        ObjectNode normalized = configs.validate(input, portfolio, base);
        ObjectNode frozen = NODES.objectNode();
        frozen.set(KEY_INPUT, input);
        ObjectNode frozenFacts = facts.freeze(input, normalized);
        if (properties.isMediaEnabled()) { mediaPlans.freeze(input, frozenFacts); }
        if (MODE_CREATE.equals(input.path(KEY_MODE).asText())) {
            boolean anyAvailable = false;
            for (JsonNode source : frozenFacts.path(KEY_SOURCES)) { anyAvailable |= source.path(KEY_CAN_BIND).asBoolean(true); }
            if (!anyAvailable) { throw new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE); }
        }
        frozen.set(KEY_FACTS, frozenFacts);
        frozen.put(KEY_CONTRACT_VERSION, AiPortfolioProtocol.CONTRACT_VERSION).put(KEY_PROMPT_VERSION, AiPortfolioProtocol.PROMPT_VERSION);
        frozen.putObject(KEY_MODEL_CONFIG).put(KEY_PROVIDER, PROVIDER_TOKENHUB).put(KEY_MODEL, properties.getModel())
                .put(KEY_BASE_URL, properties.getBaseUrl()).put(KEY_TEMPERATURE, properties.getTemperature())
                .put(FROZEN_MEDIA_ENABLED, properties.isMediaEnabled())
                .put(KEY_MAX_TOKENS, properties.getMaxTokens()).put(KEY_REASONING_EFFORT, properties.getReasoningEffort())
                .put(KEY_THINKING, THINKING_ENABLED).put(KEY_STREAM, false);
        // 投影在提交时预检，不能把无法定位的菜单、组件或未支持基准交给 Job。
        AiPortfolioProtocol.project(frozen);
        StrictAiJson.parseObject(frozen.toString(), TASK_MAX_BYTES, properties.getMaxJsonDepth() + WRAPPER_DEPTH);
        if (referenceProjection.isEmpty()) { return status(transactions.submit(requester, key, frozen)); }
        return references.withOwnerLock(input.path(KEY_OWNER_TYPE).asText(), PortfolioAiInput.id(input, KEY_OWNER_ID, false),
                () -> status(transactions.submit(requester, key, frozen, referenceItems)));
    }

    /** 查询成功任务每次重编译、重验，状态 SUCCEEDED 不代表永久可采用。 */
    public Map<String, Object> query(Long taskId) {
        Long requester = AuthContextHolder.requireUserId();
        expire(requester);
        AiGenerationTaskEntity task = requireTask(taskId, requester);
        PortfolioEntity portfolio = requireTaskOwner(task, requester);
        Map<String, Object> response = status(task);
        if (task.getReferenceAttachmentsJson() != null) {
            response.put(REFERENCE_ATTACHMENTS, references.taskResponse(task.getReferenceAttachmentsJson(), task.getOwnerType(), task.getOwnerId()));
            if (task.getCompletedAt() != null) { response.put(REFERENCE_SAVE_UNTIL, task.getCompletedAt().plusHours(24)); }
        }
        Map<String, Object> usage = PortfolioAiTokenUsage.summarize(task.getId(), task.getMediaBudgetJson(), task.getMediaBudgetUnknownCount());
        if (usage != null) { response.put(PortfolioAiTokenUsage.RESPONSE_KEY, usage); }
        ObjectNode request;
        try {
            request = parseTask(task);
        } catch (AiProtocolException | BusinessException exception) {
            // 请求损坏不改变数据库终态；只有成功任务需要额外说明候选不可用。
            if (AiTaskStatusDict.SUCCEEDED.getCode().equals(task.getStatus())) {
                rejectCandidate(response, PortfolioAiMessage.CANDIDATE_INVALID, PortfolioAiFailureDetails.Stage.REQUEST, exception);
            }
            return response;
        }
        ObjectNode input = (ObjectNode) request.path(KEY_INPUT);
        response.put(KEY_BASE_DRAFT_REVISION, input.path(KEY_BASE_DRAFT_REVISION).intValue());
        response.put(KEY_LOCAL_EDIT_REVISION, input.path(KEY_LOCAL_EDIT_REVISION).intValue());
        if (!AiTaskStatusDict.SUCCEEDED.getCode().equals(task.getStatus())) { return response; }
        Map<String, Object> result = unavailableCandidate(response);
        PortfolioAiFailureDetails.Stage stage = PortfolioAiFailureDetails.Stage.PROTOCOL;
        try {
            AiPortfolioProtocol.checkVersion(request);
            stage = PortfolioAiFailureDetails.Stage.REVISION;
            requireRevision(input, portfolio);
            stage = PortfolioAiFailureDetails.Stage.PROTOCOL;
            ObjectNode stored = StrictAiJson.parseObject(task.getResultJson(), TASK_MAX_BYTES, properties.getMaxJsonDepth() + WRAPPER_DEPTH);
            if (stored.size() != 2 || !stored.has(KEY_KIND) || !stored.path(KEY_OUTPUT).isObject()) { throw new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID); }
            ObjectNode output = (ObjectNode) stored.path(KEY_OUTPUT);
            AiPortfolioProtocol.validate(request, output.toString());
            stage = PortfolioAiFailureDetails.Stage.SOURCES;
            facts.revalidate(request, output);
            String kind = OUTPUT_NEEDS_CLARIFICATION.equals(output.path(KEY_STATUS).asText()) ? RESULT_CLARIFICATION : RESULT_CANDIDATE;
            if (!kind.equals(stored.path(KEY_KIND).asText())) { throw new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID); }
            result.put(KEY_KIND, kind);
            if (RESULT_CLARIFICATION.equals(kind)) {
                result.put(KEY_AVAILABLE, true);
                result.put(KEY_QUESTIONS, JSON.parseArray(output.path(KEY_QUESTIONS).toString()));
                return response;
            }
            stage = PortfolioAiFailureDetails.Stage.COMPILATION;
            ObjectNode candidate = PortfolioAiCompiler.compile(task.getId(), request, output);
            stage = PortfolioAiFailureDetails.Stage.CONFIG;
            ObjectNode normalized = configs.validate(input, portfolio, candidate);
            // 冻结基准已规范化；第二次规范化不能制造未授权变化。
            stage = PortfolioAiFailureDetails.Stage.NORMALIZATION;
            PortfolioAiConfigService.requireExplicitValuesPreserved(candidate, normalized, "");
            stage = PortfolioAiFailureDetails.Stage.RENDER;
            Object renderData = JSON.parseObject(JSON.toJSONString(configs.render(input, portfolio, normalized)));
            // 只有完整普通候选已可展示时才创建图片快照；重新编译仍复用同一授权基准与字段保护规则。
            stage = PortfolioAiFailureDetails.Stage.SOURCES;
            ObjectNode effectiveRequest = shareImages.snapshot(task.getId(), request, output);
            if (!effectiveRequest.equals(request)) {
                stage = PortfolioAiFailureDetails.Stage.COMPILATION;
                candidate = PortfolioAiCompiler.compile(task.getId(), effectiveRequest, output);
                stage = PortfolioAiFailureDetails.Stage.CONFIG;
                normalized = configs.validate(input, portfolio, candidate);
                stage = PortfolioAiFailureDetails.Stage.NORMALIZATION;
                PortfolioAiConfigService.requireExplicitValuesPreserved(candidate, normalized, "");
                stage = PortfolioAiFailureDetails.Stage.RENDER;
                renderData = JSON.parseObject(JSON.toJSONString(configs.render(input, portfolio, normalized)));
            }
            boolean changes = !StrictAiJson.canonicalHash(AiPortfolioProtocol.baseConfig(request)).equals(StrictAiJson.canonicalHash(normalized));
            // 完整普通渲染也成功后才发布可采用字段，异常不能留下半成功候选。
            result.put(KEY_CANDIDATE, JSON.parseObject(normalized.toString()));
            result.put(KEY_HAS_CHANGES, changes);
            result.put(KEY_AVAILABLE, true);
            result.put(KEY_SUMMARY, changes ? PortfolioAiCandidateSummary.describe(AiPortfolioProtocol.baseConfig(request), normalized, effectiveRequest) : List.of(PortfolioAiMessage.NO_CHANGES));
            result.putAll(PortfolioAiCandidateSummary.sources(normalized, effectiveRequest));
            result.put(KEY_RENDER_DATA, renderData);
        } catch (BusinessException exception) {
            rejectCandidate(response, safeReason(exception), stage, exception);
        } catch (AiProtocolException exception) {
            rejectCandidate(response, ERROR_CONTRACT_UNAVAILABLE.equals(exception.getCode()) ? PortfolioAiMessage.CONTRACT_UNAVAILABLE : PortfolioAiMessage.CANDIDATE_INVALID, stage, exception);
        } catch (RuntimeException exception) {
            rejectCandidate(response, PortfolioAiMessage.CANDIDATE_INVALID, stage, exception);
        }
        return response;
    }

    /** 仅请求人且仍具有维护权可取消；独立归属列鉴权使损坏原文仍能安全释放额度。 */
    public Map<String, Object> cancel(Long taskId) {
        Long requester = AuthContextHolder.requireUserId();
        expire(requester);
        for (int i = 0; i < 3; i++) {
            AiGenerationTaskEntity task = requireTask(taskId, requester);
            requireTaskOwner(task, requester);
            if (TERMINAL.contains(task.getStatus())) { return status(task); }
            if (tasks.cancel(taskId, requester, task.getStatus(), task.getVersion(), PortfolioAiTime.now()) == 1) {
                return status(requireTask(taskId, requester));
            }
        }
        return status(requireTask(taskId, requester));
    }

    /** 任务查询永远不返回 request_json、result_json、prompt 或真实绑定原值。 */
    private Map<String, Object> status(AiGenerationTaskEntity task) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(KEY_TASK_ID, task.getId().toString());
        response.put(KEY_STATUS, task.getStatus());
        response.put(KEY_ATTEMPT_COUNT, task.getAttemptCount());
        response.put(KEY_POLL_AFTER_MS, INITIAL_POLL_MS);
        response.put(KEY_ERROR_CODE, task.getErrorCode());
        if (AiTaskStatusDict.FAILED.getCode().equals(task.getStatus())) {
            response.put(KEY_ERROR_MESSAGE, PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
            response.put(KEY_ERROR, PortfolioAiFailureDetails.task(task.getErrorCode()));
        }
        return response;
    }

    /** 在读取记录原文前确认请求人及有效期。 */
    private AiGenerationTaskEntity requireTask(Long id, Long requester) {
        AiGenerationTaskEntity task = tasks.selectById(id);
        if (task == null || !requester.equals(task.getRequestedBy())) { throw new BusinessException(PortfolioAiMessage.TASK_NOT_FOUND); }
        requireRetained(task);
        return task;
    }
    /** 原始 JSON 不承担授权职责；先根据独立列校验真实作品集、归属和当前维护权。 */
    private PortfolioEntity requireTaskOwner(AiGenerationTaskEntity task, Long requester) {
        if ((!OWNER_USER.equals(task.getOwnerType()) && !OWNER_TEAM.equals(task.getOwnerType()))
                || task.getOwnerId() == null || task.getOwnerId() <= 0
                || task.getPortfolioId() != null && task.getPortfolioId() <= 0) {
            throw new BusinessException(PortfolioAiMessage.TASK_NOT_FOUND);
        }
        ObjectNode identity = NODES.objectNode().put(KEY_OWNER_TYPE, task.getOwnerType())
                .put(KEY_OWNER_ID, task.getOwnerId().toString());
        identity.put(KEY_PORTFOLIO_ID, task.getPortfolioId() == null ? null : task.getPortfolioId().toString());
        return facts.requireOwner(identity, requester);
    }

    /** 候选完成全部校验之前只返回不可采用的空结果，异常不会泄露半成品。 */
    private Map<String, Object> unavailableCandidate(Map<String, Object> response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_AVAILABLE, false);
        result.put(KEY_CANDIDATE, null);
        result.put(KEY_HAS_CHANGES, null);
        result.put(KEY_QUESTIONS, List.of());
        result.put(KEY_SUMMARY, List.of());
        response.put(KEY_RESULT, result);
        return result;
    }
    /** 出错时重建空候选，保留旧文案并追加安全原因，不能残留已填充的半成功字段。 */
    private void rejectCandidate(Map<String, Object> response, String legacyReason, PortfolioAiFailureDetails.Stage stage, RuntimeException exception) {
        Object previous = response.get(KEY_RESULT);
        Object kind = previous instanceof Map<?, ?> value ? value.get(KEY_KIND) : null;
        Map<String, Object> result = unavailableCandidate(response);
        if (RESULT_CANDIDATE.equals(kind) || RESULT_CLARIFICATION.equals(kind)) { result.put(KEY_KIND, kind); }
        result.put(KEY_UNAVAILABLE_REASON, legacyReason);
        Map<String, String> detail = PortfolioAiFailureDetails.candidate(stage, exception);
        result.put(KEY_ERROR, detail);
        // 仅记录固定阶段、分类及异常类型，不输出异常原文、模型结果或资源地址。
        log.warn("高级 AI 候选不可用 taskId={}, stage={}, errorCode={}, exceptionType={}",
                response.get(KEY_TASK_ID), stage, detail.get(KEY_CODE), exception.getClass().getSimpleName());
    }
    /** 终态到期立即不可查询，无需等待物理清理周期。 */
    private void requireRetained(AiGenerationTaskEntity task) {
        if (TERMINAL.contains(task.getStatus()) && task.getCompletedAt() != null
                && !task.getCompletedAt().plusDays(properties.getRetentionDays()).isAfter(PortfolioAiTime.now())) {
            throw new BusinessException(PortfolioAiMessage.TASK_NOT_FOUND);
        }
    }
    /** 草稿基准不一致时只拒绝候选，保持本地编辑与终态记录。 */
    private void requireRevision(ObjectNode input, PortfolioEntity portfolio) {
        int revision = portfolio == null || portfolio.getDraftRevision() == null ? 0 : portfolio.getDraftRevision();
        if (revision != input.path(KEY_BASE_DRAFT_REVISION).intValue()) { throw new BusinessException(PortfolioAiMessage.STALE_DRAFT); }
    }
    /** 期限收口更新使旧执行代次无效。 */
    private void expire(Long requester) {
        LocalDateTime now = PortfolioAiTime.now();
        tasks.expireRequester(requester, now, now.minusSeconds(properties.getTotalTimeoutSeconds()));
    }
    /** 内部记录只在固定预算内解析，冻结输入归属必须与已鉴权的独立列一致。 */
    private ObjectNode parseTask(AiGenerationTaskEntity task) {
        ObjectNode request = StrictAiJson.parseObject(task.getRequestJson(), TASK_MAX_BYTES, properties.getMaxJsonDepth() + WRAPPER_DEPTH);
        if (!(request.get(KEY_INPUT) instanceof ObjectNode input)
                || !task.getOwnerType().equals(PortfolioAiInput.text(input, KEY_OWNER_TYPE))
                || !task.getOwnerId().equals(PortfolioAiInput.id(input, KEY_OWNER_ID, false))
                || !Objects.equals(task.getPortfolioId(), PortfolioAiInput.id(input, KEY_PORTFOLIO_ID, true))) {
            throw new BusinessException(PortfolioAiMessage.CANDIDATE_INVALID);
        }
        return request;
    }
    /** 只回传固定来源、修订和契约错误，不回传内部原文。 */
    private static String safeReason(BusinessException exception) {
        return Set.of(PortfolioAiMessage.STALE_DRAFT, PortfolioAiMessage.SOURCE_UNAVAILABLE, PortfolioAiMessage.CONFIG_LIMIT)
                .contains(exception.getMessage()) ? exception.getMessage() : PortfolioAiMessage.CANDIDATE_INVALID;
    }
}
