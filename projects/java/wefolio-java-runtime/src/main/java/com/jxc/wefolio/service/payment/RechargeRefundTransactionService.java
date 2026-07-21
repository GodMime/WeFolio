package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 虚拟支付充值退款本地状态事务服务。 */
@Service
@RequiredArgsConstructor
public class RechargeRefundTransactionService {

    private final RechargeOrderEntityMapper rechargeOrderEntityMapper;
    private final PointAccountEntityMapper pointAccountEntityMapper;

    /** 幂等标记退款完成，并将账户权威余额快照置为待同步。 */
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrderEntity markRefunded(String merchantOrderNo) {
        RechargeOrderEntity order = rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(merchantOrderNo);
        if (order == null) {
            return null;
        }
        if (!RechargeOrderStatusDict.REFUNDED.getCode().equals(order.getStatus())) {
            order.setStatus(RechargeOrderStatusDict.REFUNDED.getCode());
            order.setRefundedAt(LocalDateTime.now());
            if (rechargeOrderEntityMapper.updateById(order) != 1) {
                throw new IllegalStateException("充值订单退款状态更新失败");
            }
        }
        if (pointAccountEntityMapper.markWechatBalanceStale(order.getAccountId()) != 1) {
            throw new IllegalStateException("退款后积分账户同步标记更新失败");
        }
        return order;
    }
}
