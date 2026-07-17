package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatPayProperties;
import com.jxc.wefolio.dict.PaymentChannelDict;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.MerchantOrderNoGenerator;
import com.jxc.wefolio.service.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 人民币币种编码。 */
    private static final String CURRENCY_CNY = "CNY";

    /** 充值订单 Mapper。 */
    private final RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 积分服务。 */
    private final PointService pointService;

    /** 商户订单号生成器。 */
    private final MerchantOrderNoGenerator merchantOrderNoGenerator;

    /** 微信支付配置。 */
    private final WechatPayProperties payProperties;

    /** 微信小程序配置。 */
    private final WechatMiniappProperties miniappProperties;

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
     * 保存预支付标识，仅允许更新仍在待支付的订单。
     *
     * @param merchantOrderNo 商户订单号
     * @param prepayId 微信预支付标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void markPrepayReady(String merchantOrderNo, String prepayId) {
        RechargeOrderEntity order = requireLockedOrder(merchantOrderNo);
        if (!RechargeOrderStatusDict.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            return;
        }
        order.setPrepayId(prepayId);
        updateOrder(order);
    }

    /**
     * 将微信预下单失败的待支付订单标记为支付失败。
     *
     * @param merchantOrderNo 商户订单号
     */
    @Transactional(rollbackFor = Exception.class)
    public void markPrepayFailed(String merchantOrderNo) {
        RechargeOrderEntity order = requireLockedOrder(merchantOrderNo);
        if (!RechargeOrderStatusDict.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            return;
        }
        order.setStatus(RechargeOrderStatusDict.PAYMENT_FAILED.getCode());
        updateOrder(order);
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
     * 结算已由微信支付核验为成功的交易。
     *
     * @param transaction 标准化微信交易
     * @return 结算结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeSettlementResult settle(WechatPayClient.Transaction transaction) {
        requireSuccessfulTransaction(transaction);
        RechargeOrderEntity order = rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(
                transaction.merchantOrderNo());
        if (order == null) {
            throw new BusinessException(RechargeMessage.ORDER_NOT_FOUND_MESSAGE);
        }
        validateTransaction(order, transaction);
        if (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())) {
            if (!Objects.equals(order.getPaymentTransactionId(), transaction.transactionId())) {
                throw new BusinessException(RechargeMessage.TRANSACTION_ID_CONFLICT_MESSAGE);
            }
            return new RechargeSettlementResult(order, null, true);
        }

        String packageName = JSONObject.parseObject(order.getPackageSnapshot()).getString("packageName");
        PointMutationResponse mutation = pointService.recharge(
                order.getUserId(),
                order.getTotalPoints().longValue(),
                order.getMerchantOrderNo(),
                order.getPackageSnapshot(),
                POINT_IDEMPOTENCY_PREFIX + order.getMerchantOrderNo(),
                packageName
        );
        order.setStatus(RechargeOrderStatusDict.PAID.getCode());
        order.setPaymentTransactionId(transaction.transactionId());
        order.setPaidAt(transaction.successTime() == null ? LocalDateTime.now() : transaction.successTime());
        order.setClosedAt(null);
        updateOrder(order);
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
        order.setStatus(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
        order.setPayChannel(PaymentChannelDict.WECHAT_PAY.getCode());
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
     * 校验交易成功状态和必要字段。
     */
    private void requireSuccessfulTransaction(WechatPayClient.Transaction transaction) {
        if (transaction == null || transaction.tradeState() != WechatPayClient.TradeState.SUCCESS) {
            throw new BusinessException(RechargeMessage.TRADE_STATE_INVALID_MESSAGE);
        }
        if (transaction.transactionId() == null || transaction.transactionId().isBlank()) {
            throw new BusinessException(RechargeMessage.TRANSACTION_ID_MISSING_MESSAGE);
        }
    }

    /**
     * 核对交易与本地订单身份及金额。
     */
    private void validateTransaction(
            RechargeOrderEntity order,
            WechatPayClient.Transaction transaction
    ) {
        if (!Objects.equals(miniappProperties.getAppId(), transaction.appId())) {
            throw new BusinessException(RechargeMessage.APP_ID_MISMATCH_MESSAGE);
        }
        if (!Objects.equals(payProperties.getMerchantId(), transaction.merchantId())) {
            throw new BusinessException(RechargeMessage.MERCHANT_ID_MISMATCH_MESSAGE);
        }
        if (!CURRENCY_CNY.equals(transaction.currency())) {
            throw new BusinessException(RechargeMessage.CURRENCY_MISMATCH_MESSAGE);
        }
        if (!Objects.equals(order.getAmountFen(), transaction.amountFen())) {
            throw new BusinessException(RechargeMessage.AMOUNT_MISMATCH_MESSAGE);
        }
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
