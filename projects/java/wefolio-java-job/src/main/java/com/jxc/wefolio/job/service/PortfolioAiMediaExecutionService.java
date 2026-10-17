package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import com.jxc.wefolio.job.model.PortfolioAiMediaSummaryIdentity;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiStoredVideoSummary;
import com.jxc.wefolio.service.portfolioai.PortfolioAiVideoAudioContext;
import com.jxc.wefolio.service.portfolioai.PortfolioAiFrameSelection;
import com.jxc.wefolio.service.portfolioai.AiPortfolioProtocol;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

/** 按作品理解真实媒体、复用安全摘要，再以原v1协议生成；费用在每次发送前持久预留。 */
@Service
@Slf4j
public class PortfolioAiMediaExecutionService {
    /** 固定业务时区，与原任务总期限一致。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** 每次模型最大输出上限。 */
    private static final int OUTPUT_TOKENS = PortfolioAiProperties.MAX_OUTPUT_TOKENS;
    /** 原作品事实的媒体类型字段，不扩大v1输出合同。 */
    private static final String MEDIA_TYPE_FIELD = "mediaType";
    /** 摘要缓存身份同时保留结构版本与实际请求提示词身份。 */
    private static final String SUMMARY_PROMPT_VERSION_FIELD = "promptVersion";
    /** 旧异常未携带结构诊断时使用固定占位，不回显异常原文。 */
    private static final String SUMMARY_DIAGNOSTIC_UNAVAILABLE = "UNAVAILABLE";
    /** 摘要失败发生的可信应用阶段，不取模型或用户提供的字符串。 */
    private enum SummaryStage {
        /** 本次模型响应。 */ MODEL,
        /** 作品摘要缓存。 */ CACHE,
        /** 任务冻结的视频摘要。 */ FROZEN
    }
    /** 摘要协议中的文本与条目上限，准入上界与实际校验保持一致。 */
    private static final int SUMMARY_MAX_CODE_POINTS = PortfolioAiStoredVideoSummary.SUMMARY_MAX_CODE_POINTS;
    private static final int ITEM_MAX_CODE_POINTS = PortfolioAiStoredVideoSummary.ITEM_MAX_CODE_POINTS;
    private static final int OBSERVATIONS_MAX_COUNT = PortfolioAiStoredVideoSummary.OBSERVATIONS_MAX_COUNT;
    private static final int LIMITATIONS_MAX_COUNT = PortfolioAiStoredVideoSummary.LIMITATIONS_MAX_COUNT;
    /** 此码点在内层 JSON 和 HTTP JSON 中双重转义，形成每码点字节数的保守上界。 */
    private static final String MAX_ESCAPED_CODE_POINT = String.valueOf((char) 1);
    /** 缓存及预算短事务访问器。 */
    private final PortfolioAiMediaRepository repository;
    /** 当前授权与COS源验证。 */
    private final PortfolioAiMediaAccessService access;
    /** 已有TokenHub有界传输。 */
    private final PortfolioAiModelClient client;
    /** 启动开关及不可延期任务期限。 */
    private final PortfolioAiProperties properties;

    /** 参考附件当前授权和私有原件版本核验。 */
    private final PortfolioAiReferenceAccessService references;

    /** 生产装配保持原媒体模块深度，附件只增加明确边界。 */
    @Autowired
    public PortfolioAiMediaExecutionService(PortfolioAiMediaRepository repository, PortfolioAiMediaAccessService access,
            PortfolioAiModelClient client, PortfolioAiProperties properties, PortfolioAiReferenceAccessService references) {
        this.repository = repository; this.access = access; this.client = client; this.properties = properties; this.references = references;
    }

    /** 旧无附件单测继续沿用原构造，附件路径显式要求完整依赖。 */
    PortfolioAiMediaExecutionService(PortfolioAiMediaRepository repository, PortfolioAiMediaAccessService access,
            PortfolioAiModelClient client, PortfolioAiProperties properties) {
        this(repository, access, client, properties, null);
    }

