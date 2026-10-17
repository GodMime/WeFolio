package com.jxc.wefolio.job.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.dict.AiTaskStatusDict;
import com.jxc.wefolio.job.service.PortfolioAiReferenceContract;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.jxc.wefolio.job.service.PortfolioAiReferenceContract.*;

/** 参考附件当前授权和保护引用读取；数据库异常、坏快照都不能视为无引用。 */
@Repository
@RequiredArgsConstructor
public class PortfolioAiReferenceRepository {
    /** 与 Runtime DATETIME 使用同一业务时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** 保护窗口按共享任务字典解释，未知状态仍阻止清理。 */
    private static final Set<String> ACTIVE = Set.of(AiTaskStatusDict.PENDING.getCode(), AiTaskStatusDict.RUNNING.getCode());
    private static final Set<String> TERMINAL = Set.of(AiTaskStatusDict.SUCCEEDED.getCode(), AiTaskStatusDict.FAILED.getCode(), AiTaskStatusDict.CANCELLED.getCode());
    /** 当前引用直接读主库，不缓存。 */
    private final JdbcTemplate jdbc;
    /** 清理可识别已逻辑删除的归属，唯一编码歧义时停止该目录。 */
    public Optional<Owner> owner(String code) {
        List<Owner> rows = jdbc.query("SELECT id,'USER' owner_type,unique_code FROM wf_user WHERE unique_code=? "
                        + "UNION ALL SELECT id,'TEAM' owner_type,unique_code FROM wf_team WHERE unique_code=?",
                (rs, n) -> new Owner(rs.getString("owner_type"), rs.getLong("id"), rs.getString("unique_code")), code, code);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }
    /** 执行前取得当前有效 owner 编码；维护权限另由既有媒体权限边界检查。 */
    public Owner currentOwner(AiGenerationTaskEntity task) {
        String table = switch (task.getOwnerType()) { case OWNER_USER -> "wf_user"; case OWNER_TEAM -> "wf_team";
            default -> throw new IllegalStateException("参考附件归属不合法"); };
        List<Owner> rows = jdbc.query("SELECT unique_code FROM " + table + " WHERE id=? AND deleted=0 AND status='ACTIVE'",
                (rs, n) -> new Owner(task.getOwnerType(), task.getOwnerId(), rs.getString("unique_code")), task.getOwnerId());
        if (rows.size() != 1) { throw new IllegalStateException("参考附件归属已失效"); }
        if (task.getPortfolioId() != null) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wf_portfolio WHERE id=? AND owner_type=? AND owner_id=? AND deleted=0",
                    Integer.class, task.getPortfolioId(), task.getOwnerType(), task.getOwnerId());
            if (count == null || count != 1) { throw new IllegalStateException("作品集已失效"); }
        }
        return rows.getFirst();
    }
    /** 锁内按最新两列构造该 owner 当前保护键集；未满上传窗口由 COS HEAD 单独判断。 */
    public Set<String> protectedKeys(Owner owner, Instant now) {
        Set<String> keys = new HashSet<>();
        for (String raw : jdbc.queryForList("SELECT ai_reference_attachments_json FROM wf_portfolio WHERE owner_type=? AND owner_id=? AND deleted=0 AND ai_reference_attachments_json IS NOT NULL",
                String.class, owner.type(), owner.id())) {
            for (JsonNode item : PortfolioAiReferenceContract.items(raw, true)) { add(keys, owner, item); }
        }
        List<TaskReference> tasks = jdbc.query("SELECT reference_attachments_json,status,completed_at,deleted FROM wf_ai_generation_task "
                        + "WHERE owner_type=? AND owner_id=? AND reference_attachments_json IS NOT NULL",
                (rs, n) -> new TaskReference(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDateTime.class), rs.getLong(4)),
                owner.type(), owner.id());
        for (TaskReference task : tasks) {
            List<JsonNode> items = PortfolioAiReferenceContract.items(task.json(), false);
            boolean retained;
            if (ACTIVE.contains(task.status())) { retained = task.deleted() == 0; }
            else if (TERMINAL.contains(task.status())) {
                if (task.completed() == null) { throw new IllegalStateException("参考任务终态缺少完成时间"); }
                retained = now.isBefore(task.completed().atZone(BUSINESS_ZONE).toInstant().plusSeconds(PortfolioAiReferenceContract.RETENTION_SECONDS));
            } else { throw new IllegalStateException("参考任务状态不合法"); }
            // 即使过期也校验 key 归属，异常数据不可被当作可删除证据。
            Set<String> validated = new HashSet<>();
            for (JsonNode item : items) { add(validated, owner, item); }
            if (retained) { keys.addAll(validated); }
        }
        return Set.copyOf(keys);
    }
    /** 精确 owner 前缀核验，禁止同 owner 列引用别人的对象。 */
    private void add(Set<String> keys, Owner owner, JsonNode item) {
        String key = item.path(KEY).asText();
        if (!key.startsWith(owner.code() + PortfolioAiReferenceContract.DIRECTORY)) { throw new IllegalStateException("附件引用归属不一致"); }
        keys.add(key);
    }
    /** 已识别的真实归属，与 Runtime 命名锁入参一致。 */
    public record Owner(String type, long id, String code) { }
    /** 任务保护条件需要读取状态和首次完成时刻。 */
    private record TaskReference(String json, String status, LocalDateTime completed, long deleted) { }
}
