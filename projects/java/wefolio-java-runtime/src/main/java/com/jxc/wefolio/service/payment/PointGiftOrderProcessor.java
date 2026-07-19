package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.point.UserPointMutex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 单条微信代币赠送订单处理器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointGiftOrderProcessor {

    private static final String ACTIVE_AUTH_STATUS = "ACTIVE";

    private final PointGiftOrderTransactionService transactionService;
    private final WechatVirtualPaymentClient wechatVirtualPaymentClient;
    private final UserAuthEntityMapper userAuthEntityMapper;
    private final UserPointMutex userPointMutex;

    /** 领取并独立处理一条赠送订单。 */
    public void process(Long orderId, String leaseOwner) {
        PointGiftOrderEntity order = transactionService.tryClaim(orderId, leaseOwner);
        if (order == null) {
            return;
        }
        log.info("微信虚拟支付业务开始 operation=处理赠送订单 referenceNo={} userId={} amount={}",
                order.getOrderNo(), order.getUserId(), order.getAmount());
        userPointMutex.execute(order.getUserId(), () -> {
            processClaimed(order, leaseOwner);
            return null;
        });
    }

    /** 在用户锁内访问微信并落库。 */
    private void processClaimed(PointGiftOrderEntity order, String leaseOwner) {
        String openid = findWechatOpenid(order.getUserId());
        if (openid == null || openid.isBlank()) {
            transactionService.markFailure(order.getId(), leaseOwner,
                    failure(WechatVirtualPaymentErrorType.PERMANENT, "用户缺少有效微信身份"));
            log.info("微信虚拟支付业务完成 operation=处理赠送订单 referenceNo={} userId={} "
                            + "localStatus=FAILED reason=MISSING_WECHAT_IDENTITY",
                    order.getOrderNo(), order.getUserId());
            return;
        }
        WechatVirtualPaymentResult result;
        try {
            result = wechatVirtualPaymentClient.presentCurrency(new WechatPresentCurrencyRequest(
                    order.getUserId(),
                    order.getOrderNo(),
                    openid,
                    order.getOrderNo(),
                    order.getAmount(),
                    Instant.now().getEpochSecond()
            ));
        } catch (RuntimeException exception) {
            log.warn("微信虚拟支付业务异常 operation=处理赠送订单 referenceNo={} userId={} "
                            + "exceptionType={}",
                    order.getOrderNo(), order.getUserId(), exception.getClass().getSimpleName());
            result = failure(WechatVirtualPaymentErrorType.TRANSIENT, "微信赠送调用异常");
        }
        log.info("微信虚拟支付业务微信结果 operation=处理赠送订单 referenceNo={} userId={} "
                        + "errcode={} errorType={} balance={} presentBalance={}",
                order.getOrderNo(), order.getUserId(), result.errorCode(), result.errorType(),
                result.balance(), result.presentBalance());
        if (result.errorType() == WechatVirtualPaymentErrorType.SUCCESS
                || result.errorType() == WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS) {
            transactionService.completeSuccess(order.getId(), leaseOwner, result);
            log.info("微信虚拟支付业务完成 operation=处理赠送订单 referenceNo={} userId={} "
                            + "localStatus=SUCCESS balance={} presentBalance={}",
                    order.getOrderNo(), order.getUserId(), result.balance(), result.presentBalance());
        } else {
            transactionService.markFailure(order.getId(), leaseOwner, result);
            log.info("微信虚拟支付业务完成 operation=处理赠送订单 referenceNo={} userId={} "
                            + "localStatus=FAILED errorType={}",
                    order.getOrderNo(), order.getUserId(), result.errorType());
        }
    }

    /** 查询用户当前有效微信 openid。 */
    private String findWechatOpenid(Long userId) {
        UserAuthEntity auth = userAuthEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserAuthEntity.class)
                        .eq(UserAuthEntity::getUserId, userId)
                        .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                        .eq(UserAuthEntity::getStatus, ACTIVE_AUTH_STATUS)
                        .isNotNull(UserAuthEntity::getOpenId)
                        .last("LIMIT 1"));
        return auth == null ? null : auth.getOpenId();
    }

    /** 构造本地失败结果。 */
    private WechatVirtualPaymentResult failure(
            WechatVirtualPaymentErrorType errorType,
            String message
    ) {
        return new WechatVirtualPaymentResult(null, message, errorType,
                0L, 0L, 0L, null, 0L, 0L, 0);
    }
}
