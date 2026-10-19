package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.VideoSummaryProperties;
import com.jxc.wefolio.job.repo.VideoSummaryRepository;
import com.jxc.wefolio.job.repo.VideoSummaryRepository.Snapshot;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import com.jxc.wefolio.service.portfolioai.PortfolioAiStoredVideoSummary;
import com.jxc.wefolio.service.portfolioai.PortfolioAiVideoAudioContext;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.qcloud.cos.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.LIMITATIONS;
import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.KEY_SOURCE_VERSION;
import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.OBSERVATIONS;

/** 原视频摘要用例：独立准入、预算、互斥、单次请求与带源身份的条件发布。 */
@Service
@Slf4j
public class VideoSummaryService {
    /** 审核回调无需重复检查已经终态的审核任务租约。 */
    private static final Runnable NO_CHECK = () -> { };
    /** 账户问题停止整轮后续摘要，而不是尝试其它作品继续收费。 */
    private static final String ACCOUNT_UNAVAILABLE = "AI_UNAVAILABLE";
    /** 签名链接在模型总期限外另留两分钟读取余量。 */
    private static final int URL_GRACE_SECONDS = 120;
    /** 模型成功后保留最终源复验之外的 CAS 和解析余量。 */
    private static final int SAVE_GRACE_SECONDS = 5;
    /** 普通协议异常没有摘要字段路径时使用固定标记，不能回显模型正文。 */
    private static final String DIAGNOSTIC_UNAVAILABLE = "UNAVAILABLE";
    /** 无栈异常使用固定标记，不尝试读取可能包含远端原文的异常消息。 */
    private static final String LOCATION_UNAVAILABLE = "UNKNOWN";
    /** 推荐名称仅供可选改名，不进入共享摘要正文。 */
    private static final String SUGGESTED_TITLE = "suggestedTitle";
    /** 模型完整响应沿用摘要正文的严格解析规模。 */
    private static final int MAX_MODEL_CONTENT_BYTES = 16 * 1024, MAX_MODEL_CONTENT_DEPTH = 8;
    /** 自动推荐名称按 Unicode 码点计数，不改变用户名称的原有长度限制。 */
    private static final int MAX_TITLE_CODE_POINTS = 10;
    /** 精确匹配小程序相册上传生成的名称，序号只允许规范的正整数形式。 */
    private static final Pattern DEFAULT_VIDEO_TITLE = Pattern.compile(
            "视频 ([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}) (?:0[1-9]|[1-9][0-9]+)");
    /** 严格校验日期，拒绝不存在的日期和越界时间。 */
    private static final DateTimeFormatter DEFAULT_TITLE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    /** 阶段名只用于定位失败，不改变准入、重试、预算或保存规则。 */
    private enum Stage {
        DEADLINE_CHECK, SOURCE_READ, SOURCE_HEAD, SUMMARY_CHECK, SOURCE_REVALIDATE,
        BUDGET_RESERVE, LOCK_ACQUIRE, SIGN_URL, MODEL_CALL, USAGE_ACCOUNT,
        CONTENT_VALIDATE, DOCUMENT_BUILD, SOURCE_VERIFY, SAVE, LOCK_RELEASE
    }
    /** 独立开关、规格与费用上限。 */
    private final VideoSummaryProperties properties;
    /** 权限和源快照及条件写入。 */
    private final VideoSummaryRepository repository;
    /** 复用音频的同作品跨实例锁，不允许在持音频锁时再领取。 */
    private final VideoAudioWorkLock workLock;
    /** 同桶受控 HEAD 和短期读取链接能力。 */
    private final VideoFrameStorageService storage;
    /** 与作品集无关的原视频模型请求。 */
    private final TokenHubVideoSummaryClient client;
    /** 实际执行期间的实例并发许可，预检不持有。 */
    private final Semaphore capacity;

