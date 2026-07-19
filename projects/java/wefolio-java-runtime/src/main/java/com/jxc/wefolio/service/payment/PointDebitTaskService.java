package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PointDebitTaskStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.mapper.PointDebitTaskEntityMapper;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 待扣活动任务保障服务 — 在本地消费事务内保证每个用户至多一条阻塞任务。
 */
@Service
@RequiredArgsConstructor
public class PointDebitTaskService {

    /** 扣币任务号前缀。 */
    private static final String TASK_NO_PREFIX = "WFD";

    /** 微信订单号最大长度。 */
    private static final int TASK_NO_RANDOM_LENGTH = 29;

    /** 活动任务固定标记。 */
    private static final int ACTIVE_FLAG = 1;

    /** 扣币任务 Mapper。 */
    private final PointDebitTaskEntityMapper pointDebitTaskEntityMapper;

    /** 积分账户 Mapper。 */
    private final PointAccountEntityMapper pointAccountEntityMapper;

    /** 新任务默认待处理延时。 */
    @Value("${wechat.virtual-payment.settlement.delay:10s}")
    private Duration settlementDelay;

    /**
     * 在账户仍有待扣时保证存在活动任务，同一延时窗口不会滚动延后执行时间。
     *
     * @param account 最新积分账户
     * @return 已存在或新创建的活动任务；没有待扣时为空
     */
    public PointDebitTaskEntity ensureActiveTask(PointAccountEntity account) {
        if (account == null || value(account.getPendingDebit()) <= 0L) {
            return null;
        }
        PointDebitTaskEntity existing = findActiveTask(account.getUserId());
        if (existing != null) {
            return existing;
        }

        String taskNo = createTaskNo();
        pointDebitTaskEntityMapper.insertActiveTask(
                taskNo,
                account.getId(),
                account.getUserId(),
                PointDebitTaskStatusDict.WAITING.getCode(),
                LocalDateTime.now().plus(effectiveDelay())
        );
        PointDebitTaskEntity active = findActiveTask(account.getUserId());
        if (active == null) {
            throw new IllegalStateException("待扣活动任务创建后不可读取");
        }
        return active;
    }

    /** 按用户查询最新账户并保障活动任务。 */
    public PointDebitTaskEntity ensureActiveTask(Long userId) {
        PointAccountEntity account = pointAccountEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointAccountEntity.class)
                        .eq(PointAccountEntity::getUserId, userId)
                        .last("LIMIT 1")
        );
        return ensureActiveTask(account);
    }

    /** 新维护者会话可用时唤醒等待会话任务，并保障仍有待扣的活动任务。 */
    public PointDebitTaskEntity ensureActiveTaskAfterSessionRefresh(Long userId) {
        pointDebitTaskEntityMapper.wakeWaitingSession(
                userId, LocalDateTime.now().plus(effectiveDelay()));
        return ensureActiveTask(userId);
    }

    /** 查询用户当前阻塞后续任务的活动任务。 */
    public PointDebitTaskEntity findActiveTask(Long userId) {
        return pointDebitTaskEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointDebitTaskEntity.class)
                        .eq(PointDebitTaskEntity::getUserId, userId)
                        .eq(PointDebitTaskEntity::getActiveFlag, ACTIVE_FLAG)
                        .last("LIMIT 1")
        );
    }

    /** 生成满足微信长度约束的随机任务号。 */
    private String createTaskNo() {
        String random = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return TASK_NO_PREFIX + random.substring(0, TASK_NO_RANDOM_LENGTH);
    }

    /** 获取有效延时，测试或配置异常时仍使用十秒。 */
    private Duration effectiveDelay() {
        return settlementDelay == null || settlementDelay.isNegative() ? Duration.ofSeconds(10L) : settlementDelay;
    }

    /** 将可空长整数转换为零兜底值。 */
    private long value(Long number) {
        return number == null ? 0L : number;
    }
}
