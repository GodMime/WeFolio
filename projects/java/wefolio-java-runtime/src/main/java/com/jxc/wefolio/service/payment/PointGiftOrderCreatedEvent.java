package com.jxc.wefolio.service.payment;

import java.util.List;

/**
 * 来源业务事务内新建赠送订单事件。
 *
 * @param orderIds 新建赠送订单 ID
 */
public record PointGiftOrderCreatedEvent(List<Long> orderIds) {
}