    /** 构造摘要用例，默认不执行任何远端调用。 */
    public VideoSummaryService(VideoSummaryProperties properties, VideoSummaryRepository repository,
            VideoAudioWorkLock workLock, VideoFrameStorageService storage, TokenHubVideoSummaryClient client) {
        this.properties = properties; this.repository = repository; this.workLock = workLock;
        this.storage = storage; this.client = client;
        capacity = new Semaphore(Math.max(1, properties.getConcurrency()));
    }
    /** 每轮预算按模型完整上下文保守预留，不使用视频体积线性推算。 */
    public RunBudget newRun() { return new RunBudget(properties.getMaxRequestsPerRun(), properties.getMaxCostFenPerRun()); }
    /** 独立摘要阶段时限。 */
    public int summaryTimeoutSeconds() { return properties.getTotalTimeoutSeconds(); }
    /** 审核调用使用提交后的独立期限。 */
    public Instant summaryDeadline() { return Instant.now().plusSeconds(summaryTimeoutSeconds()); }
    /** 常规补齐既有同源摘要时直接跳过。 */
    public Outcome summarize(long workId, RunBudget run, Instant deadline) { return summarize(workId, run, deadline, NO_CHECK); }
    /** 历史入口可传入批次锁和排空检查。 */
    public Outcome summarize(long workId, RunBudget run, Instant deadline, Runnable checkActive) {
        return execute(workId, run, deadline, checkActive, snapshot -> true);
    }
    /** 审核回调冻结原轮次与原对象，不能处理后来的另一轮审核。 */
    public Outcome summarize(long workId, Integer expectedRound, String expectedKey, String expectedSha,
            RunBudget run, Instant deadline) {
        return execute(workId, run, deadline, NO_CHECK, snapshot -> Objects.equals(expectedRound, snapshot.auditRound())
                && Objects.equals(expectedKey, snapshot.objectKey()) && Objects.equals(expectedSha, snapshot.sha256()));
    }
    /** 历史前序阶段失败后仍须守住本件原源，不能悄悄处理已换源作品。 */
    public Outcome summarize(long workId, String expectedKey, String expectedSha, RunBudget run,
            Instant deadline, Runnable checkActive) {
        return execute(workId, run, deadline, checkActive, snapshot -> Objects.equals(expectedKey, snapshot.objectKey())
                && Objects.equals(expectedSha, snapshot.sha256()));
    }
    /** 所有便捷入口共用相同准入和只消费一次的凭证。 */
    private Outcome execute(long workId, RunBudget run, Instant deadline, Runnable checkActive, Predicate<Snapshot> expected) {
        try (Preparation prepared = prepare(workId, false, run, deadline, checkActive, expected)) {
            return prepared.ready() ? complete(prepared, deadline) : prepared.outcome();
        }
    }
    /** 重建在清空前预检并预留，不获取作品锁，允许与音频预检顺序组合。 */
    public Preparation prepare(long workId, boolean force, RunBudget run, Instant deadline) {
        return prepare(workId, force, run, deadline, NO_CHECK);
    }
    /** 凭证不持锁、不下载和不收费，调用方仍用 try-with-resources 禁止晚到重用。 */
    public Preparation prepare(long workId, boolean force, RunBudget run, Instant deadline, Runnable checkActive) {
        return prepare(workId, force, run, deadline, checkActive, snapshot -> true);
    }
    /** 规格与预算不满足时明确返回，rebuild 必须据此保留旧派生数据。 */
    private Preparation prepare(long workId, boolean force, RunBudget run, Instant outerDeadline,
            Runnable checkActive, Predicate<Snapshot> expected) {
        if (!properties.isEnabled()) { return new Preparation(Outcome.DISABLED); }
        if (run == null) { return new Preparation(Outcome.FAILED); }
        long started = System.nanoTime();
        Stage stage = Stage.DEADLINE_CHECK;
        try {
            Instant deadline = VideoAudioIo.deadline(outerDeadline, summaryTimeoutSeconds());
            checkActive.run();
            stage = Stage.SOURCE_READ;
            Snapshot initial = repository.find(workId);
            if (initial == null || !expected.test(initial)) { return new Preparation(Outcome.STALE); }
            stage = Stage.SOURCE_HEAD;
            VideoFrameSource source = inspect(initial, deadline);
            stage = Stage.SUMMARY_CHECK;
            if (!force && validSummary(initial, source)) { return new Preparation(Outcome.ALREADY_COMPLETE); }
            if (run.stopped()) { return new Preparation(Outcome.FAILED); }
            if (!supported(initial, source)) { return new Preparation(Outcome.NOT_SUPPORTED); }
            checkActive.run();
            VideoAudioIo.check(deadline);
            stage = Stage.SOURCE_REVALIDATE;
            Snapshot current = repository.find(workId);
            if (!initial.sameSource(current) || current.version() != initial.version()) { return new Preparation(Outcome.STALE); }
            long reserve = TokenHubTokenPricing.costCents(VideoSummaryProperties.CONTEXT_INPUT_UPPER_BOUND, properties.getMaxOutputTokens());
            stage = Stage.BUDGET_RESERVE;
            RunBudget.Reservation reservation = run.reserve(reserve);
            if (reservation == null) { return new Preparation(Outcome.BUDGET_EXHAUSTED); }
            return new Preparation(initial, source, force, run, reservation, checkActive, System.nanoTime() - started);
        } catch (Exception exception) {
            log.warn("原视频摘要预检失败: workId={}, stage={}, exceptionType={}, failureLocation={}, elapsedMs={}",
                    workId, stage, exception.getClass().getSimpleName(), failureLocation(exception), elapsedMillis(started));
            return new Preparation(Outcome.FAILED);
        }
    }

