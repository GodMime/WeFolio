package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.PaymentChannelDict;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.MerchantOrderNoGenerator;
import com.jxc.wefolio.service.PointService;
import com.jxc.wefolio.service.point.GiftCommand;
import com.jxc.wefolio.service.point.GiftOrderResult;
import com.jxc.wefolio.service.point.PointCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 充值订单事务服务 — 负责本地建单和支付成功后的原子结算。
 */
@Service
@RequiredArgsConstructor
public class RechargeOrderTransactionService {

    /** 商户订单号最大插入尝试次数。 */
    private static final int MAX_ORDER_NO_ATTEMPTS = 3;

    /** 商户订单号唯一索引名称。 */
    private static final String MERCHANT_ORDER_UNIQUE_INDEX = "uk_recharge_merchant_order";

    /** 积分流水幂等键前缀。 */
    private static final String POINT_IDEMPOTENCY_PREFIX = "POINT_RECHARGE:";

    /** 充值订单 Mapper。 */
    private final RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 积分服务。 */
    private final PointService pointService;

    /** 商户订单号生成器。 */
    private final MerchantOrderNoGenerator merchantOrderNoGenerator;

    /** 统一积分命令入口，用于创建充值赠送订单。 */
    private final PointCommandService pointCommandService;

    /** 充值成功后恢复剩余待扣的活动任务。 */
    private final PointDebitTaskService pointDebitTaskService;

