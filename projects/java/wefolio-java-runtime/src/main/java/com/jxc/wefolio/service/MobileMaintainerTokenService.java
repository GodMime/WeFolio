package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.WechatMobileMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** 安卓令牌签发适配 — 复用现有加密服务及维护者令牌协议，不修改小程序登录服务。 */
@Service
@RequiredArgsConstructor
public class MobileMaintainerTokenService {
    /** 与现有维护者解析器保持一致的协议前缀。 */
    private static final String TOKEN_PREFIX = "wf-maintainer-v1.";
    /** 现有维护者载荷分隔符。 */
    private static final String PAYLOAD_SEPARATOR = ":";
    /** 现有维护者授权类型。 */
    private static final String TOKEN_TYPE = "Bearer";
    /** 与现有解析器一致的三十天有效期，由兼容性测试验证。 */
    private static final long EXPIRES_IN_SECONDS = 30L * 24L * 60L * 60L;
    /** 复用服务端既有加密算法和配置。 */
    private final EncryptedAuthTokenService encryptedAuthTokenService;

    /** 仅供移动应用服务在官方身份及账号状态校验完成后调用。 */
    public MaintainerWechatLoginResponse issue(Long userId) {
        if (userId == null || userId <= 0) throw new BusinessException(WechatMobileMessage.ACCOUNT_INACTIVE);
        String payload = userId + PAYLOAD_SEPARATOR + Instant.now().getEpochSecond();
        MaintainerWechatLoginResponse response = new MaintainerWechatLoginResponse();
        response.setToken(encryptedAuthTokenService.encryptPayload(TOKEN_PREFIX, payload));
        response.setTokenType(TOKEN_TYPE);
        response.setUserId(userId);
        response.setExpiresInSeconds(EXPIRES_IN_SECONDS);
        return response;
    }
}