    /** 原音频锁必须已释放；领取相同作品锁后独立复验，不再次预留费用。 */
    public Outcome complete(Preparation prepared, Instant outerDeadline) {
        if (!prepared.ready()) { return prepared.outcome(); }
        if (!prepared.claim()) { return Outcome.STALE; }
        if (!capacity.tryAcquire()) { return Outcome.BUSY; }
        long started = System.nanoTime();
        Stage stage = Stage.DEADLINE_CHECK;
        PortfolioAiModelClient.Completion response = null;
        try {
            long remaining = Duration.ofSeconds(summaryTimeoutSeconds()).toNanos() - prepared.preflightNanos;
            if (remaining <= 0) { return Outcome.FAILED; }
            Instant stageDeadline = Instant.now().plusNanos(remaining);
            Instant deadline = stageDeadline.isBefore(outerDeadline) ? stageDeadline : outerDeadline;
            VideoAudioIo.check(deadline);
            prepared.checkActive.run();
            stage = Stage.LOCK_ACQUIRE;
            var acquired = workLock.tryAcquire(prepared.initial.id());
            if (acquired.isEmpty()) { return Outcome.BUSY; }
            try (var lock = acquired.orElseThrow()) {
                stage = Stage.SOURCE_REVALIDATE;
                check(prepared, lock, deadline);
                Snapshot current = repository.find(prepared.initial.id());
                if (!prepared.initial.sameSource(current)
                        || !prepared.force && prepared.initial.version() != current.version()) { return Outcome.STALE; }
                if (!prepared.source.equals(inspect(current, deadline))) { return Outcome.STALE; }
                if (!prepared.force && validSummary(current, prepared.source)) { return Outcome.ALREADY_COMPLETE; }
                if (!supported(current, prepared.source)) { return Outcome.NOT_SUPPORTED; }
                if (prepared.run.stopped()) { return Outcome.FAILED; }
                stage = Stage.SIGN_URL;
                String signedUrl = storage.signedGet(current.objectKey(), Duration.ofSeconds(summaryTimeoutSeconds() + URL_GRACE_SECONDS));
                stage = Stage.SOURCE_REVALIDATE;
                check(prepared, lock, deadline);
                Snapshot beforeCall = repository.find(current.id());
                if (!current.sameSource(beforeCall) || current.version() != beforeCall.version()) { return Outcome.STALE; }
                if (!prepared.source.equals(inspect(current, deadline))) { return Outcome.STALE; }
                Instant modelDeadline = deadline.minusSeconds(storage.headTimeoutSeconds() + SAVE_GRACE_SECONDS);
                check(prepared, lock, modelDeadline);
                if (!prepared.run.sent(prepared.reservation)) { return Outcome.FAILED; }
                stage = Stage.MODEL_CALL;
                response = client.summarize(current.id(), signedUrl, modelDeadline);
                stage = Stage.USAGE_ACCOUNT;
                prepared.run.recordUsage(prepared.reservation, response);
                stage = Stage.CONTENT_VALIDATE;
                var modelContent = parseModelContent(response.content());
                JsonNode suggestedTitle = modelContent.remove(SUGGESTED_TITLE);
                var normalized = PortfolioAiStoredVideoSummary.normalizeModelContent(modelContent.toString());
                var content = normalized.content();
                if (normalized.changed()) {
                    log.info("原视频摘要条目已规整: workId={}, observationsBefore={}, observationsAfter={}, limitationsBefore={}, limitationsAfter={}",
                            current.id(), normalized.observationsBefore(), content.path(OBSERVATIONS).size(),
                            normalized.limitationsBefore(), content.path(LIMITATIONS).size());
                }
                stage = Stage.DOCUMENT_BUILD;
                var document = PortfolioAiStoredVideoSummary.create(prepared.source.sourceVersion(),
                        client.inputHash(prepared.source.sourceVersion()), VideoSummaryProperties.MODEL,
                        client.promptVersion(), Instant.now(), content);
                stage = Stage.SOURCE_VERIFY;
                check(prepared, lock, deadline);
                if (!prepared.source.equals(inspect(current, deadline))) { return Outcome.STALE; }
                check(prepared, lock, deadline);
                stage = Stage.SAVE;
                if (!repository.save(current, document.toString())) { return Outcome.STALE; }
                log.info("原视频摘要已保存: workId={}, inputMode={}, sourceVersion={}, elapsedMs={}, contentCodePoints={}, promptTokens={}, completionTokens={}",
                        current.id(), PortfolioAiStoredVideoSummary.INPUT_MODE, prepared.source.sourceVersion(),
                        elapsedMillis(started), contentCodePoints(response), response.promptTokens(), response.completionTokens());
                tryRenameDefaultTitle(current, document, suggestedTitle);
                stage = Stage.LOCK_RELEASE;
                return Outcome.SAVED;
            }
        } catch (PortfolioAiCallException exception) {
            prepared.run.recordUsage(prepared.reservation, exception.getCompletion());
            if (ACCOUNT_UNAVAILABLE.equals(exception.getCode())) { prepared.run.stop(); }
            var usage = exception.getCompletion();
            log.warn("原视频摘要调用失败: workId={}, stage={}, code={}, reason={}, retryable={}, elapsedMs={}, promptTokens={}, completionTokens={}",
                    prepared.initial.id(), stage, exception.getCode(), exception.getMessage(), exception.isRetryable(),
                    elapsedMillis(started), usage == null ? null : usage.promptTokens(), usage == null ? null : usage.completionTokens());
            return Outcome.FAILED;
        } catch (AiProtocolException exception) {
            String diagnostic = exception instanceof PortfolioAiStoredVideoSummary.ValidationException validation
                    ? validation.diagnostic() : DIAGNOSTIC_UNAVAILABLE;
            log.warn("原视频摘要处理失败: workId={}, stage={}, exceptionType={}, code={}, reason={}, diagnostic={}, elapsedMs={}, contentCodePoints={}, contentBytes={}, promptTokens={}, completionTokens={}, failureLocation={}",
                    prepared.initial.id(), stage, exception.getClass().getSimpleName(), exception.getCode(), exception.getMessage(),
                    diagnostic, elapsedMillis(started), contentCodePoints(response), contentBytes(response),
                    response == null ? null : response.promptTokens(), response == null ? null : response.completionTokens(), failureLocation(exception));
            return Outcome.FAILED;
        } catch (Exception exception) {
            log.warn("原视频摘要处理失败: workId={}, stage={}, exceptionType={}, failureLocation={}, elapsedMs={}",
                    prepared.initial.id(), stage, exception.getClass().getSimpleName(), failureLocation(exception), elapsedMillis(started));
            return Outcome.FAILED;
        } finally { capacity.release(); }
    }