    /** 以 Runtime 已接单的冻结媒体版本为执行合同，不被 Job 局部开关撤销。 */
    public String generate(AiGenerationTaskEntity task, ObjectNode frozen, Runnable checkOwned) {
        checkOwned.run();
        String planVersion = frozen.path(KEY_FACTS).path(KEY_MEDIA_PLAN).path(KEY_VERSION).asText();
        boolean hasWorkPlan = frozen.path(KEY_FACTS).has(KEY_MEDIA_PLAN);
        if (hasWorkPlan && !STORED_SUMMARY_PLAN_VERSION.equals(planVersion) && !PLAN_VERSION.equals(planVersion) && !LEGACY_PLAN_VERSION.equals(planVersion)) {
            throw failed(ERROR_CONTRACT_UNAVAILABLE, "真实媒体读取暂未启用");
        }
        ObjectNode request = frozen.deepCopy();
        JsonNode entries = hasWorkPlan ? request.path(KEY_FACTS).path(KEY_MEDIA_PLAN).path(KEY_ENTRIES) : StrictAiJson.mapper().createArrayNode();
        List<JsonNode> referenceItems = PortfolioAiReferenceContract.items(task.getReferenceAttachmentsJson(), false);
        List<List<JsonNode>> referenceGroups = PortfolioAiReferenceContract.groups(referenceItems);
        if (!referenceItems.isEmpty() && references == null) { throw failed(ERROR_CONTRACT_UNAVAILABLE, "参考附件模块不可用"); }
        if (!entries.isArray() || entries.size() > AiPortfolioProtocol.limits().path(KEY_MAX_SOURCE_COUNT).asInt()) { throw failed(ERROR_MEDIA_INVALID, "媒体输入计划不合法"); }
        if (hasWorkPlan) { validateSelectedSources(request, entries); }
        validateRoutes(task, entries, STORED_SUMMARY_PLAN_VERSION.equals(planVersion));
        try { PortfolioAiVideoAudioContext.validateAndNormalize(entries, !LEGACY_PLAN_VERSION.equals(planVersion)); }
        catch (AiProtocolException exception) { throw failed(ERROR_MEDIA_INVALID, exception.getMessage()); }
        for (JsonNode entry : entries) { attachAudio(request, entry); }
        List<PreparedMedia> prepared = new ArrayList<>();
        ObjectNode upperBound = request.deepCopy();
        long requiredCents = 0;
        int requiredCalls = 1;
        // 先验证全部来源并以最大合法摘要估算最终真实请求，额度不足时还没有任何模型付费。
        for (JsonNode entry : entries) {
            checkOwned.run();
            remaining(task);
            access.verify(task, entry);
            boolean stored = ROUTE_STORED_VIDEO_SUMMARY.equals(entry.path(KEY_ROUTE).asText());
            PortfolioAiMediaSummaryIdentity identity = stored ? null : cacheIdentity(entry, request);
            long workId = entry.path(KEY_WORK_ID).asLong();
            String cached = stored ? null : repository.cached(workId, identity, now());
            ObjectNode summary = stored ? storedContent(task, entry) : cached == null ? null
                    : validateSummary(cached, task, entry, SummaryStage.CACHE, null);
            int cents = summary == null ? (int) PortfolioAiMediaRepository.costCents(
                    estimatedSummaryInputTokens(entry), SUMMARY_OUTPUT_MAX_TOKENS) : 0;
            prepared.add(new PreparedMedia(entry, identity, summary, cents));
            attach(upperBound, entry.path(KEY_SOURCE_NO).asInt(), summary == null ? maximumSummary() : summary);
            requiredCents += cents;
            if (summary == null) { requiredCalls++; }
        }
        if (!referenceItems.isEmpty()) {
            checkOwned.run(); remaining(task); references.verify(task, referenceItems);
            ((ObjectNode) upperBound.path(KEY_FACTS)).set(PortfolioAiReferenceContract.SUMMARIES,
                    PortfolioAiReferenceContract.maximumSummaries(referenceItems));
            for (List<JsonNode> group : referenceGroups) {
                if (PortfolioAiReferencePrompt.estimatedInput(group) > PortfolioAiReferenceContract.INPUT_TOKENS) {
                    throw failed(ERROR_MEDIA_INVALID, "参考附件输入超过分析上限");
                }
            }
            if (client.textRequestBytes(task, upperBound) > PortfolioAiReferenceContract.INPUT_TOKENS) {
                throw failed(ERROR_BUDGET_EXHAUSTED, "参考摘要与作品内容超过布局输入上限");
            }
            requiredCents += referenceGroups.size() * referenceReservation();
            requiredCalls += referenceGroups.size();
        }
        requiredCents += textReservation(task, upperBound);
        if (requiredCents > Integer.MAX_VALUE) { throw failed(ERROR_BUDGET_EXHAUSTED, "生成输入超过预算"); }
        repository.checkCapacity(task, (int) requiredCents, requiredCalls, now());
        for (PreparedMedia media : prepared) {
            JsonNode entry = media.entry();
            checkOwned.run(); remaining(task); access.verifyCurrent(task, entry);
            ObjectNode summary = media.summary();
            if (summary == null) {
                if (!referenceItems.isEmpty()) { references.verify(task, referenceItems); }
                var signed = access.signedAfterVerification(task, entry);
                checkOwned.run();
                remaining(task);
                int call = repository.reserve(task, media.cents(), now());
                Duration timeout = remainingBeforeSend(task, call, checkOwned);
                // 发送失败/进程中断/未知usage均保留完整预留；绝不按未读到usage假设免费。
                var completion = client.summarize(task, request, signed, entry, timeout);
                repository.settle(task, call, completion.promptTokens(), completion.completionTokens(),
                        completion.cachedTokens(), completion.cacheStatus(), now());
                checkOwned.run();
                summary = validateSummary(completion.content(), task, entry, SummaryStage.MODEL, call);
                // 先复核当前数据库权限与源身份并缓存已付费摘要；后续瞬时 HEAD 故障可重试复用。
                access.verifyCurrent(task, entry);
                repository.cache(entry.path(KEY_WORK_ID).asLong(), media.identity(), summary.toString(), now());
            }
            attach(request, entry.path(KEY_SOURCE_NO).asInt(), summary);
        }
        if (!referenceItems.isEmpty()) {
            var summaries = StrictAiJson.mapper().createArrayNode();
            for (List<JsonNode> group : referenceGroups) {
                checkOwned.run(); remaining(task); references.verify(task, referenceItems);
                var signed = references.signedAfterVerification(group);
                checkOwned.run(); remaining(task);
                int referenceCall = repository.reserve(task, referenceReservation(), now());
                var completion = client.summarizeReferences(task, request, signed, group,
                        remainingBeforeSend(task, referenceCall, checkOwned));
                repository.settle(task, referenceCall, completion.promptTokens(), completion.completionTokens(),
                        completion.cachedTokens(), completion.cacheStatus(), now());
                checkOwned.run(); remaining(task);
                try {
                    summaries.addAll(PortfolioAiReferenceContract.summaries(completion.content(), group));
                } catch (PortfolioAiReferenceContract.SummaryValidationException exception) {
                    // 只输出固定分类和数值，不记录模型原文、附件标识、资源地址或异常堆栈。
                    log.warn("高级 AI 参考附件摘要校验失败 taskId={}, attempt={}, callNo={}, itemCount={}, promptTokens={}, completionTokens={}, errorCode={}, diagnostic={}",
                            task.getId(), task.getAttemptCount(), referenceCall, group.size(),
                            completion.promptTokens(), completion.completionTokens(), PortfolioAiReferenceContract.ERROR_ANALYSIS,
                            exception.diagnostic());
                    throw exception;
                }
            }
            var ordered = StrictAiJson.mapper().createArrayNode();
            for (JsonNode item : referenceItems) {
                for (JsonNode summary : summaries) {
                    if (item.path(PortfolioAiReferenceContract.NUMBER).asText().equals(summary.path(PortfolioAiReferenceContract.NUMBER).asText())) {
                        ordered.add(((ObjectNode) summary).deepCopy().put(PortfolioAiReferenceContract.NOTE, item.path(PortfolioAiReferenceContract.NOTE).asText())); break;
                    }
                }
            }
            ((ObjectNode) request.path(KEY_FACTS)).set(PortfolioAiReferenceContract.SUMMARIES, ordered);
        }
        checkOwned.run();
        access.requireOwner(task);
        if (!referenceItems.isEmpty()) { references.verify(task, referenceItems); }
        for (JsonNode entry : entries) { checkOwned.run(); remaining(task); access.verifyCurrent(task, entry); }
        checkOwned.run();
        remaining(task);
        int call = repository.reserve(task, textReservation(task, request), now());
        Duration timeout = remainingBeforeSend(task, call, checkOwned);
        var completion = client.generateWithUsage(task, request, timeout);
        repository.settle(task, call, completion.promptTokens(), completion.completionTokens(),
                completion.cachedTokens(), completion.cacheStatus(), now());
        checkOwned.run();
        // 晚到返回不能把已撤权、源替换或到期任务的结果发布为成功。
        remaining(task);
        for (JsonNode entry : entries) { checkOwned.run(); access.verify(task, entry); }
        if (!referenceItems.isEmpty()) { references.verify(task, referenceItems); }
        return completion.content();
    }

