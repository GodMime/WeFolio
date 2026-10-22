package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatMobileProperties;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.MobileWechatLoginRequest;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.WechatMobileMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** 安卓已有账号登录 — 按可信 UnionID 查找唯一正常账号，沿用既有维护者令牌与权限。 */
@Service
@RequiredArgsConstructor
public class MobileWechatAuthService {

    /** 必须与现有小程序身份摘要算法保持一致。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 限制匹配数量，仅需判断身份是否唯一。 */
    private static final String IDENTITY_QUERY_LIMIT = "LIMIT 2";
    /** 移动应用开关与凭证配置。 */
    private final WechatMobileProperties properties;
    /** 现有账号摘要使用小程序配置中的摘要密钥。 */
    private final WechatMiniappProperties miniappProperties;
    /** 官方授权校验客户端。 */
    private final WechatMobileClient mobileClient;
    /** 独立关联接口使用官方小程序凭证，原小程序登录流程不变。 */
    private final WechatMiniappClient miniappClient;
    /** 既有微信身份记录。 */
    private final UserAuthEntityMapper authMapper;
    /** 用户状态查询。 */
    private final UserEntityMapper userMapper;
    /** 复用既有维护者令牌签发。 */
    private final MobileMaintainerTokenService tokenService;

    /** 返回客户端可公开读取的开关，不暴露任何密钥。 */
    public Capabilities capabilities() {
        return new Capabilities(properties.isConfigured(), false, false);
    }

    /** 验证官方身份后关联现有账号；不自动注册、不猜测或合并账号。 */
    public MaintainerWechatLoginResponse login(MobileWechatLoginRequest request) {
        if (!properties.isConfigured()) throw new BusinessException(WechatMobileMessage.NOT_ENABLED);
        if (request == null || request.code() == null || request.code().isBlank() || request.code().length() > 512) {
            throw new BusinessException(WechatMobileMessage.CODE_REQUIRED);
        }
        String unionId = mobileClient.exchangeUnionId(request.code());
        if (unionId == null || unionId.isBlank()) throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        List<UserAuthEntity> matches = authMapper.selectList(Wrappers.lambdaQuery(UserAuthEntity.class)
                .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                .eq(UserAuthEntity::getUnionIdentifierHash, digest(unionId))
                .last(IDENTITY_QUERY_LIMIT));
        if (matches.isEmpty()) throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        if (matches.size() != 1) throw new BusinessException(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        UserAuthEntity auth = matches.getFirst();
        if (!UserStatusDict.ACTIVE.getCode().equals(auth.getStatus())) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_INACTIVE);
        }
        UserEntity user = userMapper.selectById(auth.getUserId());
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_INACTIVE);
        }
        return tokenService.issue(user.getId());
    }

    /**
     * 为已登录小程序账号补齐跨端摘要；必须同时证明当前账号及同一微信身份。
     * 仅由新增关联接口调用，不改变原登录接口，也不允许自动创建或合并账号。
     */
    public void linkMiniapp(Long userId, MobileWechatLoginRequest request) {
        if (!properties.isConfigured()) throw new BusinessException(WechatMobileMessage.NOT_ENABLED);
        if (request == null || request.code() == null || request.code().isBlank() || request.code().length() > 512) {
            throw new BusinessException(WechatMobileMessage.CODE_REQUIRED);
        }
        UserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_INACTIVE);
        }
        var session = miniappClient.exchangeCode(request.code());
        if (session == null || session.getOpenid() == null || session.getOpenid().isBlank()
                || session.getUnionid() == null || session.getUnionid().isBlank()) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        }
        List<UserAuthEntity> matches = authMapper.selectList(Wrappers.lambdaQuery(UserAuthEntity.class)
                .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                .eq(UserAuthEntity::getUserId, userId).last(IDENTITY_QUERY_LIMIT));
        if (matches.size() != 1) throw new BusinessException(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        UserAuthEntity auth = matches.getFirst();
        boolean sameIdentity = auth.getOpenId() == null || auth.getOpenId().isBlank()
                ? digest(session.getOpenid()).equals(auth.getIdentifierHash())
                : session.getOpenid().equals(auth.getOpenId());
        if (!sameIdentity || !UserStatusDict.ACTIVE.getCode().equals(auth.getStatus())) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        }
        String unionHash = digest(session.getUnionid());
        List<UserAuthEntity> conflicts = authMapper.selectList(Wrappers.lambdaQuery(UserAuthEntity.class)
                .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode())
                .eq(UserAuthEntity::getUnionIdentifierHash, unionHash)
                .ne(UserAuthEntity::getUserId, userId).last(IDENTITY_QUERY_LIMIT));
        if (!conflicts.isEmpty()) throw new BusinessException(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        auth.setUnionIdentifierHash(unionHash);
        if (authMapper.updateById(auth) != 1) throw new BusinessException(WechatMobileMessage.IDENTITY_FAILED);
    }

    /** 使用既有摘要密钥查询身份；移动应用 AppSecret 仅用于 OAuth 兑换。 */
    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(miniappProperties.getAppSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException(WechatMobileMessage.IDENTITY_FAILED);
        }
    }

    /** 公开能力：已有账号登录、新用户注册、安卓支付。 */
    public record Capabilities(boolean enabled, boolean registrationEnabled, boolean paymentsEnabled) {
    }
}