    /** 仅拆出已知推荐字段，非法 JSON 继续由原摘要合同提供相同的失败诊断。 */
    private static ObjectNode parseModelContent(String raw) {
        try { return StrictAiJson.parseObject(raw, MAX_MODEL_CONTENT_BYTES, MAX_MODEL_CONTENT_DEPTH); }
        catch (AiProtocolException exception) {
            // 同一解析边界必定失败；复用原合同保留摘要的错误码和安全诊断。
            PortfolioAiStoredVideoSummary.normalizeModelContent(raw);
            throw exception;
        }
    }

    /** 摘要事务已提交；名称读取、检查或更新失败均不能改变摘要结果。 */
    private void tryRenameDefaultTitle(Snapshot savedSource, ObjectNode document, JsonNode suggestedTitle) {
        try {
            String title = normalizeSuggestedTitle(suggestedTitle);
            if (title == null) { return; }
            var latest = repository.findForTitle(savedSource.id());
            if (latest == null || !savedSource.sameSource(latest.source()) || !isDefaultVideoTitle(latest.title())) { return; }
            var stored = PortfolioAiStoredVideoSummary.documentOrNull(latest.source().summary(),
                    document.path(KEY_SOURCE_VERSION).asText());
            if (!document.equals(stored)) { return; }
            boolean renamed = repository.renameDefaultTitle(latest, title);
            log.info("原视频默认名称更新结束: workId={}, renamed={}", savedSource.id(), renamed);
        } catch (Exception exception) {
            log.warn("原视频默认名称更新失败，摘要已保存: workId={}, exceptionType={}, failureLocation={}",
                    savedSource.id(), exception.getClass().getSimpleName(), failureLocation(exception));
        }
    }