    /** 金额按完整上下文预留，不能把经验视频估计当成实际费用保证。 */
    private static int referenceReservation() {
        return (int) PortfolioAiMediaRepository.costCents(PortfolioAiReferenceContract.INPUT_TOKENS,
                PortfolioAiReferenceContract.OUTPUT_TOKENS);
    }

    /** 仅包围模型客户端调用之前的检查，已进入客户端的异常绝不能据此退款。 */
    private Duration remainingBeforeSend(AiGenerationTaskEntity task, int call, Runnable checkOwned) {
        try {
            checkOwned.run();
            return remaining(task);
        } catch (RuntimeException exception) {
            try { repository.releaseUnsent(task, call); }
            catch (RuntimeException refundException) { exception.addSuppressed(refundException); }
            throw exception;
        }
    }

    /** 真实最终请求的全部 UTF-8 字节是输入 token 上界，初始准入和发送前使用同一估价。 */
    private int textReservation(AiGenerationTaskEntity task, ObjectNode request) {
        long reserve = PortfolioAiMediaRepository.costCents(client.textRequestBytes(task, request),
                request.path(KEY_MODEL_CONFIG).path(KEY_MAX_TOKENS).asInt(OUTPUT_TOKENS));
        if (reserve > Integer.MAX_VALUE) { throw failed(ERROR_BUDGET_EXHAUSTED, "生成输入超过预算"); }
        return (int) Math.max(1, reserve);
    }

