package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.message.PortfolioFontMessage;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import com.jxc.wefolio.dto.PortfolioFontManifestDto.Asset;
import com.jxc.wefolio.entity.PortfolioEntity;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 事务外同步准备与删除、事务内重新认领的统一协议。 */
@Slf4j
@Service
public class PortfolioFontService {
    /** 生成配置及开关。 */
    private final PortfolioFontProperties properties;
    /** 固定字体目录。 */
    private final PortfolioFontSources sources;
    /** 本地裁剪执行器。 */
    private final PortfolioFontSubsetRunner runner;
    /** 零重试存储适配器。 */
    private final PortfolioFontStorage storage;
    /** 全局生成并发名额，排队计入请求预算。 */
    private final Semaphore slots;
    /** 同作品集并发生成采用固定分段限制，不随用户数量增长。 */
    private final Semaphore[] portfolioSlots = new Semaphore[128];
    /** 仅承载当前请求同步等待的可取消工作，不是后台任务队列。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 删除使用独立并发预算。 */
    private final Semaphore deleteSlots = new Semaphore(2);

    /** 准备时与锁内计划不同，业务入口映射为自身的并发冲突语义。 */
    public static final class PlanConflict extends RuntimeException { }

    /** 本次请求独占的产物凭据；不得从 HTTP 反序列化或跨请求复用。 */
    public static final class Prepared {
        /** 完整配置快照的服务端修订号。 */
        private final Integer revision;
        /** 候选配置逻辑需求。 */
        private final PortfolioFontPlan plan;
        /** 已完成本次验证的资源。 */
        private final PortfolioFontManifestDto manifest;
        /** 本次新上传对象，区别于预检缓存候选。 */
        private final Set<String> newKeys;
        /** 一次性接入标记，结果未知也不得再作为新对象接入。 */
        private boolean consumed;
        /** 仅服务端生成本次凭据。 */
        private Prepared(Integer revision, PortfolioFontPlan plan, PortfolioFontManifestDto manifest, Set<String> newKeys) {
            this.revision = revision; this.plan = plan; this.manifest = manifest; this.newKeys = Set.copyOf(newKeys);
        }
        /** 供业务复核完整服务端快照。 */
        public Integer revision() { return revision; }
        /** 供业务复核候选配置需求。 */
        public String planHash() { return plan.hash(); }
        /** prepare 接口仅返回去掉对象键的展示投影。 */
        public PortfolioFontManifestDto response() { return PortfolioFontManifests.project(plan, JSON.toJSONString(manifest)); }
    }

    /** 注入资源边界并固定线程资源上限。 */
    public PortfolioFontService(PortfolioFontProperties properties, PortfolioFontSources sources,
                                PortfolioFontSubsetRunner runner, PortfolioFontStorage storage) {
        this.properties = properties; this.sources = sources; this.runner = runner; this.storage = storage;
        slots = new Semaphore(Math.max(1, properties.getConcurrency()));
        for (int i = 0; i < portfolioSlots.length; i++) { portfolioSlots[i] = new Semaphore(1); }
    }

    /** 预检事务和上层锁结束后调用；失败保留选择并直接返回不可用清单。 */
    public Prepared prepare(PortfolioEntity snapshot, PortfolioFontPlan plan, String prefix) {
        requireOutsideTransaction();
        var manifest = PortfolioFontManifests.empty(plan);
        if (manifest == null) { return new Prepared(snapshot.getCurrentRevision(), plan, null, Set.of()); }
        String bucket = storage.bucket();
        boolean bucketUsable = bucket != null && !bucket.isBlank();
        if (!bucketUsable) { log.warn("字体存储配置不可用: reasonCode=BUCKET_MISSING"); }
        var draft = PortfolioFontManifests.parse(snapshot.getDraftFontAssetsJson());
        var published = PortfolioFontManifests.parse(snapshot.getPublishedFontAssetsJson());
        List<PortfolioFontPlan.Group> missing = new ArrayList<>();
        for (var group : plan.groups()) {
            Asset reused = PortfolioFontManifests.find(group, draft, published);
            if (bucketUsable && reused != null && Objects.equals(reused.getBucket(), bucket)) { manifest.getAssets().add(reused); }
            else if (group.fontVersion() == null) { PortfolioFontManifests.unavailable(manifest, group, PortfolioFontManifests.MISSING_VERSION); }
            else if (sources.resolve(group.fontId(), group.fontVersion()) == null) { PortfolioFontManifests.unavailable(manifest, group, PortfolioFontManifests.UNKNOWN_VERSION); }
            else { missing.add(group); }
        }
        Set<String> newKeys = new HashSet<>();
        if (bucketUsable && !missing.isEmpty() && properties.isEnabled() && sources.isReady()) {
            List<Asset> completed = new ArrayList<>();
            try (var budget = createBudget(properties.getPrepareBudgetMs())) {
                try { bounded(() -> generate(snapshot.getId(), missing, prefix, budget, completed), budget); }
                catch (Exception exception) { log.warn("字体同步准备结束并降级: portfolioId={}, reason={}", snapshot.getId(), exception.toString()); }
                synchronized (budget) {
                    // bounded 已关闭预算，迟到上传无法再加入 completed。
                    manifest.getAssets().addAll(completed);
                    completed.forEach(asset -> newKeys.add(asset.getObjectKey()));
                }
            }
        }
        for (var group : missing) {
            if (PortfolioFontManifests.find(group, manifest) == null) { PortfolioFontManifests.unavailable(manifest, group, PortfolioFontManifests.UNAVAILABLE_REASON); }
        }
        return new Prepared(snapshot.getCurrentRevision(), plan, manifest, newKeys);
    }