    /** 无效名称只跳过改名，不截断、不重试；安全清理后仍须满足十码点限制。 */
    private static String normalizeSuggestedTitle(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().codePoints().anyMatch(Character::isISOControl)) { return null; }
        String title = value.asText().strip();
        if (title.isBlank() || title.codePointCount(0, title.length()) > MAX_TITLE_CODE_POINTS) { return null; }
        title = PortfolioAiVideoAudioContext.sanitize(title).strip();
        return title.isBlank() || title.codePointCount(0, title.length()) > MAX_TITLE_CODE_POINTS ? null : title;
    }

    /** 只识别完整的时间序号占位名，原文件名与用户自定义名称均保留。 */
    private static boolean isDefaultVideoTitle(String title) {
        if (title == null) { return false; }
        var match = DEFAULT_VIDEO_TITLE.matcher(title);
        if (!match.matches()) { return false; }
        try { LocalDateTime.parse(match.group(1), DEFAULT_TITLE_TIME); return true; }
        catch (DateTimeParseException exception) { return false; }
    }

    /** HEAD 仅访问数据库验证过的作品对象，不接受调用方任意 URL。 */
    private VideoFrameSource inspect(Snapshot snapshot, Instant deadline) {
        VideoAudioIo.check(deadline);
        ObjectMetadata head = storage.head(snapshot.objectKey(), deadline);
        VideoAudioIo.check(deadline);
        String etag = VideoFrameStorageService.normalizeEtag(head.getETag());
        if (head.getContentLength() <= 0 || etag.isBlank()) { throw new IllegalStateException("原视频元数据不完整"); }
        return new VideoFrameSource(snapshot.objectKey(), PortfolioAiStoredVideoSummary.sourceVersion(snapshot.objectKey(),
                etag, head.getContentLength(), snapshot.sha256()), etag, head.getContentLength());
    }
    /** 数据库时长仅用于应用准入，真正体积取 COS HEAD，不声称已探测完整时长。 */
    private boolean supported(Snapshot snapshot, VideoFrameSource source) {
        boolean supported = source.size() <= properties.getMaxVideoBytes() && snapshot.durationMs() != null
                && snapshot.durationMs() > 0 && snapshot.durationMs() <= properties.getMaxDurationSeconds() * 1000L;
        if (!supported) {
            log.info("原视频摘要规格拒绝: workId={}, videoBytes={}, maxVideoBytes={}, durationMs={}, maxDurationMs={}",
                    snapshot.id(), source.size(), properties.getMaxVideoBytes(), snapshot.durationMs(), properties.getMaxDurationSeconds() * 1000L);
        }
        return supported;
    }
    /** 正文只计 Unicode 码点，缺少响应时不能记成已收到空正文。 */
    private static Integer contentCodePoints(PortfolioAiModelClient.Completion response) {
        return response == null || response.content() == null ? null
                : response.content().codePointCount(0, response.content().length());
    }
    /** 记录真实 UTF-8 字节长度，不记录摘要、音频或资源地址。 */
    private static Integer contentBytes(PortfolioAiModelClient.Completion response) {
        return response == null || response.content() == null ? null : response.content().getBytes(StandardCharsets.UTF_8).length;
    }
    /** 相对耗时使用单调时钟，不受服务器时间校准影响。 */
    private static long elapsedMillis(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    /** 只输出代码位置，不输出异常消息、完整堆栈或嵌套异常中的敏感值。 */
    private static String failureLocation(Exception exception) {
        StackTraceElement[] stack = exception.getStackTrace();
        return stack.length == 0 ? LOCATION_UNAVAILABLE : stack[0].getClassName() + "#" + stack[0].getMethodName() + ":" + stack[0].getLineNumber();
    }
    /** 同源摘要无 TTL，帧或音频变化不影响合法性。 */
    private static boolean validSummary(Snapshot snapshot, VideoFrameSource source) {
        return PortfolioAiStoredVideoSummary.documentOrNull(snapshot.summary(), source.sourceVersion()) != null;
    }
    /** 付费前后均检查绝对期限、批次许可和数据库会话锁。 */
    private static void check(Preparation prepared, VideoAudioWorkLock.ExecutionLock lock, Instant deadline) {
        VideoAudioIo.check(deadline); prepared.checkActive.run(); lock.checkHeld(); VideoAudioIo.check(deadline);
    }

    /** 规格拒绝单独列出，rebuild 不可先清再失败。 */
    public enum Outcome { READY, DISABLED, ALREADY_COMPLETE, BUSY, BUDGET_EXHAUSTED, NOT_SUPPORTED, FAILED, STALE, SAVED }

    /** 内存后台预算；每次先按上下文最大费用预留，未知响应不能被当作免费。 */
    public static final class RunBudget {
        /** 请求数和费用的独立总上限。 */
        private final int maxRequests;
        private final long maxCostFen;
        /** 已预留次数不回退；费用包含已结算费用和仍未知或未发送的完整预留。 */
        private int requests;
        private long reservedCostFen;
        /** 实际发送与有合法计量的响应数量。 */
        private int sentRequests, measuredResponses;
        /** 已知 tokens 和按分向上取整的估算费用。 */
        private long knownTokens, knownCostFen;
        /** 账户错误停止本轮后续发送。 */
        private boolean stopped;
        /** 公开构造便于离线边界测试。 */
        public RunBudget(int maxRequests, long maxCostFen) { this.maxRequests = maxRequests; this.maxCostFen = maxCostFen; }
        /** 每件独立持有预留，只能凭对应请求的可信计量释放差额。 */
        synchronized Reservation reserve(long fen) {
            if (stopped || fen <= 0 || requests >= maxRequests || fen > maxCostFen - reservedCostFen) { return null; }
            requests++; reservedCostFen += fen; return new Reservation(this, fen);
        }
        /** 同一凭证只发送一次，同一轮账户失效后不可继续发送。 */
        synchronized boolean sent(Reservation reservation) {
            if (stopped || reservation == null || reservation.owner != this || reservation.sent) { return false; }
            reservation.sent = true; sentRequests++; return true;
        }
        /** 核心用量缺失、负数或溢出时保留未知，缓存非法按全价而不是零费用。 */
        synchronized void recordUsage(Reservation reservation, PortfolioAiModelClient.Completion value) {
            if (reservation == null || reservation.owner != this || !reservation.sent || reservation.measured
                    || value == null || value.promptTokens() == null || value.completionTokens() == null
                    || value.promptTokens() < 0 || value.completionTokens() < 0) { return; }
            try {
                long tokens = Math.addExact(value.promptTokens(), value.completionTokens());
                Long cached = value.cacheStatus() == PortfolioAiModelClient.CacheStatus.KNOWN_HIT
                        || value.cacheStatus() == PortfolioAiModelClient.CacheStatus.KNOWN_MISS ? value.cachedTokens() : null;
                long cost = TokenHubTokenPricing.costCents(value.promptTokens(), value.completionTokens(), cached);
                long nextTokens = Math.addExact(knownTokens, tokens), nextCost = Math.addExact(knownCostFen, cost);
                long nextReserved = Math.addExact(reservedCostFen - reservation.fen, cost);
                knownTokens = nextTokens; knownCostFen = nextCost; reservedCostFen = nextReserved;
                reservation.measured = true; measuredResponses++;
                // 单件越过自身预留就停止，不能借其它在途或未知请求的预留掩盖越界。
                if (cost > reservation.fen || reservedCostFen > maxCostFen) { stopped = true; }
            } catch (ArithmeticException exception) { stopped = true; }
        }
        /** 账户失败仅停止本次扫描，不更改永久开关。 */
        synchronized void stop() { stopped = true; }
        /** 已预留请求数。 */
        public synchronized int requests() { return requests; }
        /** 已进入客户端的请求数。 */
        public synchronized int sentRequests() { return sentRequests; }
        /** 已知总 token，不含未知响应。 */
        public synchronized long knownTokens() { return knownTokens; }
        /** 本轮已结算费用加尚未结算的保守预留，单位分。 */
        public synchronized long reservedCostFen() { return reservedCostFen; }
        /** 已知响应费用估算，单位分，不是账单。 */
        public synchronized long knownCostFen() { return knownCostFen; }
        /** 是否存在无可信计量的已发送请求。 */
        public synchronized boolean usageUnknown() { return measuredResponses < sentRequests; }
        /** 用量未知请求数。 */
        public synchronized int unknownUsageCount() { return sentRequests - measuredResponses; }
        /** 本轮是否禁止继续收费。 */
        public synchronized boolean stopped() { return stopped; }

        /** 单件费用凭证；字段只在所属预算监视器内读取或修改。 */
        static final class Reservation {
            /** 预留所属运行，防止跨轮凭证误用。 */
            private final RunBudget owner;
            /** 准入时固定的本件费用上界。 */
            private final long fen;
            /** 发送与结算均只能完成一次。 */
            private boolean sent, measured;
            /** 只允许所属预算创建凭证。 */
            private Reservation(RunBudget owner, long fen) { this.owner = owner; this.fen = fen; }
        }
    }

    /** 无锁且无网络资源的单次预算凭证，强制重建前后传递同一实例。 */
    public static class Preparation implements AutoCloseable {
        /** 准入状态。 */
        private final Outcome outcome;
        /** 开始时源和权限快照。 */
        private Snapshot initial;
        /** HEAD 身份不含临时读取链接。 */
        private VideoFrameSource source;
        /** 强制重建允许本件帧和音频推进数据库版本。 */
        private boolean force;
        /** 已预留费用所属运行。 */
        private RunBudget run;
        /** 跨越前序帧和音频阶段后仍只结算本件预留。 */
        private RunBudget.Reservation reservation;
        /** 调用方的生命周期和批次锁检查。 */
        private Runnable checkActive;
        /** 预检已用摘要期限。 */
        private long preflightNanos;
        /** 防止同凭证关闭后或重复执行付费。 */
        private boolean closed, completed;
        /** 未就绪结果不持有任何资源。 */
        private Preparation(Outcome outcome) { this.outcome = outcome; }
        /** 就绪凭证不会把锁跨越音频阶段。 */
        private Preparation(Snapshot initial, VideoFrameSource source, boolean force, RunBudget run, RunBudget.Reservation reservation,
                Runnable checkActive, long preflightNanos) {
            this.outcome = Outcome.READY; this.initial = initial; this.source = source; this.force = force;
            this.run = run; this.reservation = reservation; this.checkActive = checkActive; this.preflightNanos = preflightNanos;
        }
        /** 清空前供调用方检查。 */
        public Outcome outcome() { return outcome; }
        /** 仅准入成功可进入执行。 */
        public boolean ready() { return outcome == Outcome.READY; }
        /** 即使多线程误用，也只有一个执行者能使用凭证。 */
        private synchronized boolean claim() { if (closed || completed) { return false; } completed = true; return true; }
        /** 不退预留，关闭后禁止迟到执行。 */
        @Override public synchronized void close() { closed = true; }
    }
}