    /** 不改变摘要语义，以允许的控制字符双重转义覆盖最大合法模型返回。 */
    private static ObjectNode maximumSummary() {
        ObjectNode summary = StrictAiJson.mapper().createObjectNode().put(SUMMARY, MAX_ESCAPED_CODE_POINT.repeat(SUMMARY_MAX_CODE_POINTS));
        var observations = summary.putArray(OBSERVATIONS);
        for (int index = 0; index < OBSERVATIONS_MAX_COUNT; index++) { observations.add(MAX_ESCAPED_CODE_POINT.repeat(ITEM_MAX_CODE_POINTS)); }
        var limitations = summary.putArray(LIMITATIONS);
        for (int index = 0; index < LIMITATIONS_MAX_COUNT; index++) { limitations.add(MAX_ESCAPED_CODE_POINT.repeat(ITEM_MAX_CODE_POINTS)); }
        return summary;
    }

    /** 初始复核的缓存或未付费步骤，只在本次执行内存持有。 */
    private record PreparedMedia(JsonNode entry, PortfolioAiMediaSummaryIdentity identity, ObjectNode summary, int cents) { }

    /** 从受限路线重新估算输入，不信任冻结 JSON 自报的估算数值。 */
    private static long estimatedSummaryInputTokens(JsonNode entry) {
        return estimatedMediaInputTokens(entry) + PortfolioAiVideoAudioContext.inputTokenUpperBound(entry);
    }

