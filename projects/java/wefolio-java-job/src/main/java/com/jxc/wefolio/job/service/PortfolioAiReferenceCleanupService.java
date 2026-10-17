package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.repo.PortfolioAiReferenceRepository;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** COS 原件本身就是补偿线索；内存分页游标跨轮继续，不新增清理台账。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioAiReferenceCleanupService {
    /** 每轮三个独立硬预算，远端单次另由客户端限时。 */
    static final int MAX_OBJECTS = 1000;
    static final int MAX_CALLS = 200;
    private static final long MAX_NANOS = 60_000_000_000L;
    /** 单次持锁最多处理十个对象，避免阻塞当前 owner 保存。 */
    private static final int LOCK_BATCH = 10;
    /** 精确私有存储操作。 */
    private final PortfolioAiReferenceStorage storage;
    /** 当前保护引用与包含删除身份的 owner 反查。 */
    private final PortfolioAiReferenceRepository repository;
    /** 与 Runtime 保存/提交相同的跨进程命名锁。 */
    private final PortfolioAiReferenceOwnerLock locks;
    /** 根页完整消费后才推进，下次调度无需重新扫前面的 owner。 */
    private String rootMarker;
    private ObjectListing rootPage;
    private int ownerIndex;
    /** 当前 owner 的子页完整消费后才推进。 */
    private PortfolioAiReferenceRepository.Owner currentOwner;
    private String objectMarker;
    private ObjectListing objectPage;
    private int objectIndex;
    /** 每轮统计和时间只在同步执行期间更新。 */
    private int calls;
    private int inspected;
    private int deleted;
    private long started;
    /** 桶前提在轮内变化时停止本轮自动删除。 */
    private boolean halted;

    /** 关闭接单后仍继续回收既有对象；同实例不允许游标并发消费。 */
    public synchronized int cleanup() {
        calls = 0; inspected = 0; deleted = 0; halted = false; started = System.nanoTime();
        try { calls++; storage.requireVersioningOff(); }
        catch (RuntimeException exception) { warn("BUCKET_POLICY", null, exception); return 0; }
        while (available(1)) {
            if (rootPage == null) {
                try { calls++; rootPage = storage.owners(rootMarker); ownerIndex = 0; }
                catch (RuntimeException exception) { warn("ROOT_LIST", null, exception); break; }
            }
            if (ownerIndex >= rootPage.getCommonPrefixes().size()) {
                if (rootPage.isTruncated()) {
                    String marker = rootPage.getNextMarker();
                    if (marker == null || marker.equals(rootMarker)) { log.warn("参考附件根分页未推进，保留当前游标"); break; }
                    rootMarker = marker; rootPage = null; continue;
                }
                rootMarker = null; rootPage = null; ownerIndex = 0;
                log.info("参考附件完成整轮扫描 inspected={}, cosCalls={}, deleted={}", inspected, calls, deleted);
                break;
            }
            if (currentOwner == null && !selectOwner()) { continue; }
            if (objectPage == null) {
                try { calls++; objectPage = storage.objects(currentOwner.code(), objectMarker); objectIndex = 0; }
                catch (RuntimeException exception) { warn("OWNER_LIST", currentOwner, exception); nextOwner(); continue; }
            }
            if (objectIndex >= objectPage.getObjectSummaries().size()) {
                if (objectPage.isTruncated()) {
                    String marker = objectPage.getNextMarker();
                    if (marker == null || marker.equals(objectMarker)) {
                        log.warn("参考附件子分页未推进 ownerType={}, ownerId={}", currentOwner.type(), currentOwner.id()); nextOwner(); continue;
                    }
                    objectMarker = marker; objectPage = null; continue;
                }
                nextOwner(); continue;
            }
            // 先按列举写入时间筛选，每批保持精确游标；删除失败对象留待下个整轮。
            List<Candidate> candidates = new ArrayList<>();
            while (objectIndex < objectPage.getObjectSummaries().size() && candidates.size() < LOCK_BATCH && available(3)) {
                COSObjectSummary object = objectPage.getObjectSummaries().get(objectIndex++); inspected++;
                if (PortfolioAiReferenceContract.isObjectKey(object.getKey())
                        && object.getKey().startsWith(currentOwner.code() + PortfolioAiReferenceContract.DIRECTORY)
                        && object.getLastModified() != null && !fresh(object.getLastModified().toInstant(), Instant.now())) { candidates.add(new Candidate(object, objectIndex - 1)); }
            }
            if (!candidates.isEmpty()) { deleteCandidates(candidates); }
            else if (!available(3)) { break; }
        }
        log.info("参考附件清理批次 inspected={}, cosCalls={}, deleted={}, elapsedMs={}", inspected, calls, deleted,
                (System.nanoTime() - started) / 1_000_000);
        return deleted;
    }

    /** 不存在或重复的唯一编码不猜测归属；删除用户仍可通过原记录反查。 */
    private boolean selectOwner() {
        String prefix = rootPage.getCommonPrefixes().get(ownerIndex);
        if (!prefix.matches("[A-Za-z0-9_-]+/")) { nextOwner(); return false; }
        try {
            var owner = repository.owner(prefix.substring(0, prefix.length() - 1));
            if (owner.isEmpty()) { log.warn("参考附件无法确定根目录 owner，跳过 prefixHash={}", Integer.toHexString(prefix.hashCode())); nextOwner(); return false; }
            currentOwner = owner.orElseThrow(); return true;
        } catch (RuntimeException exception) { warn("OWNER_LOOKUP", null, exception); nextOwner(); return false; }
    }

    /** owner 锁覆盖当前读、最终 HEAD 与删除；坏 JSON 或查询失败跳过整个 owner。 */
    private void deleteCandidates(List<Candidate> candidates) {
        try {
            var acquired = locks.tryAcquire(currentOwner.type(), currentOwner.id());
            if (acquired.isEmpty()) { rewind(candidates.getFirst().index()); halted = true; return; }
            try (var lock = acquired.orElseThrow()) {
                if (!remoteAvailable(1)) { rewind(candidates.getFirst().index()); return; }
                try { calls++; storage.requireVersioningOff(); }
                catch (RuntimeException exception) { halted = true; throw exception; }
                Set<String> retained = repository.protectedKeys(currentOwner, Instant.now());
                for (int i = 0; i < candidates.size(); i++) {
                    if (!remoteAvailable(2)) { rewind(candidates.get(i).index()); break; }
                    String key = candidates.get(i).object().getKey();
                    if (retained.contains(key)) { continue; }
                    try {
                        lock.checkHeld(); calls++; ObjectMetadata head = storage.head(key);
                        if (head == null || head.getLastModified() == null || fresh(head.getLastModified().toInstant(), Instant.now())) { continue; }
                        lock.checkHeld(); calls++; storage.delete(key); deleted++;
                    } catch (RuntimeException exception) {
                        log.warn("参考附件对象清理失败 ownerType={}, ownerId={}, keyHash={}, exceptionType={}",
                                currentOwner.type(), currentOwner.id(), Integer.toHexString(key.hashCode()), exception.getClass().getSimpleName());
                    }
                }
            }
        } catch (RuntimeException exception) { warn("OWNER_CLEANUP", currentOwner, exception); nextOwner(); }
    }

    /** 预算耗尽或锁竞争时回退未处理候选，下轮从原页续跑。 */
    private void rewind(int index) { objectIndex = index; }
    /** 根 owner 仅在当前子目录完整结束或明确失败后推进。 */
    private void nextOwner() { ownerIndex++; currentOwner = null; objectMarker = null; objectPage = null; objectIndex = 0; }
    /** 精确边界为 now >= 写入时间 + 24h 时可进入引用检查。 */
    static boolean fresh(Instant modified, Instant now) { return now.isBefore(modified.plusSeconds(PortfolioAiReferenceContract.RETENTION_SECONDS)); }
    /** 先为一个完整对象的 HEAD/DELETE 留出调用预算。 */
    private boolean available(int requiredCalls) { return inspected < MAX_OBJECTS && remoteAvailable(requiredCalls); }
    /** 已筛出的本批对象允许完成，已消费的扫描额度不阻止最终安全检查。 */
    private boolean remoteAvailable(int requiredCalls) { return !halted && calls + requiredCalls <= MAX_CALLS && System.nanoTime() - started < MAX_NANOS; }
    /** 保留真实页内位置，预算耗尽时回退不能遗漏夹在新鲜对象前的旧对象。 */
    private record Candidate(COSObjectSummary object, int index) { }
    /** 日志只保留阶段、数据库身份和异常类型，不记录 COS 原文或签名。 */
    private void warn(String stage, PortfolioAiReferenceRepository.Owner owner, RuntimeException exception) {
        log.warn("参考附件清理跳过 stage={}, ownerType={}, ownerId={}, exceptionType={}", stage,
                owner == null ? null : owner.type(), owner == null ? null : owner.id(), exception.getClass().getSimpleName());
    }
}