    /**
     * 创建待支付本地订单，商户订单号唯一冲突时最多重试三次。
     *
     * @param userId 用户 ID
     * @param account 积分账户
     * @param rechargePackage 充值套餐
     * @param expireAt 过期时间
     * @return 已持久化订单
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrderEntity createPendingOrder(
            Long userId,
            PointAccountEntity account,
            RechargePackageEntity rechargePackage,
            LocalDateTime expireAt
    ) {
        for (int attempt = 1; attempt <= MAX_ORDER_NO_ATTEMPTS; attempt++) {
            RechargeOrderEntity order = buildPendingOrder(
                    userId, account, rechargePackage, expireAt, merchantOrderNoGenerator.generate());
            try {
                rechargeOrderEntityMapper.insert(order);
                return order;
            } catch (DuplicateKeyException exception) {
                if (!isMerchantOrderConflict(exception)) {
                    throw exception;
                }
            }
        }
        throw new BusinessException(RechargeMessage.ORDER_CREATE_FAILED_MESSAGE);
    }

    /**
     * 根据微信主动查单结果写入失败或关闭终态，不覆盖已支付订单。
     *
     * @param userId 当前用户 ID
     * @param merchantOrderNo 商户订单号
     * @param targetStatus 目标终态
     * @return 更新后的订单
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrderEntity markTerminalState(
            Long userId,
            String merchantOrderNo,
            RechargeOrderStatusDict targetStatus
    ) {
        RechargeOrderEntity order = requireLockedOrder(merchantOrderNo);
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(RechargeMessage.ORDER_NOT_FOUND_MESSAGE);
        }
        if (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())) {
            return order;
        }
        order.setStatus(targetStatus.getCode());
        if (targetStatus == RechargeOrderStatusDict.CLOSED) {
            order.setClosedAt(LocalDateTime.now());
        }
        updateOrder(order);
        return order;
    }

    /**
     * 独立保存已经核实的微信收款事实；余额补查失败不能撤销事实，也不能提前视为本地入账。
     * 调用方已校验微信身份并持有用户锁；订单锁与金额校验防止错误订单获得关单豁免。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordPaymentConfirmed(String merchantOrderNo, WechatVirtualPaymentResult result) {
        RechargeOrderEntity order = requireLockedOrder(merchantOrderNo);
        if (result == null || !result.isSuccessful() || result.payAmount() <= 0L
                || order.getAmountFen() == null || result.payAmount() != order.getAmountFen()
                || (result.buyQuantity() > 0L && !Objects.equals(order.getBuyQuantity(), result.buyQuantity()))) {
            throw new BusinessException(RechargeMessage.AMOUNT_MISMATCH_MESSAGE);
        }
        if (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())
                || RechargeOrderStatusDict.REFUNDED.getCode().equals(order.getStatus())) {
            return;
        }
        order.setPaidFee(result.payAmount());
        if (order.getPaidAt() == null) {
            order.setPaidAt(LocalDateTime.now());
        }
        order.setWechatOrderId(result.remoteOrderId() == null || result.remoteOrderId().isBlank()
                ? merchantOrderNo : result.remoteOrderId());
        updateOrder(order);
    }

    /** 独立提交核对进度，让调度器轮转到其他订单；不改变充值业务状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordQuerySchedule(Long orderId, LocalDateTime nextQueryAt, int retryCount, String errorCode) {
        if (rechargeOrderEntityMapper.updateQuerySchedule(orderId, nextQueryAt, retryCount, errorCode) != 1) {
            throw new BusinessException(RechargeMessage.ORDER_UPDATE_FAILED_MESSAGE);
        }
    }

    /** 根据微信虚拟支付权威查单结果完成基础代币充值和套餐赠送。 */
    @Transactional(rollbackFor = Exception.class)
    public RechargeSettlementResult settleVirtual(
            String merchantOrderNo,
            WechatVirtualPaymentResult result,
            WechatVirtualPaymentResult balance
    ) {
        if (result == null || !result.isSuccessful()
                || balance == null || balance.errorType() != WechatVirtualPaymentErrorType.SUCCESS) {
            throw new BusinessException(RechargeMessage.TRADE_STATE_INVALID_MESSAGE);
        }
        RechargeOrderEntity order = requireLockedOrder(merchantOrderNo);
        if (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())) {
            return new RechargeSettlementResult(order, null, true);
        }
        if (RechargeOrderStatusDict.REFUNDED.getCode().equals(order.getStatus())) {
            throw new BusinessException(RechargeMessage.TRADE_STATE_INVALID_MESSAGE);
        }
        if (result.buyQuantity() > 0L && !Objects.equals(order.getBuyQuantity(), result.buyQuantity())) {
            throw new BusinessException(RechargeMessage.AMOUNT_MISMATCH_MESSAGE);
        }
        if (result.payAmount() > 0L && result.payAmount() != order.getAmountFen()) {
            throw new BusinessException(RechargeMessage.AMOUNT_MISMATCH_MESSAGE);
        }
        String packageName = JSONObject.parseObject(order.getPackageSnapshot()).getString("packageName");
        PointMutationResponse mutation = pointService.rechargeVirtual(
                order.getUserId(),
                order.getBasePoints().longValue(),
                order.getMerchantOrderNo(),
                order.getPackageSnapshot(),
                POINT_IDEMPOTENCY_PREFIX + order.getMerchantOrderNo(),
                packageName,
                balance.balance(),
                balance.presentBalance()
        );
        order.setPointTransactionId(mutation.getTransactionId());
        if (order.getBonusPoints() != null && order.getBonusPoints() > 0 && order.getBonusGiftOrderId() == null) {
            GiftOrderResult gifts = pointCommandService.createGiftOrderWithinUserLock(new GiftCommand(
                    order.getUserId(),
                    PointSceneCodeDict.RECHARGE_BONUS_GIFT.getCode(),
                    order.getBonusPoints(),
                    "RECHARGE_BONUS",
                    order.getMerchantOrderNo(),
                    order.getPackageSnapshot(),
                    "RECHARGE_BONUS:" + order.getMerchantOrderNo()
            ));
            order.setBonusGiftOrderId(gifts.orders().getFirst().orderId());
        }
        order.setStatus(RechargeOrderStatusDict.PAID.getCode());
        order.setWechatOrderId(result.remoteOrderId() == null || result.remoteOrderId().isBlank()
                ? order.getMerchantOrderNo()
                : result.remoteOrderId());
        order.setPaidFee(result.payAmount());
        if (order.getPaidAt() == null) {
            order.setPaidAt(LocalDateTime.now());
        }
        order.setClosedAt(null);
        updateOrder(order);
        pointDebitTaskService.ensureActiveTask(order.getUserId());
        return new RechargeSettlementResult(order, mutation.getBalanceAfter(), false);
    }

    /**
     * 锁定并返回订单。
     */
    private RechargeOrderEntity requireLockedOrder(String merchantOrderNo) {
        RechargeOrderEntity order = rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(merchantOrderNo);
        if (order == null) {
            throw new BusinessException(RechargeMessage.ORDER_NOT_FOUND_MESSAGE);
        }
        return order;
    }

    /**
     * 更新订单并校验更新行数。
     */
    private void updateOrder(RechargeOrderEntity order) {
        int updated = rechargeOrderEntityMapper.updateById(order);
        if (updated != 1) {
            throw new BusinessException(RechargeMessage.ORDER_UPDATE_FAILED_MESSAGE);
        }
    }

    /**
     * 构造待支付订单。
     */
    private RechargeOrderEntity buildPendingOrder(
            Long userId,
            PointAccountEntity account,
            RechargePackageEntity rechargePackage,
            LocalDateTime expireAt,
            String merchantOrderNo
    ) {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setMerchantOrderNo(merchantOrderNo);
        order.setAccountId(account.getId());
        order.setUserId(userId);
        order.setPackageId(rechargePackage.getId());
        order.setPackageSnapshot(buildPackageSnapshot(rechargePackage));
        order.setAmountFen(rechargePackage.getAmountFen());
        order.setBasePoints(rechargePackage.getBasePoints());
        order.setBonusPoints(rechargePackage.getBonusPoints());
        order.setTotalPoints(rechargePackage.getTotalPoints());
        order.setBuyQuantity(rechargePackage.getBasePoints().longValue());
        order.setStatus(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
        order.setPayChannel(PaymentChannelDict.WECHAT_VIRTUAL_PAYMENT.getCode());
        order.setExpireAt(expireAt);
        return order;
    }

    /**
     * 构造不可变套餐快照。
     */
    private String buildPackageSnapshot(RechargePackageEntity rechargePackage) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("packageCode", rechargePackage.getPackageCode());
        snapshot.put("packageVersion", rechargePackage.getPackageVersion());
        snapshot.put("packageName", rechargePackage.getPackageName());
        snapshot.put("amountFen", rechargePackage.getAmountFen());
        snapshot.put("basePoints", rechargePackage.getBasePoints());
        snapshot.put("bonusPoints", rechargePackage.getBonusPoints());
        snapshot.put("totalPoints", rechargePackage.getTotalPoints());
        return JSON.toJSONString(snapshot);
    }

    /**
     * 判断数据库异常是否来自商户订单号唯一索引。
     */
    private boolean isMerchantOrderConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(MERCHANT_ORDER_UNIQUE_INDEX)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
