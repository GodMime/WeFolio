package com.jxc.wefolio.job.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.dict.AiTaskStatusDict;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.model.PortfolioAiMediaSummaryIdentity;
import com.jxc.wefolio.job.service.PortfolioAiCallException;
import com.jxc.wefolio.job.service.PortfolioAiMediaAccessService;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract.*;

/** 作品独立摘要字段与任务独立费用字段的短事务，无额外缓存表或费用表。 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PortfolioAiMediaRepository {
    /** 摘要缓存元数据字段。 */
    private static final String CACHE_KEY = "cacheKey";
    private static final String PROMPT_VERSION = "promptVersion";
    /** SQL 访问器及全局硬预算。 */
    private final JdbcTemplate jdbc;
    private final PortfolioAiProperties properties;

    /** 缓存不授予授权；调用方必须先检查当前作品与团队权限，七天期限独立于作品帧。 */
    public String cached(long workId, PortfolioAiMediaSummaryIdentity identity, LocalDateTime now) {
        List<String> rows = jdbc.queryForList("""
                SELECT ai_media_summary_json FROM wf_work
                WHERE id=? AND deleted=0 AND status=? AND audit_status=? AND ai_media_summary_json IS NOT NULL
                  AND ai_media_summary_expires_at>?
                """, String.class, workId, WORK_ACTIVE, WorkAuditStatusDict.PASSED.getCode(), now);
        if (rows.isEmpty()) { return null; }
        ObjectNode value;
        try { value = StrictAiJson.parseObject(rows.getFirst(), 32 * 1024, 10); }
        catch (RuntimeException exception) { throw new PortfolioAiCallException(ERROR_INVALID_SUMMARY, "媒体摘要缓存JSON不合法", false); }
        if (!identityMatches(value, identity)) { return null; }
        JsonNode summary = value.path(SUMMARY);
        if (!summary.isObject()) { throw new PortfolioAiCallException(ERROR_INVALID_SUMMARY, "媒体摘要缓存结构不合法", false); }
        return summary.toString();
    }

    /**
     * 当前源及帧清单仍一致才缓存；普通作品更新不能回带旧缓存。
     * 显式保持 updated_at 与业务 version，避免缓存更新让当前冻结任务自失效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cache(long workId, PortfolioAiMediaSummaryIdentity identity, String summary, LocalDateTime now) {
        List<CacheSource> rows = jdbc.query("""
                SELECT media_object_key,media_sha256,video_frames_json FROM wf_work
                WHERE id=? AND deleted=0 AND status=? AND audit_status=? FOR UPDATE
                """, (rs, n) -> new CacheSource(rs.getString(1), rs.getString(2), rs.getString(3)),
                workId, WORK_ACTIVE, WorkAuditStatusDict.PASSED.getCode());
        if (rows.isEmpty() || !sourceMatches(rows.getFirst(), identity)) { throw unavailable(); }
        ObjectNode cache = StrictAiJson.mapper().createObjectNode().put(CACHE_KEY, identity.cacheKey())
                .put(KEY_SOURCE_VERSION, identity.sourceVersion()).put(KEY_MODEL, identity.model())
                .put(PROMPT_VERSION, identity.promptVersion()).put(KEY_ROUTE, identity.route())
                .put(KEY_MANIFEST_HASH, identity.manifestHash());
        cache.set(SUMMARY, StrictAiJson.parseObject(summary, 16 * 1024, 8));
        jdbc.update("""
                UPDATE wf_work SET ai_media_summary_json=?,ai_media_summary_expires_at=?,updated_at=updated_at
                WHERE id=? AND deleted=0
                """, cache.toString(), now.plusDays(properties.getMediaCacheDays()), workId);
    }

    /** 用户行锁串行所有实例每日预留，再锁任务并确认当前代次；费用更新不改变任务业务版本。 */
    @Transactional(rollbackFor = Exception.class)
    public int reserve(AiGenerationTaskEntity task, int cents, LocalDateTime now) {
        BudgetCapacity capacity = lockCapacity(task, now);
        requireCapacity(capacity, cents, 1);
        int number = capacity.budget().reserve(task.getAttemptCount(), cents, now.toLocalDate());
        writeBudget(task.getId(), capacity.budget());
        return number;
    }

    /** 首次付费前检查全部剩余步骤的上界，不创建预留，也不声称占用了未来并发额度。 */
    @Transactional(rollbackFor = Exception.class)
    public void checkCapacity(AiGenerationTaskEntity task, int cents, int calls, LocalDateTime now) {
        requireCapacity(lockCapacity(task, now), cents, calls);
    }

    /** 用户锁保证本次检查跨实例一致；后续实际发送仍必须逐次重新检查并预留。 */
    private BudgetCapacity lockCapacity(AiGenerationTaskEntity task, LocalDateTime now) {
        List<Long> locked = jdbc.queryForList("SELECT id FROM wf_user WHERE id=? AND deleted=0 AND status=? FOR UPDATE",
                Long.class, task.getRequestedBy(), USER_ACTIVE);
        if (locked.isEmpty()) { throw unavailable(); }
        List<TaskBudget> rows = jdbc.query("""
                SELECT requested_by,status,version,deleted,attempt_count,media_budget_json,media_budget_unknown_count,
                CASE WHEN TIMESTAMPADD(SECOND,?,created_at)>%s THEN 1 ELSE 0 END AS alive
                FROM wf_ai_generation_task WHERE id=? FOR UPDATE
                """.formatted(PortfolioAiDatabaseTime.NOW_SQL), (rs, n) -> new TaskBudget(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        rs.getInt(5), rs.getString(6), rs.getInt(7), rs.getInt(8) == 1),
                properties.getTotalTimeoutSeconds(), task.getId());
        if (rows.isEmpty()) { throw unavailable(); }
        TaskBudget row = rows.getFirst();
        if (row.requester() != task.getRequestedBy() || !AiTaskStatusDict.RUNNING.getCode().equals(row.status())
                || row.version() != task.getVersion() || row.deleted() != 0
                || row.attempt() != task.getAttemptCount() || !row.alive()) { throw unavailable(); }
        PortfolioAiMediaBudget budget = validatedBudget(row.json(), row.unknown());
        long dailyTotal = 0;
        // 已失败、取消或逻辑删除的任务仍保留已发送调用，不能通过取消或删除重置当日额度。
        List<DailyBudget> daily = jdbc.query("""
                SELECT id,created_at,media_budget_json,media_budget_unknown_count FROM wf_ai_generation_task
                WHERE requested_by=? AND (media_budget_json IS NOT NULL OR media_budget_unknown_count>0)
                """, (rs, n) -> new DailyBudget(rs.getLong(1), rs.getObject(2, LocalDateTime.class),
                        rs.getString(3), rs.getInt(4)), task.getRequestedBy());
        for (DailyBudget stored : daily) {
            // 已达到日上限后无需累加超大历史金额，避免不同坏行的合计溢出继续冒泡。
            dailyTotal += Math.min(dailyTotal(stored, now), properties.getMediaDailyBudgetCents() - dailyTotal);
        }
        return new BudgetCapacity(budget, dailyTotal);
    }

    /** 数量和费用均按整个任务累计，不能在重试后重置。 */
    private void requireCapacity(BudgetCapacity capacity, int cents, int calls) {
        if (cents < 1 || calls < 1 || cents > properties.getMediaTaskBudgetCents() - capacity.budget().total()
                || cents > properties.getMediaDailyBudgetCents() - capacity.dailyTotal()
                || calls > properties.getMediaMaxCalls() - capacity.budget().callCount()) {
            throw new PortfolioAiCallException(ERROR_BUDGET_EXHAUSTED, "媒体理解预算已用尽，请稍后重试", false);
        }
    }

    /** 完整 usage 才结算；已取消、已清隐私或逻辑删除的费用行仍可核对，重复结算不得改写已知值。 */
    @Transactional(rollbackFor = Exception.class, noRollbackFor = PortfolioAiCallException.class)
    public void settle(AiGenerationTaskEntity task, int callNo, Long prompt, Long completion, LocalDateTime now) {
        if (prompt == null || completion == null || prompt < 0 || completion < 0) { return; }
        // 清理只锁任务行，不申请用户锁；这里固定用户 -> 任务顺序以避免跨实例预算竞态。
        jdbc.queryForList("SELECT id FROM wf_user WHERE id=? FOR UPDATE", Long.class, task.getRequestedBy());
        List<StoredBudget> rows = jdbc.query("""
                SELECT media_budget_json,media_budget_unknown_count FROM wf_ai_generation_task
                WHERE id=? AND requested_by=? FOR UPDATE
                """, (rs, n) -> new StoredBudget(rs.getString(1), rs.getInt(2)), task.getId(), task.getRequestedBy());
        if (rows.isEmpty()) { throw unavailable(); }
        PortfolioAiMediaBudget budget = validatedBudget(rows.getFirst().json(), rows.getFirst().unknown());
        PortfolioAiMediaBudget.Call call = budget.find(task.getAttemptCount(), callNo);
        if (call == null) { throw unavailable(); }
        // 第一次完整返回才写费用，晚到或重复 usage 不改变已知真实费用。
        if (call.charged() != null) { return; }
        long cents = costCents(prompt, completion);
        budget.settle(call, cents, prompt, completion);
        writeBudget(task.getId(), budget);
        if (cents > call.reserved()) {
            throw new PortfolioAiCallException(ERROR_BUDGET_OVERRUN, "模型用量超过预留，本次任务已停止", false);
        }
    }

    /**
     * 调用方确认尚未进入模型客户端才可释放预留；失权、取消或代次更新不能阻止退款。
     * 保留调用序号，费用更新仍不续约、不改变业务版本，重复释放或已结算调用均保持原账本。
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseUnsent(AiGenerationTaskEntity task, int callNo) {
        jdbc.queryForList("SELECT id FROM wf_user WHERE id=? FOR UPDATE", Long.class, task.getRequestedBy());
        List<StoredBudget> rows = jdbc.query("""
                SELECT media_budget_json,media_budget_unknown_count FROM wf_ai_generation_task
                WHERE id=? AND requested_by=? FOR UPDATE
                """, (rs, n) -> new StoredBudget(rs.getString(1), rs.getInt(2)), task.getId(), task.getRequestedBy());
        if (rows.isEmpty()) { throw unavailable(); }
        PortfolioAiMediaBudget budget = validatedBudget(rows.getFirst().json(), rows.getFirst().unknown());
        PortfolioAiMediaBudget.Call call = budget.find(task.getAttemptCount(), callNo);
        if (call == null) { throw unavailable(); }
        if (budget.releaseUnsent(call)) { writeBudget(task.getId(), budget); }
    }

    /** 广州输入0.8/输出2.8元每百万tokens，费用向上取整为整数分。 */
    public static long costCents(long prompt, long completion) {
        BigInteger units = BigInteger.valueOf(prompt).multiply(BigInteger.valueOf(80))
                .add(BigInteger.valueOf(completion).multiply(BigInteger.valueOf(280)));
        return units.add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000)).longValueExact();
    }

    /** 小批只清作品上的过期摘要，显式抑制 MySQL ON UPDATE；帧、源、作品版本均保留。 */
    public int cleanup(LocalDateTime now, int limit) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM wf_work WHERE ai_media_summary_expires_at<=? ORDER BY ai_media_summary_expires_at,id LIMIT ?
                """, Long.class, now, limit);
        int count = 0;
        for (Long id : ids) {
            count += jdbc.update("""
                    UPDATE wf_work SET ai_media_summary_json=NULL,ai_media_summary_expires_at=NULL,updated_at=updated_at
                    WHERE id=? AND ai_media_summary_expires_at<=?
                    """, id, now);
        }
        return count;
    }

    /** 元数据全部匹配才命中，修改模型、提示词、路线或帧策略不会复用旧摘要。 */
    private boolean identityMatches(ObjectNode value, PortfolioAiMediaSummaryIdentity identity) {
        return identity.cacheKey().equals(value.path(CACHE_KEY).asText())
                && identity.sourceVersion().equals(value.path(KEY_SOURCE_VERSION).asText())
                && identity.model().equals(value.path(KEY_MODEL).asText())
                && identity.promptVersion().equals(value.path(PROMPT_VERSION).asText())
                && identity.route().equals(value.path(KEY_ROUTE).asText())
                && identity.manifestHash().equals(value.path(KEY_MANIFEST_HASH).asText());
    }

    /** 只用当前作品 digest 重算身份，禁止在源替换或帧重新发布后写旧缓存。 */
    private boolean sourceMatches(CacheSource source, PortfolioAiMediaSummaryIdentity identity) {
        if (!Objects.equals(source.key(), identity.sourceObjectKey())
                || !PortfolioAiMediaAccessService.fingerprint(source.key(), identity.sourceEtag(), identity.sourceBytes(), source.sha())
                    .equals(identity.sourceVersion())) { return false; }
        if (!ROUTE_FRAMES.equals(identity.route())) { return true; }
        if (source.manifest() == null) { return false; }
        ObjectNode manifest = StrictAiJson.parseObject(source.manifest(), 128 * 1024, 12);
        return PortfolioAiMediaAccessService.hash(manifest.toString()).equals(identity.manifestHash());
    }

    /** 未知计数用于清理索引与保护，必须与 JSON 事实一致才允许继续付费。 */
    private PortfolioAiMediaBudget validatedBudget(String json, int unknown) {
        try {
            PortfolioAiMediaBudget budget = PortfolioAiMediaBudget.parse(json);
            if (budget.unknownCount() != unknown) { throw new IllegalStateException("媒体费用未知计数不一致"); }
            // 账本总额溢出同样属于坏行，必须在进入预算分支前转换为可处理的隔离结果。
            budget.total();
            return budget;
        } catch (RuntimeException exception) {
            throw new PortfolioAiCallException(ERROR_BUDGET_INVALID, "媒体费用记录不合法，当前任务已隔离，请联系管理员核对", false);
        }
    }

    /**
     * 坏账本保留原文并隔离；仅在任务可能发送付费请求的日期计入完整任务上限。
     * 请求的发送不能晚于绝对期限，因此历史坏行不会永久冻结请求人的未来额度。
     */
    private long dailyTotal(DailyBudget stored, LocalDateTime now) {
        try { return validatedBudget(stored.json(), stored.unknown()).total(now.toLocalDate()); }
        catch (PortfolioAiCallException exception) {
            log.warn("媒体费用账本已隔离: taskId={}", stored.id());
            if (stored.createdAt() == null) { return properties.getMediaTaskBudgetCents(); }
            var day = now.toLocalDate();
            return !day.isBefore(stored.createdAt().toLocalDate())
                    && !day.isAfter(stored.createdAt().plusSeconds(properties.getTotalTimeoutSeconds()).toLocalDate())
                    ? properties.getMediaTaskBudgetCents() : 0;
        }
    }

    /** 费用更新不改业务版本或租约心跳，晚到结算不得复活已过期的执行代次。 */
    private void writeBudget(long taskId, PortfolioAiMediaBudget budget) {
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json=?,media_budget_unknown_count=?,updated_at=updated_at WHERE id=?",
                budget.json(), budget.unknownCount(), taskId);
    }
    /** 当前任务或作品源失效。 */
    private static PortfolioAiCallException unavailable() { return new PortfolioAiCallException(ERROR_SOURCE_UNAVAILABLE, "作品或当前执行权限已失效", false); }
    /** 已锁定的任务费用与当前业务代次。 */
    private record TaskBudget(long requester, String status, int version, long deleted, int attempt, String json, int unknown, boolean alive) { }
    /** 每日扫描或已锁定任务的独立费用。 */
    private record StoredBudget(String json, int unknown) { }
    /** 其它任务的日费用及不可延期的可能发送日期。 */
    private record DailyBudget(long id, LocalDateTime createdAt, String json, int unknown) { }
    /** 当前任务账本及请求人当日保守费用，不包含任何新预留。 */
    private record BudgetCapacity(PortfolioAiMediaBudget budget, long dailyTotal) { }
    /** 已锁定作品的当前源与帧清单。 */
    private record CacheSource(String key, String sha, String manifest) { }
}
