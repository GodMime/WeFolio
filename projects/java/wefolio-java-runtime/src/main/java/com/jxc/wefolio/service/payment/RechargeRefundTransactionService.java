package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.service.point.UserPointMutex;
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

    /** 标记退款与所有权威余额查询使用同一用户锁，避免陈旧查询清除新退款标记。 */
    private final UserPointMutex userPointMutex;

    /** 幂等标记退款完成，并将账户权威余额快照置为待同步。 */
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrderEntity markRefunded(String merchantOrderNo) {
        RechargeOrderEntity candidate = rechargeOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(RechargeOrderEntity.class)
                        .eq(RechargeOrderEntity::getMerchantOrderNo, merchantOrderNo).last("LIMIT 1"));
        if (candidate == null) {
            return null;
        }
        return userPointMutex.execute(candidate.getUserId(), () -> markRefundedInsideUserLock(merchantOrderNo));
    }

    /** 后台权威核对已持有用户锁，只取得订单行锁并独立提交退款事实。 */
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrderEntity markRefundedWithinUserLock(String merchantOrderNo) {
        return markRefundedInsideUserLock(merchantOrderNo);
    }

    /** 固定用户锁在前、订单行锁在后；锁随退款事务提交释放。 */
    private RechargeOrderEntity markRefundedInsideUserLock(String merchantOrderNo) {
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
