package com.jxc.wefolio.service.payment;

/**
 * 维护者微信会话可用事件。
 *
 * @param userId 维护者用户 ID
 */
public record MaintainerWechatSessionAvailableEvent(Long userId) {
}
