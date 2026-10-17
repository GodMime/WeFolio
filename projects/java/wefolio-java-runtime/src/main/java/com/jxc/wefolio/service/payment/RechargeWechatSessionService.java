package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.message.MiniappAuthMessage;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.TrustedClientIpResolver;
import com.jxc.wefolio.service.WechatMiniappClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 充值设备会话 — 校验当前 code 的身份并将本次会话直接交给签名流程。 */
@Service
@RequiredArgsConstructor
public class RechargeWechatSessionService {

    /** 微信临时登录凭证交换边界。 */
    private final WechatMiniappClient wechatMiniappClient;

    /** 当前用户唯一微信认证查询边界。 */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /** 保存会话以供后续查单、余额同步及后台结算使用。 */
    private final MaintainerWechatSessionService sessionService;

    /** 仅使用服务端可信代理解析后的来源地址。 */
    private final TrustedClientIpResolver clientIpResolver;

    /** 校验设备身份、保存会话，并返回本次 code 交换得到的密钥而非共享记录的回读值。 */
    public String refreshForPayment(Long userId, String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(MiniappAuthMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        }
        UserAuthEntity auth = WechatPaymentIdentity.findUnique(userAuthEntityMapper, userId);
        if (auth == null) {
            throw new BusinessException(MiniappAuthMessage.WECHAT_ACCOUNT_INVALID_MESSAGE);
        }
        String clientIp = clientIpResolver.resolveCurrentRequest();
        if (clientIp == null || clientIp.isBlank()) {
            throw new BusinessException(RechargeMessage.PAYMENT_CLIENT_IP_REQUIRED_MESSAGE);
        }
        WechatSessionResponse session = wechatMiniappClient.exchangeCode(code);
        if (session == null || (session.getErrcode() != null && session.getErrcode() != 0)
                || session.getSessionKey() == null || session.getSessionKey().isBlank()) {
            throw new BusinessException(RechargeMessage.PAYMENT_SESSION_UNAVAILABLE_MESSAGE);
        }
        if (!auth.getOpenId().equals(session.getOpenid())) {
            throw new BusinessException(RechargeMessage.PAYMENT_IDENTITY_MISMATCH_MESSAGE);
        }
        String paymentSessionKey = session.getSessionKey();
        sessionService.saveAvailableSession(userId, auth.getId(), paymentSessionKey, clientIp);
        // 其他终端可以覆盖共享会话，但不能改变已经为本次支付取到的密钥。
        return paymentSessionKey;
    }
}
