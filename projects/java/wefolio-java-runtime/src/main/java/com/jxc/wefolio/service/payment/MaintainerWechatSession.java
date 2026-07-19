package com.jxc.wefolio.service.payment;

/**
 * 可用维护者微信会话只读视图。
 *
 * @param userId 维护者用户 ID
 * @param authId 微信认证记录 ID
 * @param sessionKey 解密后的微信会话密钥
 * @param sessionVersion 会话版本
 * @param clientIp 微信余额查询使用的可信客户端 IP
 */
public record MaintainerWechatSession(
        Long userId,
        Long authId,
        String sessionKey,
        Long sessionVersion,
        String clientIp
) {
}