    /** 行锁内复核，已离开当前两列的旧对象不得重新接入；本次不再准备。 */
    public String accept(PortfolioEntity locked, PortfolioFontPlan plan, Prepared prepared) {
        if (prepared.consumed) { throw new IllegalStateException(PortfolioFontMessage.PREPARED_ALREADY_CONSUMED); }
        if (!Objects.equals(prepared.planHash(), plan.hash())) { throw new PlanConflict(); }
        prepared.consumed = true;
        var output = PortfolioFontManifests.empty(plan);
        if (output == null) { return null; }
        var draft = PortfolioFontManifests.parse(locked.getDraftFontAssetsJson());
        var published = PortfolioFontManifests.parse(locked.getPublishedFontAssetsJson());
        for (var group : plan.groups()) {
            Asset candidate = PortfolioFontManifests.find(group, prepared.manifest);
            Asset current = PortfolioFontManifests.find(group, draft, published);
            if (candidate != null && candidate.getBucket() != null && !candidate.getBucket().isBlank() && Objects.equals(candidate.getBucket(), storage.bucket()) && (prepared.newKeys.contains(candidate.getObjectKey())
                    || current != null && Objects.equals(current.getBucket(), candidate.getBucket()) && Objects.equals(current.getObjectKey(), candidate.getObjectKey()))) { output.getAssets().add(candidate); }
            else { PortfolioFontManifests.unavailable(output, group, PortfolioFontManifests.reason(plan, group, prepared.manifest)); }
        }
        return JSON.toJSONString(output);
    }

    /** 发布只复制计划匹配的草稿清单，不生成或上传字体。 */
    public String publish(PortfolioEntity locked, PortfolioFontPlan plan) {
        var draft = PortfolioFontManifests.parse(locked.getDraftFontAssetsJson());
        var result = PortfolioFontManifests.empty(plan);
        if (result == null) { return null; }
        boolean matching = draft != null && Objects.equals(draft.getPlanVersion(), PortfolioFontPlan.VERSION)
                && plan.hash().equals(draft.getPlanHash());
        for (var group : plan.groups()) {
            Asset asset = matching ? PortfolioFontManifests.find(group, draft) : null;
            if (asset == null) { PortfolioFontManifests.unavailable(result, group, PortfolioFontManifests.reason(plan, group, draft)); }
            else { result.getAssets().add(asset); }
        }
        return JSON.toJSONString(result);
    }

    /** 清理汇总原因；只留诊断，不生成补偿任务。 */
    private enum CleanupReason { INVALID_PREFIX, BUCKET_MISSING, BUCKET_MISMATCH, LEGACY_PREFIX,
        PREFIX_MISMATCH, INVALID_KEY, DELETE_CONFIRMED, DELETE_FAILED, DEADLINE_EXPIRED, CLEANUP_FAILED }
    /** 完整作品集前缀必须包含主体及作品集 ID，禁止空前缀变成全桶匹配。 */
    private static final String FONT_PREFIX_PATTERN = "[A-Za-z0-9_-]+/others/fonts/[0-9]+/";
    /** 单个 WOFF 叶子对象，目录占位、嵌套目录和路径穿越均不能删除。 */
    private static final String FONT_FILE_PATTERN = "[A-Za-z0-9_-]+\\.woff";
    /** 旧字体子目录仅用于识别残留原因，不允许自动删除。 */
    private static final String LEGACY_FONT_PATTERN = "[A-Za-z0-9_-]+/protfolio/[0-9]+/fonts/.*";

