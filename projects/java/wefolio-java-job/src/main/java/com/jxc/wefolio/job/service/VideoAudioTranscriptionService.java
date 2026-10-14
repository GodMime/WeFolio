package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoAudioProperties;
import com.jxc.wefolio.job.repo.VideoAudioTextRepository;
import com.jxc.wefolio.job.repo.VideoAudioTextRepository.Snapshot;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

/** 审核与历史处理共用的音频用例：预检、预算、付费、源校验和条件保存集中实现。 */
@Service
@Slf4j
public class VideoAudioTranscriptionService {
    /** 调用方已有生命周期许可时无需增加重复许可，仍可传入更严格的持锁检查。 */
    private static final Runnable NO_CHECK = () -> { };
    /** 已有持久帧提供源身份，不能把更新的音频附到旧源帧上。 */
    private static final String SOURCE_OBJECT_KEY = "sourceObjectKey", SOURCE_ETAG = "sourceEtag";
    private static final String SOURCE_BYTES = "sourceBytes", SOURCE_VERSION = "sourceVersion";
    private static final String SHA_256 = "SHA-256";
    /** 不向日志输出文件、源签名 URL 或全文。 */
    private final VideoAudioProperties properties;
    /** 作品快照与带源身份保护的全文写入。 */
    private final VideoAudioTextRepository repository;
    /** 跨实例同作品互斥，避免审核和历史入口同时付费。 */
    private final VideoAudioWorkLock workLock;
    /** 受控下载、探测和本地音轨提取。 */
    private final VideoAudioExtractor extractor;
    /** 只发送一次请求的 TokenHub 转写客户端。 */
    private final TokenHubAudioTranscriptionClient client;
    /** 当前实例持有临时文件及执行转写的并发上限。 */
    private final Semaphore capacity;

    /** 单实例并发在整个预检与付费周期都受限制，同时避免临时文件累积。 */
    public VideoAudioTranscriptionService(VideoAudioProperties properties, VideoAudioTextRepository repository,
            VideoAudioWorkLock workLock, VideoAudioExtractor extractor, TokenHubAudioTranscriptionClient client) {
        this.properties = properties; this.repository = repository; this.workLock = workLock;
        this.extractor = extractor; this.client = client;
        this.capacity = new Semaphore(Math.max(1, properties.getConcurrency()));
    }

    /** 每次审核扫描或显式历史运行拥有独立的请求数和音频秒数预算。 */
    public RunBudget newRun() { return new RunBudget(properties.getMaxRequestsPerRun(), properties.getMaxAudioSecondsPerRun()); }
    /** 音频独立总期限，不借用已经提交终态的审核租约。 */
    public Instant audioDeadline() { return Instant.now().plusSeconds(audioTimeoutSeconds()); }
    /** 历史总期限可用截图期限加此值，避免重复硬编码。 */
    public int audioTimeoutSeconds() { return properties.getTotalTimeoutSeconds(); }

    /** 普通补齐可在已有生命周期许可内调用。 */
    public Outcome transcribe(long workId, RunBudget run, Instant deadline) {
        return transcribe(workId, run, deadline, NO_CHECK);
    }
    /** 历史补齐传入全局批次持锁检查，异常在本模块隔离但不放松付费准入。 */
    public Outcome transcribe(long workId, RunBudget run, Instant deadline, Runnable checkActive) {
        try (Preparation preparation = prepare(workId, false, run, deadline, checkActive)) {
            return preparation.ready() ? complete(preparation, deadline) : preparation.outcome();
        }
    }
    /** 审核回调携带原任务身份，过期审核不能转写后来的已通过作品。 */
    public Outcome transcribe(long workId, Integer expectedRound, String expectedObjectKey, String expectedSha,
            RunBudget run, Instant deadline) {
        try (Preparation preparation = prepare(workId, false, run, deadline, NO_CHECK,
                snapshot -> Objects.equals(expectedRound, snapshot.auditRound())
                        && Objects.equals(expectedObjectKey, snapshot.objectKey()) && Objects.equals(expectedSha, snapshot.sha256()))) {
            return preparation.ready() ? complete(preparation, deadline) : preparation.outcome();
        }
    }
    /** 重建调用方在清空旧数据前先完成下载、探测、并预留收费预算。 */
    public Preparation prepare(long workId, boolean force, RunBudget run, Instant deadline) {
        return prepare(workId, force, run, deadline, NO_CHECK);
    }
    /** 调用方负责 try-with-resources，预检后可以先重建帧，再完成音频处理。 */
    public Preparation prepare(long workId, boolean force, RunBudget run, Instant deadline, Runnable checkActive) {
        return prepare(workId, force, run, deadline, checkActive, snapshot -> true);
    }