    /** 媒体本身的保守上界独立于可选文字，均由实际路线重新计算。 */
    private static long estimatedMediaInputTokens(JsonNode entry) {
        String route = entry.path(KEY_ROUTE).asText();
        if (ROUTE_IMAGE.equals(route)) { return IMAGE_ESTIMATED_INPUT_TOKENS; }
        if (ROUTE_FRAMES.equals(route)) {
            JsonNode frames = entry.path(KEY_FRAMES);
            if (!frames.isArray() || !isSupportedFrameCount(frames.size())) {
                throw failed(ERROR_MEDIA_INVALID, "媒体帧输入数量不合法");
            }
            if (entry.has(PortfolioAiFrameSelection.FIELD)) {
                return PortfolioAiFrameSelection.estimatedInputTokens(entry.path(PortfolioAiFrameSelection.FIELD));
            }
            return FRAMES_INPUT_OVERHEAD_TOKENS + FRAME_ESTIMATED_INPUT_TOKENS * frames.size();
        }
        JsonNode duration = entry.path(KEY_DURATION_MS);
        if (!ROUTE_VIDEO.equals(route) || !duration.isIntegralNumber() || !duration.canConvertToInt()
                || duration.asInt() <= 0 || duration.asInt() > DIRECT_VIDEO_MAX_DURATION_MS) {
            throw failed(ERROR_MEDIA_INVALID, "直传视频输入时长不合法");
        }
        long estimate = estimateVideoInputTokens(duration.asInt());
        if (estimate > DIRECT_VIDEO_MAX_INPUT_TOKENS) { throw failed(ERROR_MEDIA_INVALID, "直传视频输入超过限制"); }
        return estimate;
    }

