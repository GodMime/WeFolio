package com.jxc.wefolio.service.point;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.dict.PointTransactionTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.PointPendingDebitEntity;
import com.jxc.wefolio.entity.PointTransactionEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.InsufficientPointBalanceException;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.PointPendingDebitEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import com.jxc.wefolio.service.payment.PointDebitTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 统一积分命令事务服务 — 在用户锁内原子写账户、流水、待扣来源和活动任务。
 */
@Service
@RequiredArgsConstructor
public class PointCommandTransactionService {

    /** 微信赠送订单号前缀。 */
    private static final String GIFT_ORDER_NO_PREFIX = "WFG";

    /** 微信赠送订单号摘要长度。 */
    private static final int GIFT_ORDER_DIGEST_LENGTH = 29;

    /** 用户积分互斥。 */
    private final UserPointMutex userPointMutex;

    /** 用户 Mapper，用于校验启用用户。 */
    private final UserEntityMapper userEntityMapper;

    /** 积分账户 Mapper。 */
    private final PointAccountEntityMapper pointAccountEntityMapper;

    /** 积分流水 Mapper。 */
    private final PointTransactionEntityMapper pointTransactionEntityMapper;

    /** 待扣来源 Mapper。 */
    private final PointPendingDebitEntityMapper pointPendingDebitEntityMapper;

    /** 扣币活动任务保障服务。 */
    private final PointDebitTaskService pointDebitTaskService;

    /** 赠送订单 Mapper。 */
    private final PointGiftOrderEntityMapper pointGiftOrderEntityMapper;

    /** 创建赠送订单并按用户 ID 升序取得本地锁。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public GiftOrderResult createGiftOrders(List<GiftCommand> commands) {
        List<Long> userIds = commands.stream()
                .map(GiftCommand::userId)
                .distinct()
                .sorted()
                .toList();
        return executeWithUserLocks(userIds, 0, () -> createGiftOrdersInsideLocks(commands));
    }

    /** 按维护者语义执行完整本地扣除。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public PointMutationResult deductForMaintainer(DebitCommand command) {
        return userPointMutex.execute(command.userId(),
                () -> deductInsideLock(command, DebitMode.MAINTAINER).mutation());
    }

    /** 按访客语义执行完整本地扣除。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public PointMutationResult deductForVisitor(DebitCommand command) {
        return userPointMutex.execute(command.userId(),
                () -> deductInsideLock(command, DebitMode.VISITOR).mutation());
    }

    /** 按后台月费语义执行完整本地扣除或返回余额非正跳过。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public SystemDebitResult deductForSystem(DebitCommand command) {
        return userPointMutex.execute(command.userId(), () -> {
            DebitOutcome outcome = deductInsideLock(command, DebitMode.SYSTEM);
            return outcome.skipped() ? SystemDebitResult.skipped() : SystemDebitResult.deducted(outcome.mutation());
        });
    }

    /** 在重复键事务回滚后从新事务读取已有流水。 */
    @Transactional(readOnly = true)
    public PointMutationResult recoverExistingMutation(DebitCommand command) {
        PointTransactionEntity transaction = findTransaction(command.idempotencyKey());
        if (transaction == null) {
            throw new BusinessException(PointMessage.IDEMPOTENCY_RECOVERY_FAILED_MESSAGE);
        }
        assertSameBusiness(transaction, command);
        return toMutation(transaction, true);
    }

    /** 在重复键事务回滚后从新事务读取已有赠送订单。 */
    @Transactional(readOnly = true)
    public GiftOrderResult recoverGiftOrders(List<GiftCommand> commands) {
        List<GiftOrderResult.GiftOrderItem> items = commands.stream()
                .map(command -> {
                    PointGiftOrderEntity order = findGiftOrder(command.idempotencyKey());
                    if (order == null) {
                        throw new BusinessException(PointMessage.IDEMPOTENCY_RECOVERY_FAILED_MESSAGE);
                    }
                    assertSameGiftBusiness(order, command);
                    return toGiftItem(order, true);
                })
                .toList();
        return new GiftOrderResult(items);
    }

    /** 在全部目标用户锁内创建赠送订单。 */
    private GiftOrderResult createGiftOrdersInsideLocks(List<GiftCommand> commands) {
        List<GiftOrderResult.GiftOrderItem> items = new ArrayList<>();
        List<GiftCommand> ordered = commands.stream()
                .sorted(Comparator.comparing(GiftCommand::userId).thenComparing(GiftCommand::idempotencyKey))
                .toList();
        for (GiftCommand command : ordered) {
            PointGiftOrderEntity existing = findGiftOrder(command.idempotencyKey());
            if (existing != null) {
                assertSameGiftBusiness(existing, command);
                items.add(toGiftItem(existing, true));
                continue;
            }
            PointAccountEntity account = ensureAccount(command.userId());
            PointGiftOrderEntity order = new PointGiftOrderEntity();
            order.setOrderNo(createGiftOrderNo(command.idempotencyKey()));
            order.setAccountId(account.getId());
            order.setUserId(command.userId());
            order.setSceneCode(command.sceneCode().strip());
            order.setAmount(command.amount());
            order.setBusinessType(command.businessType().strip());
            order.setBusinessId(command.businessId().strip());
            order.setBusinessSnapshot(command.businessSnapshot());
            order.setIdempotencyKey(command.idempotencyKey().strip());
            order.setStatus(PointGiftOrderStatusDict.READY.getCode());
            order.setRetryCount(0);
            order.setNextExecuteAt(LocalDateTime.now());
            pointGiftOrderEntityMapper.insert(order);
            items.add(toGiftItem(order, false));
        }
        return new GiftOrderResult(List.copyOf(items));
    }