    /** 预检不发送付费请求，任一失败不会清空调用方的旧结果。 */
    private Preparation prepare(long workId, boolean force, RunBudget run, Instant outerDeadline,
            Runnable checkActive, Predicate<Snapshot> expected) {
        if (!properties.isEnabled()) { return new Preparation(Outcome.DISABLED); }
        if (run == null) { return new Preparation(Outcome.FAILED); }
        if (!capacity.tryAcquire()) { return new Preparation(Outcome.BUSY); }
        VideoAudioWorkLock.ExecutionLock lock = null;
        VideoAudioExtractor.Prepared media = null;
        long started = System.nanoTime();
        boolean transferred = false;
        try {
            Instant deadline = VideoAudioIo.deadline(outerDeadline, properties.getTotalTimeoutSeconds());
            checkActive.run();
            Optional<VideoAudioWorkLock.ExecutionLock> acquired = workLock.tryAcquire(workId);
            if (acquired.isEmpty()) { return new Preparation(Outcome.BUSY); }
            lock = acquired.orElseThrow();
            lock.checkHeld();
            Snapshot initial = repository.find(workId);
            if (initial == null || !expected.test(initial)) { return new Preparation(Outcome.STALE); }
            if (!force && initial.text() != null) { return new Preparation(Outcome.ALREADY_COMPLETE); }
            // 账户失败不影响已有完整结果的跳过语义，但禁止继续下载和收费预检。
            if (run.stopped()) { return new Preparation(Outcome.FAILED); }
            media = extractor.prepare(initial.objectKey(), deadline);
            if (!force && !matchesManifest(initial, media.source())) { return new Preparation(Outcome.STALE); }
            checkActive.run();
            lock.checkHeld();
            Snapshot current = repository.find(workId);
            if (!initial.sameSource(current) || initial.version() != current.version()) { return new Preparation(Outcome.STALE); }
            if (media.probe().hasAudio() && !run.reserve(VideoAudioExtractor.reservedSeconds(media.probe(), properties.getMaxDurationSeconds()))) {
                return new Preparation(Outcome.BUDGET_EXHAUSTED);
            }
            Preparation prepared = new Preparation(initial, force, run, checkActive, lock, media,
                    Math.max(0, System.nanoTime() - started), capacity);
            transferred = true;
            return prepared;
        } catch (Exception exception) {
            log.warn("视频音频预检失败: workId={}, exceptionType={}", workId, exception.getClass().getSimpleName());
            return new Preparation(Outcome.FAILED);
        } finally {
            if (!transferred) {
                if (media != null) { media.close(); }
                if (lock != null) { lock.close(); }
                capacity.release();
            }
        }
    }

