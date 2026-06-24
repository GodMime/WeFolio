package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.AuthSessionResponse;
import com.jxc.wefolio.dto.WechatLoginRequest;
import com.jxc.wefolio.dto.WechatLoginResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 小程序登录服务 — 提供一期微信授权登录的后端入口
 */
@Service
@RequiredArgsConstructor
public class MiniappAuthService {

    /** 简单令牌前缀，后续可替换为 JWT 或服务端会话 */
    private static final String DEVELOPMENT_TOKEN_PREFIX = "wf-dev-user-";

    /** 令牌类型 */
    private static final String TOKEN_TYPE = "Bearer";

    /** 简单令牌有效期 */
    private static final long DEVELOPMENT_EXPIRES_IN_SECONDS = 30L * 24L * 60L * 60L;

    /** 微信小程序登录类型 */
    private static final String WECHAT_AUTH_TYPE = "WECHAT_MINI_APP";

    /** HMAC 算法 */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 用户登录身份 Mapper */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /** 微信小程序客户端 */
    private final WechatMiniappClient wechatMiniappClient;

    /** 微信小程序配置 */
    private final WechatMiniappProperties wechatMiniappProperties;

    /**
     * 解析 bearer token 中的用户 ID
     *
     * @param authorizationHeader Authorization 请求头
     * @return 用户 ID，无法解析时返回空
     */
    public Long resolveUserId(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.regionMatches(true, 0, TOKEN_TYPE + " ", 0, TOKEN_TYPE.length() + 1)) {
            value = value.substring(TOKEN_TYPE.length() + 1).trim();
        }
        if (!value.startsWith(DEVELOPMENT_TOKEN_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(value.substring(DEVELOPMENT_TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析并校验当前登录用户
     *
     * @param authorizationHeader Authorization 请求头
     * @return 有效用户 ID，无效时为空
     */
    public Long resolveAuthenticatedUserId(String authorizationHeader) {
        Long userId = resolveUserId(authorizationHeader);
        if (userId == null) {
            return null;
        }
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return null;
        }
        return userId;
    }

    /**
     * 构建登录态响应
     *
     * @param userId 当前登录用户 ID
     * @return 登录态响应
     */
    public AuthSessionResponse buildSession(Long userId) {
        AuthSessionResponse response = new AuthSessionResponse();
        response.setAuthenticated(userId != null);
        response.setUserId(userId);
        return response;
    }

    /**
     * 微信授权登录
     *
     * @param request 微信登录请求
     * @return 登录响应
     */
    public WechatLoginResponse loginByWechat(WechatLoginRequest request) {
        if (request == null || request.getCode() == null || request.getCode().isBlank()) {
            throw new BusinessException("微信登录凭证不能为空");
        }

        WechatSessionResponse session = wechatMiniappClient.exchangeCode(request.getCode());
        String openidHash = digestIdentifier(session.getOpenid());
        UserAuthEntity auth = findActiveAuth(WECHAT_AUTH_TYPE, openidHash);

        UserEntity user;
        if (auth == null) {
            WechatPhoneNumberResponse.PhoneInfo phoneInfo =
                    wechatMiniappClient.exchangePhoneCode(requirePhoneCode(request));
            String openpid = exchangePluginOpenpidIfPresent(request.getPluginLoginCode());
            user = findActiveUserByPhone(phoneInfo.getPhoneNumber());
            if (user == null) {
                user = createWechatUser(request, phoneInfo, openpid);
            } else {
                updateWechatRegistrationProfile(user, request, phoneInfo, openpid);
            }
            createWechatAuth(user, session, openidHash);
        } else {
            user = userEntityMapper.selectById(auth.getUserId());
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException("微信账号状态异常");
            }
            updateLoginTime(user, auth);
        }

        return buildLoginResponse(user.getId());
    }

    /**
     * 创建微信登录用户
     *
     * @param request 微信登录请求
     * @param phoneInfo 微信手机号信息
     * @param openpid 插件用户 openpid
     * @return 用户实体
     */
    private UserEntity createWechatUser(
            WechatLoginRequest request,
            WechatPhoneNumberResponse.PhoneInfo phoneInfo,
            String openpid
    ) {
        LocalDateTime now = LocalDateTime.now();
        UserEntity user = new UserEntity();
        user.setUniqueCode(generateUniqueCode());
        user.setNickname(defaultString(request.getNickname(), "微信用户"));
        user.setAvatarUrl(defaultString(request.getAvatarUrl(), ""));
        user.setPhoneNumber(phoneInfo.getPhoneNumber());
        user.setPhoneCountryCode(defaultString(phoneInfo.getCountryCode(), ""));
        user.setPhoneLast4(last4(phoneInfo.getPhoneNumber()));
        user.setPhoneBoundAt(now);
        user.setWechatOpenpid(openpid);
        user.setProfession("");
        user.setCity("");
        user.setStatus("ACTIVE");
        user.setRegisteredAt(now);
        user.setLastLoginAt(now);
        userEntityMapper.insert(user);
        if (user.getId() == null) {
            throw new BusinessException("登录用户创建失败");
        }
        return user;
    }

    /**
     * 更新微信注册资料
     *
     * @param user 用户实体
     * @param request 微信登录请求
     * @param phoneInfo 微信手机号信息
     * @param openpid 插件用户 openpid
     */
    private void updateWechatRegistrationProfile(
            UserEntity user,
            WechatLoginRequest request,
            WechatPhoneNumberResponse.PhoneInfo phoneInfo,
            String openpid
    ) {
        LocalDateTime now = LocalDateTime.now();
        String nickname = defaultString(request.getNickname(), user.getNickname());
        String avatarUrl = defaultString(request.getAvatarUrl(), user.getAvatarUrl());
        user.setNickname(nickname);
        user.setAvatarUrl(avatarUrl);
        user.setPhoneNumber(phoneInfo.getPhoneNumber());
        user.setPhoneCountryCode(defaultString(phoneInfo.getCountryCode(), ""));
        user.setPhoneLast4(last4(phoneInfo.getPhoneNumber()));
        user.setPhoneBoundAt(user.getPhoneBoundAt() == null ? now : user.getPhoneBoundAt());
        if (openpid != null && !openpid.isBlank()) {
            user.setWechatOpenpid(openpid);
        }
        user.setLastLoginAt(now);
        userEntityMapper.updateById(user);
    }

    /**
     * 创建微信登录身份绑定
     *
     * @param user 用户实体
     * @param session 微信会话
     * @param openidHash openid 摘要
     */
    private void createWechatAuth(UserEntity user, WechatSessionResponse session, String openidHash) {
        LocalDateTime now = LocalDateTime.now();
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(user.getId());
        auth.setAuthType(WECHAT_AUTH_TYPE);
        auth.setIdentifierHash(openidHash);
        auth.setIdentifierCiphertext("WECHAT_OPENID_BOUND");
        if (session.getUnionid() != null && !session.getUnionid().isBlank()) {
            auth.setUnionIdentifierHash(digestIdentifier(session.getUnionid()));
            auth.setUnionIdentifierCiphertext("WECHAT_UNIONID_BOUND");
        }
        auth.setStatus("ACTIVE");
        auth.setLastAuthenticatedAt(now);
        userAuthEntityMapper.insert(auth);
    }

    /**
     * 更新登录时间
     *
     * @param user 用户实体
     * @param auth 登录身份实体
     */
    private void updateLoginTime(UserEntity user, UserAuthEntity auth) {
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        userEntityMapper.updateById(user);
        auth.setLastAuthenticatedAt(now);
        userAuthEntityMapper.updateById(auth);
    }

    /**
     * 构建登录响应
     *
     * @param userId 当前登录用户 ID
     * @return 登录响应
     */
    private WechatLoginResponse buildLoginResponse(Long userId) {
        WechatLoginResponse response = new WechatLoginResponse();
        response.setTokenType(TOKEN_TYPE);
        response.setToken(DEVELOPMENT_TOKEN_PREFIX + userId);
        response.setUserId(userId);
        response.setExpiresInSeconds(DEVELOPMENT_EXPIRES_IN_SECONDS);
        return response;
    }

    /**
     * 生成个人唯一码
     *
     * @return 个人唯一码
     */
    private String generateUniqueCode() {
        return "WF" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 默认字符串
     *
     * @param value 原字符串
     * @param fallback 兜底值
     * @return 非空字符串
     */
    private String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    /**
     * 查找激活的登录身份
     *
     * @param authType 登录类型
     * @param identifierHash 身份摘要
     * @return 登录身份实体
     */
    private UserAuthEntity findActiveAuth(String authType, String identifierHash) {
        return userAuthEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserAuthEntity.class)
                        .eq(UserAuthEntity::getAuthType, authType)
                        .eq(UserAuthEntity::getIdentifierHash, identifierHash)
                        .eq(UserAuthEntity::getStatus, "ACTIVE")
                        .last("LIMIT 1")
        );
    }

    /**
     * 按手机号查找激活用户
     *
     * @param phoneNumber 手机号
     * @return 用户实体
     */
    private UserEntity findActiveUserByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }
        return userEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserEntity.class)
                        .eq(UserEntity::getPhoneNumber, phoneNumber)
                        .eq(UserEntity::getStatus, "ACTIVE")
                        .last("LIMIT 1")
        );
    }

    /**
     * 要求提供手机号授权凭证
     *
     * @param request 微信登录请求
     * @return 手机号授权凭证
     */
    private String requirePhoneCode(WechatLoginRequest request) {
        if (request.getPhoneCode() == null || request.getPhoneCode().isBlank()) {
            throw new BusinessException("请先完成手机号授权注册");
        }
        return request.getPhoneCode();
    }

    /**
     * 尝试换取插件 openpid。普通小程序或低版本微信环境可能无法提供 wx.pluginLogin code，
     * 此时注册流程继续，只在拿到 code 时保存 openpid。
     *
     * @param pluginLoginCode wx.pluginLogin 返回的插件用户标志凭证
     * @return 插件用户 openpid，未提供凭证时为空
     */
    private String exchangePluginOpenpidIfPresent(String pluginLoginCode) {
        if (pluginLoginCode == null || pluginLoginCode.isBlank()) {
            return null;
        }
        return wechatMiniappClient.exchangePluginOpenpid(pluginLoginCode);
    }

    /**
     * 获取手机号尾号
     *
     * @param phoneNumber 手机号
     * @return 尾号
     */
    private String last4(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() <= 4) {
            return phoneNumber;
        }
        return phoneNumber.substring(phoneNumber.length() - 4);
    }

    /**
     * 对微信身份标识做 HMAC 摘要，避免明文 openid 入库
     *
     * @param identifier 微信 openid 或 unionid
     * @return 十六进制 HMAC 摘要
     */
    private String digestIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new BusinessException("微信身份标识不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(
                    wechatMiniappProperties.getAppSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            ));
            return HexFormat.of().formatHex(mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException("微信身份摘要生成失败", e);
        }
    }
}