    /** 在用户锁内执行一种扣除语义。 */
    private DebitOutcome deductInsideLock(DebitCommand command, DebitMode mode) {
        PointTransactionEntity existing = findTransaction(command.idempotencyKey());
        if (existing != null) {
            assertSameBusiness(existing, command);
            return DebitOutcome.mutated(toMutation(existing, true));
        }

        PointAccountEntity account = ensureAccount(command.userId());
        int updated = updateAccount(account, command, mode);
        if (updated != 1) {
            if (mode == DebitMode.MAINTAINER) {
                throw new InsufficientPointBalanceException(PointMessage.INSUFFICIENT_BALANCE_MESSAGE);
            }
            if (mode == DebitMode.SYSTEM) {
                return DebitOutcome.skippedOutcome();
            }
            throw new BusinessException(PointMessage.ACCOUNT_UPDATE_FAILED_MESSAGE);
        }

        PointAccountEntity updatedAccount = pointAccountEntityMapper.selectById(account.getId());
        if (updatedAccount == null) {
            throw new BusinessException(PointMessage.ACCOUNT_UPDATE_FAILED_MESSAGE);
        }
        long balanceAfter = value(updatedAccount.getBalance());
        long balanceBefore = Math.addExact(balanceAfter, command.points());
        PointTransactionEntity transaction = buildTransaction(command, updatedAccount, balanceBefore, balanceAfter);
        pointTransactionEntityMapper.insert(transaction);

        PointPendingDebitEntity pendingDebit = new PointPendingDebitEntity();
        pendingDebit.setAccountId(updatedAccount.getId());
        pendingDebit.setUserId(command.userId());
        pendingDebit.setPointTransactionId(transaction.getId());
        pendingDebit.setOriginalAmount(command.points());
        pendingDebit.setRemainingAmount(command.points());
        pointPendingDebitEntityMapper.insert(pendingDebit);
        pointDebitTaskService.ensureActiveTask(updatedAccount);
        return DebitOutcome.mutated(toMutation(transaction, false));
    }

    /** 执行对应语义的账户原子 SQL。 */
    private int updateAccount(PointAccountEntity account, DebitCommand command, DebitMode mode) {
        return switch (mode) {
            case MAINTAINER -> pointAccountEntityMapper.deductForMaintainer(
                    account.getId(), command.userId(), command.points());
            case VISITOR -> pointAccountEntityMapper.deductForVisitor(
                    account.getId(), command.userId(), command.points());
            case SYSTEM -> pointAccountEntityMapper.deductForSystem(
                    account.getId(), command.userId(), command.points());
        };
    }

    /** 校验启用用户并幂等创建零余额账户。 */
    private PointAccountEntity ensureAccount(Long userId) {
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        PointAccountEntity existing = pointAccountEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointAccountEntity.class)
                        .eq(PointAccountEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        PointAccountEntity account = new PointAccountEntity();
        account.setUserId(userId);
        account.setBalance(0L);
        account.setWechatBalance(0L);
        account.setWechatPresentBalance(0L);
        account.setPendingDebit(0L);
        account.setTotalRecharged(0L);
        account.setTotalGifted(0L);
        account.setTotalConsumed(0L);
        try {
            pointAccountEntityMapper.insert(account);
            return account;
        } catch (DuplicateKeyException exception) {
            PointAccountEntity concurrent = pointAccountEntityMapper.selectOne(
                    Wrappers.lambdaQuery(PointAccountEntity.class)
                            .eq(PointAccountEntity::getUserId, userId)
                            .last("LIMIT 1"));
            if (concurrent != null) {
                return concurrent;
            }
            throw exception;
        }
    }

    /** 构造不可变消费流水。 */
    private PointTransactionEntity buildTransaction(
            DebitCommand command,
            PointAccountEntity account,
            long balanceBefore,
            long balanceAfter
    ) {
        PointTransactionEntity transaction = new PointTransactionEntity();
        transaction.setAccountId(account.getId());
        transaction.setUserId(command.userId());
        transaction.setRuleId(command.ruleId());
        transaction.setTransactionType(PointTransactionTypeDict.CONSUMPTION.getCode());
        transaction.setSceneCode(command.sceneCode().strip());
        transaction.setPointsChange(Math.negateExact(command.points()));
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setBusinessType(command.businessType().strip());
        transaction.setBusinessId(command.businessId().strip());
        transaction.setCalculationSnapshot(defaultSnapshot(command.calculationSnapshot()));
        transaction.setIdempotencyKey(command.idempotencyKey().strip());
        transaction.setRemark(normalizeOptional(command.remark()));
        transaction.setOccurredAt(LocalDateTime.now());
        return transaction;
    }