    /** 重读刚发布帧后的版本，再识别和 CAS；预检所耗音频时间仍计入独立预算。 */
    public Outcome complete(Preparation prepared, Instant outerDeadline) {
        if (!prepared.ready()) { return prepared.outcome(); }
        if (prepared.closed || prepared.completed) { return Outcome.STALE; }
        prepared.completed = true;
        try {
            long remaining = Duration.ofSeconds(properties.getTotalTimeoutSeconds()).toNanos() - prepared.preflightNanos;
            if (remaining <= 0) { return Outcome.FAILED; }
            Instant audioDeadline = Instant.now().plusNanos(remaining);
            Instant deadline = audioDeadline.isBefore(outerDeadline) ? audioDeadline : outerDeadline;
            check(prepared, deadline);
            Snapshot current = repository.find(prepared.initial.id());
            if (!prepared.initial.sameSource(current)) { return Outcome.STALE; }
            if (!prepared.force && prepared.initial.version() != current.version()) { return Outcome.STALE; }
            if (!matchesManifest(current, prepared.media.source())) { return Outcome.STALE; }
            if (!prepared.force && current.text() != null) { return Outcome.ALREADY_COMPLETE; }
            if (!prepared.media.source().equals(extractor.inspect(current.objectKey(), deadline))) { return Outcome.STALE; }
            String text = "";
            if (prepared.media.probe().hasAudio()) {
                Path wav = extractor.extract(prepared.media, deadline);
                check(prepared, deadline);
                // 提取期间可能发生作品编辑或重审核，在收费前必须再核对快照。
                Snapshot beforeCall = repository.find(current.id());
                if (!current.sameSource(beforeCall) || current.version() != beforeCall.version()) { return Outcome.STALE; }
                if (!prepared.media.source().equals(extractor.inspect(current.objectKey(), deadline))) { return Outcome.STALE; }
                check(prepared, deadline);
                if (prepared.run.stopped()) { return Outcome.FAILED; }
                prepared.run.sent();
                TokenHubAudioTranscriptionClient.Result result = client.transcribe(wav, deadline);
                prepared.run.recordUsage(result.tokens());
                text = result.text();
            }
            check(prepared, deadline);
            if (!prepared.media.source().equals(extractor.inspect(current.objectKey(), deadline))) { return Outcome.STALE; }
            check(prepared, deadline);
            if (!repository.save(current, text)) { return Outcome.STALE; }
            Outcome outcome = text.isEmpty() ? Outcome.EMPTY : Outcome.SAVED;
            log.info("视频音频处理结束: workId={}, outcome={}", current.id(), outcome);
            return outcome;
        } catch (TokenHubAudioTranscriptionClient.RunDisabledException exception) {
            prepared.run.stop();
            log.warn("视频音频鉴权或付费不可用，本轮停止请求: workId={}", prepared.initial.id());
            return Outcome.FAILED;
        } catch (Exception exception) {
            log.warn("视频音频处理失败: workId={}, exceptionType={}", prepared.initial.id(), exception.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /** 每次付费及写入之前核对线程期限、批次锁和作品锁。 */
    private static void check(Preparation prepared, Instant deadline) {
        VideoAudioIo.check(deadline);
        prepared.checkActive.run();
        prepared.lock.checkHeld();
        VideoAudioIo.check(deadline);
    }

    /** 无清单兼容旧 INTERVAL；有清单时必须匹配其保存的完整源身份。 */
    private static boolean matchesManifest(Snapshot snapshot, VideoAudioExtractor.Source source) {
        if (snapshot.manifest() == null) { return true; }
        try {
            var manifest = StrictAiJson.parseObject(snapshot.manifest(), 128 * 1024, 12);
            String material = source.key() + "\n" + source.etag() + "\n" + source.bytes()
                    + "\n" + Objects.toString(snapshot.sha256(), "");
            String version = HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256)
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return source.key().equals(manifest.path(SOURCE_OBJECT_KEY).asText())
                    && source.etag().equals(VideoFrameStorageService.normalizeEtag(manifest.path(SOURCE_ETAG).asText()))
                    && manifest.path(SOURCE_BYTES).isIntegralNumber() && source.bytes() == manifest.path(SOURCE_BYTES).longValue()
                    && version.equals(manifest.path(SOURCE_VERSION).asText());
        } catch (Exception exception) { return false; }
    }

    /** 明确区分全文成功、成功无文本、跳过和不完整状态。 */
    public enum Outcome { READY, DISABLED, ALREADY_COMPLETE, BUSY, BUDGET_EXHAUSTED, FAILED, STALE, SAVED, EMPTY }

    /** 内存执行预算，预留后不退回，防止失败后的隐式付费重试突破上限。 */
    public static final class RunBudget {
        /** 本轮最多预留的请求数。 */
        private final int maxRequests;
        /** 本轮最多预留的音频秒数。 */
        private final long maxSeconds;
        /** 已预留请求数，失败不归还。 */
        private int requests;
        /** 已预留的保守音频秒数。 */
        private long seconds;
        /** 鉴权或余额失败后停止本轮后续请求。 */
        private boolean stopped;
        /** 实际调用与有计量响应分别记录，超时或缺失 usage 保留未知而不是记零。 */
        private int sentRequests;
        /** 返回可接受 usage 的响应数。 */
        private int measuredResponses;
        /** 累计已知 token，溢出的响应按未知计量处理。 */
        private long knownTokens;
        /** 由服务配置创建，公开构造便于离线边界测试。 */
        public RunBudget(int maxRequests, long maxSeconds) { this.maxRequests = maxRequests; this.maxSeconds = maxSeconds; }
        /** 音频无轨不调用此方法，不占请求额度。 */
        synchronized boolean reserve(long durationSeconds) {
            if (stopped || durationSeconds <= 0 || requests >= maxRequests || durationSeconds > maxSeconds - seconds) { return false; }
            requests++; seconds += durationSeconds; return true;
        }
        /** 账户级失败仅停止当前运行，不修改功能配置。 */
        synchronized void stop() { stopped = true; }
        /** 请求进入客户端前记录，响应未知也不能假设免费。 */
        synchronized void sent() { sentRequests++; }
        /** 接受非负且不会溢出的计量，未知响应不累加。 */
        synchronized void recordUsage(Long tokens) {
            if (tokens != null && tokens >= 0 && tokens <= Long.MAX_VALUE - knownTokens) { measuredResponses++; knownTokens += tokens; }
        }
        /** 本轮是否禁止新请求。 */
        public synchronized boolean stopped() { return stopped; }
        /** 已预留请求数。 */
        public synchronized int requests() { return requests; }
        /** 已预留音频秒数。 */
        public synchronized long seconds() { return seconds; }
        /** 已进入收费调用的请求数。 */
        public synchronized int sentRequests() { return sentRequests; }
        /** 累计已知 token，不含计量未知部分。 */
        public synchronized long knownTokens() { return knownTokens; }
        /** 是否存在需要按未知报告的请求。 */
        public synchronized boolean usageUnknown() { return measuredResponses < sentRequests; }
        /** 计量未知的请求个数。 */
        public synchronized int unknownUsageCount() { return sentRequests - measuredResponses; }
    }

    /** 持有单件源文件和互斥资源，允许重建复用但不允许重复收费。 */
    public static class Preparation implements AutoCloseable {
        /** 预检准入结果；READY 才持有可继续处理的资源。 */
        private final Outcome outcome;
        /** 预检开始时的作品和源快照。 */
        private Snapshot initial;
        /** 强制重建允许帧阶段推进版本，但不能更换源或轮次。 */
        private boolean force;
        /** 当前扫描共享的收费预算。 */
        private RunBudget run;
        /** 调用方批次锁或停止状态检查。 */
        private Runnable checkActive;
        /** 当前作品的数据库会话锁。 */
        private VideoAudioWorkLock.ExecutionLock lock;
        /** 本件可复用的本地原视频及探测结果。 */
        private VideoAudioExtractor.Prepared media;
        /** 预检已经消耗的音频处理时间。 */
        private long preflightNanos;
        /** 清理后归还的实例并发许可。 */
        private Semaphore capacity;
        /** 防止重复释放目录、会话与许可。 */
        private boolean closed;
        /** 防止同一预检结果被多次提交收费。 */
        private boolean completed;
        /** 无资源结果不要求调用方执行特殊清理。 */
        private Preparation(Outcome outcome) { this.outcome = outcome; }
        /** 成功预检转移资源所有权。 */
        private Preparation(Snapshot initial, boolean force, RunBudget run, Runnable checkActive,
                VideoAudioWorkLock.ExecutionLock lock, VideoAudioExtractor.Prepared media, long preflightNanos, Semaphore capacity) {
            this.outcome = Outcome.READY; this.initial = initial; this.force = force; this.run = run;
            this.checkActive = checkActive; this.lock = lock; this.media = media; this.preflightNanos = preflightNanos; this.capacity = capacity;
        }
        /** 返回预检结果，供历史任务在清空前决定是否继续。 */
        public Outcome outcome() { return outcome; }
        /** 只有完成准入的结果才能进入识别。 */
        public boolean ready() { return outcome == Outcome.READY; }
        /** 所有失败、预算拒绝和帧重建中止路径都回收本件资源。 */
        @Override public synchronized void close() {
            if (closed) { return; }
            closed = true;
            if (media != null) { media.close(); }
            if (lock != null) { lock.close(); }
            if (capacity != null) { capacity.release(); }
        }
    }
}
