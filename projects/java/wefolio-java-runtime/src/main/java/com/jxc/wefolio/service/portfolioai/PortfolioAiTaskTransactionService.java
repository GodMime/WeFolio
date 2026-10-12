package com.jxc.wefolio.service.portfolioai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        if (users.lockActiveUserById(requester) == null) { throw new BusinessException(PortfolioAiMessage.INVALID_INPUT); }
        ObjectNode input = (ObjectNode) frozen.path(KEY_INPUT);
        facts.requireOwner(input, requester);
        AiGenerationTaskEntity replay = find(requester, key);
        if (replay != null) { requireSame(replay, input); return replay; }
        // 最小物理用户行只作为公共互斥点，不代表它属于请求人或处于活动状态。
        // 已锁定有效请求人后守卫仍为空属于接单设施异常，不能误报已有活动任务。
        if (users.lockAiAdmissionGuard() == null) { throw new BusinessException(PortfolioAiMessage.DISABLED); }
        if (tasks.selectCount(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                .eq(AiGenerationTaskEntity::getRequestedBy, requester).in(AiGenerationTaskEntity::getStatus, ACTIVE)) > 0
                || tasks.selectCount(Wrappers.<AiGenerationTaskEntity>lambdaQuery()
                .in(AiGenerationTaskEntity::getStatus, ACTIVE)) >= properties.getMaxActiveTasks()) {
            throw new BusinessException(PortfolioAiMessage.ACTIVE_TASK);
        }
        LocalDateTime now = PortfolioAiTime.now();
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setOwnerType(input.path(KEY_OWNER_TYPE).asText());
        task.setOwnerId(PortfolioAiInput.id(input, KEY_OWNER_ID, false));
        task.setPortfolioId(PortfolioAiInput.id(input, KEY_PORTFOLIO_ID, true));
        task.setRequestedBy(requester);
        task.setIdempotencyKey(key);
        task.setRequestJson(frozen.toString());
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
        ObjectNode old = StrictAiJson.parseObject(task.getRequestJson(), Integer.MAX_VALUE, 32);
        if (!old.path(KEY_INPUT).equals(input)) { throw new BusinessException(PortfolioAiMessage.IDEMPOTENCY_CONFLICT); }
    }
}