    /** 全部事务和锁释放后同步尝试本次差集；不补发、不改变已提交结果。 */
    public void cleanup(List<String> keys, String prefix) {
        if (keys.isEmpty() || !properties.isCleanupEnabled()) { return; }
        requireOutsideTransaction();
        var counts = new EnumMap<CleanupReason, AtomicInteger>(CleanupReason.class);
        for (var reason : CleanupReason.values()) { counts.put(reason, new AtomicInteger()); }
        if (prefix == null || !prefix.matches(FONT_PREFIX_PATTERN)) {
            counts.get(CleanupReason.INVALID_PREFIX).set(keys.size());
            log.warn("字体清理拒绝: counts={}", counts); return;
        }
        try (var budget = createBudget(properties.getDeleteBudgetMs())) {
            bounded(() -> {
                if (!deleteSlots.tryAcquire(budget.remaining(), TimeUnit.MILLISECONDS)) { throw new TimeoutException(); }
                try {
                    String bucket = storage.bucket();
                    for (String identity : new HashSet<>(keys)) {
                        budget.remaining();
                        int separator = identity == null ? -1 : identity.indexOf('|');
                        String owner = separator > 0 ? identity.substring(0, separator) : null;
                        if (bucket == null || bucket.isBlank() || owner == null || owner.isBlank() || "null".equals(owner)) {
                            counts.get(CleanupReason.BUCKET_MISSING).incrementAndGet(); continue;
                        }
                        if (!owner.equals(bucket)) { counts.get(CleanupReason.BUCKET_MISMATCH).incrementAndGet(); continue; }
                        String key = identity.substring(separator + 1);
                        if (!key.startsWith(prefix)) {
                            counts.get(key.matches(LEGACY_FONT_PATTERN) ? CleanupReason.LEGACY_PREFIX : CleanupReason.PREFIX_MISMATCH).incrementAndGet(); continue;
                        }
                        if (!key.substring(prefix.length()).matches(FONT_FILE_PATTERN)) { counts.get(CleanupReason.INVALID_KEY).incrementAndGet(); continue; }
                        try { storage.delete(key, budget); counts.get(CleanupReason.DELETE_CONFIRMED).incrementAndGet(); }
                        catch (Exception exception) {
                            counts.get(CleanupReason.DELETE_FAILED).incrementAndGet();
                            log.warn("字体删除未确认成功: objectKey={}, reason={}", key, exception.toString());
                        }
                    }
                } finally { deleteSlots.release(); }
                return null;
            }, budget);
        } catch (TimeoutException exception) { counts.get(CleanupReason.DEADLINE_EXPIRED).incrementAndGet(); }
        catch (Exception exception) { counts.get(CleanupReason.CLEANUP_FAILED).incrementAndGet(); }
        finally { log.info("字体清理本次汇总: requested={}, counts={}", keys.size(), counts); }
    }

    /** 在受限名额内一次生成和上传，每次新生成使用新对象键。 */
    private List<Asset> generate(Long portfolioId, List<PortfolioFontPlan.Group> groups, String prefix, PortfolioFontBudget budget, List<Asset> completed) throws Exception {
        Semaphore portfolioSlot = portfolioSlots[Math.floorMod(portfolioId.hashCode(), portfolioSlots.length)];
        if (!portfolioSlot.tryAcquire()) { throw new TimeoutException(PortfolioFontMessage.PREPARE_ALREADY_RUNNING); }
        boolean acquired = false;
        Path work = null;
        try {
            acquired = slots.tryAcquire(budget.remaining(), TimeUnit.MILLISECONDS);
            if (!acquired) { throw new TimeoutException(); }
            if (!sources.isReady() || !properties.isEnabled()) { return List.of(); }
            try { work = Files.createTempDirectory(sources.getDirectory(), "request-"); }
            catch (java.io.IOException exception) {
                sources.fail(PortfolioFontSources.Failure.WORK_DIRECTORY_UNAVAILABLE); throw exception;
            }
            List<Asset> result = new ArrayList<>();
            for (var output : runner.generate(groups, work, budget)) {
                budget.remaining();
                String id = UUID.randomUUID().toString();
                String key = prefix + id + ".woff";
                String url;
                if (!sources.isReady() || !properties.isEnabled()) { break; }
                try { url = storage.upload(output.file(), key, budget); }
                catch (Exception exception) {
                    log.warn("单组字体上传失败: fontId={}, reason={}", output.group().fontId(), exception.toString());
                    continue;
                }
                Asset asset = new Asset();
                asset.setBucket(storage.bucket()); asset.setAssetId(id); asset.setFontId(output.group().fontId()); asset.setFontVersion(output.group().fontVersion());
                asset.setFontWeight(output.weight()); asset.setFontStyle(output.group().fontStyle());
                asset.setDemandHash(output.group().demandHash()); asset.setSubsetHash(output.subsetHash());
                asset.setStatus(PortfolioFontManifests.READY); asset.setFormat(PortfolioFontManifests.WOFF);
                asset.setBytes(Files.size(output.file())); asset.setUrl(url); asset.setObjectKey(key); result.add(asset);
                synchronized (budget) { budget.remaining(); completed.add(asset); }
            }
            return result;
        } finally {
            PortfolioFontSubsetRunner.removeWorkDirectory(work);
            if (acquired) { slots.release(); }
            portfolioSlot.release();
        }
    }

    /** 每次请求独立预算；测试可提供受控单调时钟，生产仍使用系统时钟。 */
    PortfolioFontBudget createBudget(long millis) { return new PortfolioFontBudget(millis); }

    /** 当前请求同步等待，超时同时取消任务、进程和网络连接。 */
    private <T> T bounded(Callable<T> action, PortfolioFontBudget budget) throws Exception {
        Future<T> future = executor.submit(action);
        try { return future.get(budget.remaining(), TimeUnit.MILLISECONDS); }
        finally { budget.close(); future.cancel(true); }
    }

    /** 运行时护栏阻止将字体 I/O 放进数据库事务。 */
    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException(PortfolioFontMessage.IO_REQUIRES_NO_TRANSACTION); }
    }

    /** 服务停止时取消尚未完成的本次同步操作。 */
    @PreDestroy public void close() { executor.shutdownNow(); }
}
