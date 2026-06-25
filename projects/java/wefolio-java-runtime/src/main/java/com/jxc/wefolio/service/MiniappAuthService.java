package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthorizationHeaderUtils;
import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.AuthSessionResponse;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 小程序登录服务 — 提供维护者微信授权登录的后端入口。
 */
@Slf4j
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
    private static final String WECHAT_AUTH_TYPE = AuthTypeDict.WECHAT_MINI_APP.getCode();

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

    /** COS 对象存储服务 */
    private final CosService cosService;

    /** COS 配置 */
    private final CosProperties cosProperties;

    /** 用户注册服务 */
    private final UserRegistrationService userRegistrationService;

    /**
     * 解析 bearer token 中的用户 ID
     *
     * @param authorizationHeader Authorization 请求头
     * @return 用户 ID，无法解析时返回空
     */
    public Long resolveUserId(String authorizationHeader) {
        String value = AuthorizationHeaderUtils.normalizeBearerToken(authorizationHeader);
        if (value.isBlank()) {
            return null;
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
     * 按用户 ID 获取个人唯一码
     *
     * @param userId 用户 ID
     * @return 个人唯一码，用户不存在时抛异常
     */
    public String getUniqueCodeByUserId(Long userId) {
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user.getUniqueCode();
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
     * 维护者微信授权登录。
     *
     * @param request 维护者微信登录请求
     * @return 维护者登录响应
     */
    public MaintainerWechatLoginResponse loginMaintainerByWechat(MaintainerWechatLoginRequest request) {
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
                String uniqueCode = generateUniqueCode();
                // COS 文件夹初始化在前，失败直接抛异常，不污染数据库
                cosService.initUserStorage(uniqueCode);
                // 将微信头像上传到 COS，替换 request 中的原始微信 URL
                request.setAvatarUrl(uploadAvatarToCos(request.getAvatarUrl(), uniqueCode));
                user = userRegistrationService.createWechatMaintainerUser(
                        uniqueCode,
                        request,
                        phoneInfo,
                        openpid,
                        openidHash,
                        digestIdentifierIfPresent(session.getUnionid())
                );
            } else {
                updateWechatRegistrationProfile(user, request, phoneInfo, openpid);
                createWechatAuth(user, session, openidHash);
            }
        } else {
            user = userEntityMapper.selectById(auth.getUserId());
            if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                throw new BusinessException("微信账号状态异常");
            }
            updateLoginTime(user, auth);

            // 校验 COS 文件夹是否存在（处理老用户或意外删除场景），失败不影响登录
            ensureUserStorage(user);
        }

        return buildMaintainerLoginResponse(user.getId());
    }

    /**
     * 确保老用户的 COS 文件夹结构存在（处理上线前的存量用户或意外删除场景）。
     * 登录时静默补建，失败仅记日志不阻断登录。
     *
     * @param user 用户实体
     */
    private void ensureUserStorage(UserEntity user) {
        try {
            if (!cosService.isUserStorageInitialized(user.getUniqueCode())) {
                log.info("补建 COS 文件夹 uniqueCode={}", user.getUniqueCode());
                cosService.initUserStorage(user.getUniqueCode());
            }
        } catch (Exception e) {
            log.error("COS 文件夹补建失败 uniqueCode={}", user.getUniqueCode(), e);
        }
    }

    /**
     * 将头像从远程 URL 上传到 COS 的 {@code {uniqueCode}/others/} 目录。
     * 上传失败时返回原始 URL 作为降级处理，不影响注册主流程。
     * 如果已经是 COS URL 则跳过上传。
     *
     * @param avatarUrl  头像 URL（通常为微信返回的远程 URL）
     * @param uniqueCode 用户唯一码
     * @return COS 公开 URL，上传失败时返回原始 URL
     */
    private String uploadAvatarToCos(String avatarUrl, String uniqueCode) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return avatarUrl;
        }
        // 已经是 COS URL，跳过上传
        String publicBaseUrl = cosProperties.getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank() && avatarUrl.startsWith(publicBaseUrl)) {
            return avatarUrl;
        }
        try {
            String key = cosService.uploadFromUrl(avatarUrl, uniqueCode + "/others");
            return cosService.publicUrl(key);
        } catch (Exception e) {
            log.warn("头像上传 COS 失败，使用原始 URL: uniqueCode={}, url={}", uniqueCode, avatarUrl, e);
            return avatarUrl;
        }
    }

    /**
     * 更新微信注册资料
     *
     * @param user 用户实体
     * @param request 维护者微信登录请求
     * @param phoneInfo 微信手机号信息
     * @param openpid 插件用户 openpid
     */
    private void updateWechatRegistrationProfile(
            UserEntity user,
            MaintainerWechatLoginRequest request,
            WechatPhoneNumberResponse.PhoneInfo phoneInfo,
            String openpid
    ) {
        LocalDateTime now = LocalDateTime.now();
        String nickname = defaultString(request.getNickname(), user.getNickname());
        String avatarUrl = uploadAvatarToCos(
                defaultString(request.getAvatarUrl(), user.getAvatarUrl()),
                user.getUniqueCode()
        );
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
        String unionidHash = digestIdentifierIfPresent(session.getUnionid());
        if (unionidHash != null) {
            auth.setUnionIdentifierHash(unionidHash);
            auth.setUnionIdentifierCiphertext("WECHAT_UNIONID_BOUND");
        }
        auth.setStatus(UserStatusDict.ACTIVE.getCode());
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
     * 构建维护者登录响应。
     *
     * @param userId 当前登录用户 ID
     * @return 维护者登录响应
     */
    private MaintainerWechatLoginResponse buildMaintainerLoginResponse(Long userId) {
        MaintainerWechatLoginResponse response = new MaintainerWechatLoginResponse();
        response.setTokenType(TOKEN_TYPE);
        response.setToken(DEVELOPMENT_TOKEN_PREFIX + userId);
        response.setUserId(userId);
        response.setExpiresInSeconds(DEVELOPMENT_EXPIRES_IN_SECONDS);
        return response;
    }

    /**
     * 生成个人唯一码 — 一次生成 3 个候选码，批量查库取第一个未使用的
     *
     * @return 个人唯一码
     */
    private String generateUniqueCode() {
        List<String> candidates = Stream.generate(() -> "WF" + UUID.randomUUID().toString()
                        .replace("-", "")
                        .substring(0, 8)
                        .toUpperCase(Locale.ROOT))
                .limit(3)
                .collect(Collectors.toList());
        Set<String> used = userEntityMapper.selectList(
                        Wrappers.<UserEntity>query()
                                .select("unique_code")
                                .in("unique_code", candidates))
                .stream()
                .map(UserEntity::getUniqueCode)
                .collect(Collectors.toSet());
        for (String code : candidates) {
            if (!used.contains(code)) {
                return code;
            }
        }
        throw new BusinessException("唯一码生成失败，请重试");
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
                        .eq(UserAuthEntity::getStatus, UserStatusDict.ACTIVE.getCode())
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
                        .eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .last("LIMIT 1")
        );
    }

    /**
     * 要求提供手机号授权凭证
     *
     * @param request 维护者微信登录请求
     * @return 手机号授权凭证
     */
    private String requirePhoneCode(MaintainerWechatLoginRequest request) {
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

    /**
     * 有值时计算微信身份摘要。
     *
     * @param identifier 微信 openid 或 unionid
     * @return 十六进制 HMAC 摘要，入参为空时返回空
     */
    private String digestIdentifierIfPresent(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        return digestIdentifier(identifier);
    }
}