    /** 在任何缓存、签名和付费动作之前，冻结计划必须与当前冻结来源一一对应。 */
    private void validateSelectedSources(ObjectNode request, JsonNode entries) {
        JsonNode sources = request.path(KEY_FACTS).path(KEY_SOURCES);
        if (!sources.isArray()) { throw failed(ERROR_MEDIA_INVALID, "媒体来源清单不合法"); }
        Map<Integer, JsonNode> indexed = new HashMap<>();
        for (JsonNode source : sources) {
            JsonNode number = source.path(KEY_SOURCE_NO);
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.asInt() <= 0
                    || indexed.putIfAbsent(number.asInt(), source) != null) {
                throw failed(ERROR_MEDIA_INVALID, "媒体来源编号重复或不合法");
            }
        }
        Set<Integer> planned = new HashSet<>();
        for (JsonNode entry : entries) {
            JsonNode number = entry.path(KEY_SOURCE_NO); JsonNode work = entry.path(KEY_WORK_ID);
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.asInt() <= 0
                    || !planned.add(number.asInt()) || !work.isIntegralNumber() || !work.canConvertToLong() || work.asLong() <= 0) {
                throw failed(ERROR_MEDIA_INVALID, "媒体计划来源编号或作品身份不合法");
            }
            JsonNode source = indexed.get(number.asInt());
            if (source == null || !SOURCE_WORK.equals(source.path(KEY_KIND).asText())
                    || !source.path(KEY_CAN_BIND).isBoolean() || !source.path(KEY_CAN_BIND).asBoolean()
                    || !Long.toString(work.asLong()).equals(source.path(KEY_SOURCE_ID).asText())) {
                throw failed(ERROR_MEDIA_INVALID, "媒体计划与可绑定作品来源不一致");
            }
        }
        // 图片和视频不可通过丢失entry静默变为文字；其它类型保留明确的未读取说明。
        for (JsonNode source : sources) {
            String type = source.path(MEDIA_TYPE_FIELD).asText();
            if (SOURCE_WORK.equals(source.path(KEY_KIND).asText()) && source.path(KEY_CAN_BIND).asBoolean()
                    && (ROUTE_IMAGE.equals(type) || ROUTE_VIDEO.equals(type))
                    && !planned.contains(source.path(KEY_SOURCE_NO).asInt())) {
                throw failed(ERROR_MEDIA_INVALID, "所选图片或视频缺少冻结媒体计划");
            }
        }
    }

    /** 摘要按源、模型、真实提示词及实际有序输入复用；选帧策略和预算元数据不影响等价输入。 */
    static String cacheKey(JsonNode entry, JsonNode request) {
        ObjectNode identity = StrictAiJson.mapper().createObjectNode()
                .put(KEY_SOURCE_VERSION, entry.path(KEY_SOURCE_VERSION).asText())
                .put(KEY_MODEL, request.path(KEY_MODEL_CONFIG).path(KEY_MODEL).asText())
                .put(KEY_SCHEMA_VERSION, SUMMARY_VERSION)
                .put(SUMMARY_PROMPT_VERSION_FIELD, PortfolioAiMediaSummaryPrompt.version())
                .put(KEY_ROUTE, entry.path(KEY_ROUTE).asText())
                .put(PortfolioAiVideoAudioContext.INPUT_HASH, PortfolioAiVideoAudioContext.inputHash(entry.path(PortfolioAiVideoAudioContext.TEXT).asText("")))
                .put(PortfolioAiVideoAudioContext.POLICY, entry.path(PortfolioAiVideoAudioContext.POLICY).asText(""))
                .put(PortfolioAiVideoAudioContext.EXCERPT, entry.path(PortfolioAiVideoAudioContext.EXCERPT).asBoolean());
        if (ROUTE_FRAMES.equals(entry.path(KEY_ROUTE).asText())) {
            JsonNode input = entry.has(PortfolioAiFrameSelection.FIELD)
                    ? entry.path(PortfolioAiFrameSelection.FIELD).path(KEY_FRAMES) : entry.path(KEY_FRAMES);
            List<JsonNode> ordered = new ArrayList<>(); input.forEach(ordered::add);
            ordered.sort(Comparator.comparingLong(frame -> frame.path(KEY_TIME_MS).asLong()));
            var frames = identity.putArray(KEY_FRAMES);
            for (JsonNode frame : ordered) {
                frames.addObject().put(KEY_OBJECT_KEY, frame.path(KEY_OBJECT_KEY).asText())
                        .put(KEY_BYTES, frame.path(KEY_BYTES).asLong()).put(KEY_TIME_MS, frame.path(KEY_TIME_MS).asLong());
            }
        }
        return StrictAiJson.canonicalHash(identity);
    }

    /** 缓存身份独立于作品帧清单，额外源字段仅用于写入时的守卫。 */
    private static PortfolioAiMediaSummaryIdentity cacheIdentity(JsonNode entry, JsonNode request) {
        return new PortfolioAiMediaSummaryIdentity(cacheKey(entry, request), entry.path(KEY_SOURCE_VERSION).asText(),
                request.path(KEY_MODEL_CONFIG).path(KEY_MODEL).asText(), PortfolioAiMediaSummaryPrompt.version(),
                entry.path(KEY_ROUTE).asText(), entry.path(KEY_MANIFEST_HASH).asText(),
                entry.path(KEY_SOURCE_OBJECT_KEY).asText(), entry.path(KEY_SOURCE_ETAG).asText(),
                entry.path(KEY_SOURCE_BYTES).asLong(), entry.path(PortfolioAiVideoAudioContext.FULL_HASH)
                        .asText(PortfolioAiVideoAudioContext.fullHash(null)));
    }

    /** 最终生成使用同一冻结文字；执行中不得查询并补入较新的转写。 */
    private void attachAudio(ObjectNode request, JsonNode entry) {
        for (JsonNode source : request.path(KEY_FACTS).path(KEY_SOURCES)) {
            if (source.path(KEY_SOURCE_NO).asInt() == entry.path(KEY_SOURCE_NO).asInt()) {
                PortfolioAiVideoAudioContext.project(entry, (ObjectNode) source);
                return;
            }
        }
    }

    /** 摘要只能作为当前素材的参考事实，不改变原sourceNo或绑定句柄。 */
    private void attach(ObjectNode request, int sourceNo, ObjectNode summary) {
        for (JsonNode source : request.path(KEY_FACTS).path(KEY_SOURCES)) {
            if (source.path(KEY_SOURCE_NO).asInt() == sourceNo && source.path(KEY_CAN_BIND).asBoolean()
                    && SOURCE_WORK.equals(source.path(KEY_KIND).asText())) {
                ((ObjectNode) source).set(KEY_MEDIA_SUMMARY, summary); return;
            }
        }
        throw failed(ERROR_MEDIA_INVALID, "媒体摘要来源不存在");
    }

    /** 共用原视频与帧兜底的内容校验，应用层保留既有摘要失败分类。 */
    static ObjectNode validateSummary(String raw) {
        return validateSummary(raw, null, null, null, null);
    }

    /** 在异常转换前记录可信诊断，校验规则、错误文案、费用和重试语义保持不变。 */
    private static ObjectNode validateSummary(String raw, AiGenerationTaskEntity task, JsonNode entry,
                                              SummaryStage stage, Integer callNo) {
        try { return PortfolioAiStoredVideoSummary.validateContent(raw); }
        catch (AiProtocolException exception) {
            logSummaryFailure(task, entry, stage, callNo, exception);
            throw failed(ERROR_INVALID_SUMMARY, exception.getMessage());
        }
    }

    /** 冻结摘要只按任务内容读取，不能临时改用更新后的字段或调用模型补救。 */
    private static ObjectNode storedContent(AiGenerationTaskEntity task, JsonNode entry) {
        try { return PortfolioAiStoredVideoSummary.frozenContent(entry); }
        catch (AiProtocolException exception) {
            logSummaryFailure(task, entry, SummaryStage.FROZEN, null, exception);
            throw failed(ERROR_INVALID_SUMMARY, "冻结视频摘要不合法");
        }
    }

    /** 仅输出任务身份和合同内固定诊断，不输出摘要正文、资源地址或异常堆栈。 */
    private static void logSummaryFailure(AiGenerationTaskEntity task, JsonNode entry,
                                          SummaryStage stage, Integer callNo, AiProtocolException exception) {
        if (task == null) { return; }
        String diagnostic = exception instanceof PortfolioAiStoredVideoSummary.ValidationException validation
                ? validation.diagnostic() : SUMMARY_DIAGNOSTIC_UNAVAILABLE;
        log.warn("高级 AI 媒体摘要校验失败 taskId={}, attempt={}, workId={}, sourceNo={}, stage={}, callNo={}, errorCode={}, diagnostic={}",
                task.getId(), task.getAttemptCount(), entry.path(KEY_WORK_ID).asLong(), entry.path(KEY_SOURCE_NO).asInt(),
                stage, callNo, ERROR_INVALID_SUMMARY, diagnostic);
    }

    /** 新路线只属于 v3；v3 视频缺少摘要只能使用帧，审核轮次不得缺失。 */
    private static void validateRoutes(AiGenerationTaskEntity task, JsonNode entries, boolean storedVersion) {
        for (JsonNode entry : entries) {
            String route = entry.path(KEY_ROUTE).asText();
            if (storedVersion) {
                if (ROUTE_VIDEO.equals(route) || !entry.path(KEY_AUDIT_ROUND).isIntegralNumber()
                        || !entry.path(KEY_AUDIT_ROUND).canConvertToInt() || entry.path(KEY_AUDIT_ROUND).asInt() < 0) {
                    throw failed(ERROR_MEDIA_INVALID, "视频摘要媒体合同不合法");
                }
            } else if (ROUTE_STORED_VIDEO_SUMMARY.equals(route) || entry.has(PortfolioAiStoredVideoSummary.FIELD)) {
                throw failed(ERROR_MEDIA_INVALID, "旧媒体合同不可使用持久视频摘要");
            }
            if (ROUTE_STORED_VIDEO_SUMMARY.equals(route)) {
                if (entry.has(KEY_FRAMES) || entry.has(KEY_MANIFEST_HASH) || entry.has(PortfolioAiFrameSelection.FIELD)) {
                    throw failed(ERROR_MEDIA_INVALID, "持久视频摘要不能混入帧输入");
                }
                storedContent(task, entry);
            } else if (entry.has(PortfolioAiStoredVideoSummary.FIELD)) {
                throw failed(ERROR_MEDIA_INVALID, "媒体路线与持久视频摘要不一致");
            }
        }
    }

    /** 每个远端阶段重新计算不可延期的剩余期限。 */
    private Duration remaining(AiGenerationTaskEntity task) {
        Duration duration = Duration.between(now(), task.getCreatedAt().plusSeconds(properties.getTotalTimeoutSeconds()));
        if (duration.isNegative() || duration.isZero() || Thread.currentThread().isInterrupted()) { throw failed(ERROR_TIMEOUT, "媒体处理超过任务期限"); }
        return duration;
    }
    /** 统一时间来源。 */
    private LocalDateTime now() { return LocalDateTime.now(BUSINESS_ZONE); }
    /** 不可自动降级的媒体业务失败。 */
    private static PortfolioAiCallException failed(String code, String reason) { return new PortfolioAiCallException(code, reason, false); }
}
