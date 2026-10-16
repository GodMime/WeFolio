package com.jxc.wefolio.service.portfolioai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDateTime;
import java.util.List;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 提交短事务：请求人行锁保护本人额度与幂等，另用公共行锁串行化全局容量计数与入队。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiTaskTransactionService {
    /** 所有活动态，不新增 RETRYING 状态。 */
    public static final List<String> ACTIVE = List.of(AiTaskStatusDict.PENDING.getCode(), AiTaskStatusDict.RUNNING.getCode());
    /** 可信附件快照解析；最终复验位于已取得 owner 锁的短事务中。 */
    private final PortfolioAiReferenceService references;
    /** 用户行锁访问器。 */
    private final UserEntityMapper users;
    /** 任务数据访问器。 */
    private final AiGenerationTaskEntityMapper tasks;
    /** 当前运行预算。 */
    private final PortfolioAiProperties properties;
    /** 最终短事务内复验当前归属权限。 */
    private final PortfolioAiFactsService facts;

    /** 同键同参返回原冻结任务，异参拒绝；整个事务不包含模型调用或字体工作。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AiGenerationTaskEntity submit(Long requester, String key, ObjectNode frozen) {
        return submitInternal(requester, key, frozen, JsonNodeFactory.instance.arrayNode());
    }

    /** 带附件提交由应用入口先取得 owner 锁，再进入同一接单事务。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AiGenerationTaskEntity submit(Long requester, String key, ObjectNode frozen, ArrayNode referenceItems) {
        return submitInternal(requester, key, frozen, referenceItems);
    }

    /** 两条入口共享同参检查与全局接单锁，附件不重复写入原请求。 */
    private AiGenerationTaskEntity submitInternal(Long requester, String key, ObjectNode frozen, ArrayNode referenceItems) {
        if (users.lockActiveUserById(requester) == null) { throw new BusinessException(PortfolioAiMessage.INVALID_INPUT); }
        ObjectNode input = (ObjectNode) frozen.path(KEY_INPUT);
        PortfolioEntity portfolio = facts.requireOwner(input, requester);
        AiGenerationTaskEntity replay = find(requester, key);
        if (replay != null) { requireSame(replay, input, referenceItems); return replay; }
        // 最小物理用户行只作为公共互斥点，不代表它属于请求人或处于活动状态。
        // 已锁定有效请求人后守卫仍为空属于接单设施异常，不能误报已有活动任务。
        if (users.lockAiAdmissionGuard() == null) { throw new BusinessException(PortfolioAiMessage.DISABLED); }
        if (tasks.selectCount(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                .eq(AiGenerationTaskEntity::getRequestedBy, requester).in(AiGenerationTaskEntity::getStatus, ACTIVE)) > 0
                || tasks.selectCount(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                .in(AiGenerationTaskEntity::getStatus, ACTIVE)) >= properties.getMaxActiveTasks()) {
            throw new BusinessException(PortfolioAiMessage.ACTIVE_TASK);
        }
        String referenceJson = null;
        if (!referenceItems.isEmpty()) {
            int limit = Math.min(properties.getReferenceMaxActiveTasks(), properties.getMaxActiveTasks() - 1);
            if (!properties.isReferenceEnabled() || limit < 1) { throw new BusinessException(PortfolioAiMessage.DISABLED); }
            if (tasks.selectCount(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                    .in(AiGenerationTaskEntity::getStatus, ACTIVE).isNotNull(AiGenerationTaskEntity::getReferenceAttachmentsJson)) >= limit) {
                throw new BusinessException(PortfolioAiMessage.ACTIVE_TASK);
            }
            referenceJson = references.resolveForTask(input.path(KEY_OWNER_TYPE).asText(), PortfolioAiInput.id(input, KEY_OWNER_ID, false),
                    referenceItems, portfolio == null ? null : portfolio.getAiReferenceAttachmentsJson());
        }
        LocalDateTime now = PortfolioAiTime.now();
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setOwnerType(input.path(KEY_OWNER_TYPE).asText());
        task.setOwnerId(PortfolioAiInput.id(input, KEY_OWNER_ID, false));
        task.setPortfolioId(PortfolioAiInput.id(input, KEY_PORTFOLIO_ID, true));
        task.setRequestedBy(requester);
        task.setIdempotencyKey(key);
        task.setRequestJson(frozen.toString());
        task.setReferenceAttachmentsJson(referenceJson);
        task.setMediaBudgetUnknownCount(0);
        task.setStatus(AiTaskStatusDict.PENDING.getCode());
        task.setAttemptCount(0);
        task.setNextRunAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeleted(0L);
        task.setVersion(0);
        tasks.insert(task);
        return task;
    }

    /** 非事务提前查幂等可避免重新冻结事实，最终事务再次查以处理并发。 */
    public AiGenerationTaskEntity find(Long requester, String key) {
        List<AiGenerationTaskEntity> rows = tasks.selectList(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                .eq(AiGenerationTaskEntity::getRequestedBy, requester).eq(AiGenerationTaskEntity::getIdempotencyKey, key));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 只比较业务 input，不刷新模型参数、事实或再次调用模型。 */
    public void requireSame(AiGenerationTaskEntity task, ObjectNode input) {
        requireSame(task, input, JsonNodeFactory.instance.arrayNode());
    }

    /** 临时回执更新不改变业务身份；顺序、编号和说明都参与双列幂等比较。 */
    public void requireSame(AiGenerationTaskEntity task, ObjectNode input, ArrayNode referenceItems) {
        ObjectNode old = StrictAiJson.parseObject(task.getRequestJson(), Integer.MAX_VALUE, 32);
        if (!old.path(KEY_INPUT).equals(input)
                || !PortfolioAiReferenceService.businessProjection(task.getReferenceAttachmentsJson())
                .equals(PortfolioAiReferenceService.normalizeBusinessProjection(referenceItems))) {
            throw new BusinessException(PortfolioAiMessage.IDEMPOTENCY_CONFLICT);
        }
    }
}