    /** 查询幂等消费流水。 */
    private PointTransactionEntity findTransaction(String idempotencyKey) {
        return pointTransactionEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointTransactionEntity.class)
                        .eq(PointTransactionEntity::getIdempotencyKey, idempotencyKey.strip())
                        .last("LIMIT 1")
        );
    }

    /** 查询幂等赠送订单。 */
    private PointGiftOrderEntity findGiftOrder(String idempotencyKey) {
        return pointGiftOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointGiftOrderEntity.class)
                        .eq(PointGiftOrderEntity::getIdempotencyKey, idempotencyKey.strip())
                        .last("LIMIT 1")
        );
    }

    /** 校验幂等流水仍属于相同用户和业务。 */
    private void assertSameBusiness(PointTransactionEntity transaction, DebitCommand command) {
        if (!Objects.equals(transaction.getUserId(), command.userId())
                || !Objects.equals(transaction.getSceneCode(), command.sceneCode().strip())
                || !Objects.equals(transaction.getBusinessType(), command.businessType().strip())
                || !Objects.equals(transaction.getBusinessId(), command.businessId().strip())) {
            throw new BusinessException(PointMessage.IDEMPOTENCY_BUSINESS_CONFLICT_MESSAGE);
        }
    }

    /** 校验幂等赠送订单仍属于相同用户和业务。 */
    private void assertSameGiftBusiness(PointGiftOrderEntity order, GiftCommand command) {
        if (!Objects.equals(order.getUserId(), command.userId())
                || !Objects.equals(order.getSceneCode(), command.sceneCode().strip())
                || !Objects.equals(order.getBusinessType(), command.businessType().strip())
                || !Objects.equals(order.getBusinessId(), command.businessId().strip())
                || !Objects.equals(order.getAmount(), command.amount())) {
            throw new BusinessException(PointMessage.IDEMPOTENCY_BUSINESS_CONFLICT_MESSAGE);
        }
    }

    /** 将消费流水转换为统一结果。 */
    private PointMutationResult toMutation(PointTransactionEntity transaction, boolean idempotent) {
        return new PointMutationResult(
                transaction.getId(),
                transaction.getAccountId(),
                Math.abs(value(transaction.getPointsChange())),
                value(transaction.getBalanceBefore()),
                value(transaction.getBalanceAfter()),
                idempotent
        );
    }

    /** 将赠送订单转换为统一结果。 */
    private GiftOrderResult.GiftOrderItem toGiftItem(PointGiftOrderEntity order, boolean idempotent) {
        return new GiftOrderResult.GiftOrderItem(
                order.getId(), order.getOrderNo(), order.getUserId(), order.getStatus(), idempotent);
    }

    /** 按用户 ID 顺序递归取得全部锁，避免多用户赠送发生交叉加锁。 */
    private <T> T executeWithUserLocks(List<Long> userIds, int index, Supplier<T> action) {
        if (index >= userIds.size()) {
            return action.get();
        }
        return userPointMutex.execute(userIds.get(index),
                () -> executeWithUserLocks(userIds, index + 1, action));
    }

    /** 从业务幂等键生成稳定、合法的微信赠送订单号。 */
    private String createGiftOrderNo(String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hexadecimal = HexFormat.of().withUpperCase().formatHex(
                    digest.digest(idempotencyKey.strip().getBytes(StandardCharsets.UTF_8)));
            return GIFT_ORDER_NO_PREFIX + hexadecimal.substring(0, GIFT_ORDER_DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    /** 空计算快照使用最小合法 JSON 对象。 */
    private String defaultSnapshot(String snapshot) {
        return snapshot == null || snapshot.isBlank() ? "{}" : snapshot;
    }

    /** 规范化可空备注。 */
    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 将可空长整数转换为零兜底值。 */
    private long value(Long number) {
        return number == null ? 0L : number;
    }

    /** 三类本地扣除语义。 */
    private enum DebitMode {
        MAINTAINER,
        VISITOR,
        SYSTEM
    }

    /** 内部扣除结果，区分后台余额非正跳过与实际变更。 */
    private record DebitOutcome(boolean skipped, PointMutationResult mutation) {

        /** 创建实际变更结果。 */
        private static DebitOutcome mutated(PointMutationResult mutation) {
            return new DebitOutcome(false, mutation);
        }

        /** 创建后台余额非正跳过结果。 */
        private static DebitOutcome skippedOutcome() {
            return new DebitOutcome(true, null);
        }
    }
}
