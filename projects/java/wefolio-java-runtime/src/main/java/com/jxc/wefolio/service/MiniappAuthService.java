package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.UniqueCodeGenerator;
import com.jxc.wefolio.common.auth.AuthorizationHeaderUtils;
import com.jxc.wefolio.config.AuthTokenProperties;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 小程序登录服务 — 提供维护者微信授权登录的后端入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiniappAuthService {

    /** 维护者登录令牌前缀 */
    private static final String MAINTAINER_TOKEN_PREFIX = "wf-maintainer-v1.";

    /** 维护者令牌 payload 分隔符 */
    private static final String TOKEN_PAYLOAD_SEPARATOR = ":";

    /** 维护者令牌 payload 字段数量 */
    private static final int TOKEN_PAYLOAD_PART_COUNT = 2;

    /** 维护者令牌 payload 用户 ID 下标 */
    private static final int TOKEN_PAYLOAD_USER_ID_INDEX = 0;

    /** 维护者令牌 payload 签发时间下标 */
    private static final int TOKEN_PAYLOAD_ISSUED_AT_INDEX = 1;

    /** 令牌解析失败提示 */
    private static final String TOKEN_PARSE_FAILED_MESSAGE = "登录令牌解析失败，请重新登录";

    /** 令牌过期提示 */
    private static final String TOKEN_EXPIRED_MESSAGE = "登录令牌已过期，请重新登录";

    /** 令牌密钥未配置提示 */
    private static final String TOKEN_SECRET_MISSING_MESSAGE = "登录令牌密钥未配置";

    /** 令牌类型 */
    private static final String TOKEN_TYPE = "Bearer";

    /** 手机号并发注册冲突提示 */
    private static final String PHONE_REGISTRATION_CONFLICT_MESSAGE = "手机号注册状态已变化，请重试";

    /** 维护者令牌有效期 */
    private static final long MAINTAINER_EXPIRES_IN_SECONDS = 30L * 24L * 60L * 60L;

    /** 微信小程序登录类型 */
    private static final String WECHAT_AUTH_TYPE = AuthTypeDict.WECHAT_MINI_APP.getCode();

    /** HMAC 算法 */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** 登录令牌密钥摘要算法 */
    private static final String SHA_256 = "SHA-256";

    /** 登录令牌加密算法 */
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";

    /** AES 密钥算法 */
    private static final String AES_ALGORITHM = "AES";

    /** GCM 初始向量字节数 */
    private static final int TOKEN_IV_LENGTH_BYTES = 12;

    /** GCM 认证标签位数 */
    private static final int TOKEN_GCM_TAG_LENGTH_BITS = 128;

    /** 令牌随机数生成器 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** 用户登录身份 Mapper */
    private final UserAuthEntityMapper userAuthEntityMapper;

    /** 微信小程序客户端 */
    private final WechatMiniappClient wechatMiniappClient;

    /** 微信小程序配置 */
    private final WechatMiniappProperties wechatMiniappProperties;

    /** 认证令牌配置 */
    private final AuthTokenProperties authTokenProperties;

    /** COS 对象存储服务 */
    private final CosService cosService;

    /** COS 配置 */
    private final CosProperties cosProperties;

    /** 用户注册服务 */
    private final UserRegistrationService userRegistrationService;

    /** 唯一码生成器 */
    private final UniqueCodeGenerator uniqueCodeGenerator;

    /**
     * 已解析的维护者登录令牌。
     *
     * @param userId 用户 ID
     * @param expiresAt 令牌服务端过期时间
     */
    public record ResolvedAuthToken(Long userId, Instant expiresAt) {
    }

    /**
     * 解析 bearer token 中的用户 ID
     *
     * @param authorizationHeader Authorization 请求头
     * @return 用户 ID，无法解析时返回空
     */
    public Long resolveUserId(String authorizationHeader) {
        ResolvedAuthToken resolvedAuthToken = resolveAuthToken(authorizationHeader);
        return resolvedAuthToken == null ? null : resolvedAuthToken.userId();
    }

    /**
     * 解析 bearer token 中的登录态信息。
     *
     * @param authorizationHeader Authorization 请求头
     * @return 登录态信息，无法解析为维护者令牌时返回空
     */
    public ResolvedAuthToken resolveAuthToken(String authorizationHeader) {
        String value = AuthorizationHeaderUtils.normalizeBearerToken(authorizationHeader);
        if (value.isBlank()) {
            return null;
        }
        if (!value.startsWith(MAINTAINER_TOKEN_PREFIX)) {
            return null;
        }
        return decryptUserIdToken(value);
    }

    /**
     * 解密维护者登录令牌。
     *
     * @param token 登录令牌
     * @return 登录态信息
     */
    private ResolvedAuthToken decryptUserIdToken(String token) {
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token.substring(MAINTAINER_TOKEN_PREFIX.length()));
            if (tokenBytes.length <= TOKEN_IV_LENGTH_BYTES) {
                throw new BusinessException(TOKEN_PARSE_FAILED_MESSAGE);
            }
            byte[] iv = Arrays.copyOfRange(tokenBytes, 0, TOKEN_IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(tokenBytes, TOKEN_IV_LENGTH_BYTES, tokenBytes.length);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, buildTokenSecretKey(), new GCMParameterSpec(TOKEN_GCM_TAG_LENGTH_BITS, iv));
            String payload = new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
            return parseTokenPayload(payload);
        } catch (BusinessException e) {
            log.warn("维护者登录令牌解析失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("维护者登录令牌解析失败: errorType={}, errorMessage={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw new BusinessException(TOKEN_PARSE_FAILED_MESSAGE, e);
        }
    }

    /**
     * 解析维护者登录令牌 payload。
     *
     * @param payload 解密后的 payload
     * @return 登录态信息
     */
    private ResolvedAuthToken parseTokenPayload(String payload) {
        String[] parts = payload.split(TOKEN_PAYLOAD_SEPARATOR, -1);
        if (parts.length != TOKEN_PAYLOAD_PART_COUNT) {
            throw new BusinessException(TOKEN_PARSE_FAILED_MESSAGE);
        }
        long userId;
        long issuedAtEpochSeconds;
        try {
            userId = Long.parseLong(parts[TOKEN_PAYLOAD_USER_ID_INDEX]);
            issuedAtEpochSeconds = Long.parseLong(parts[TOKEN_PAYLOAD_ISSUED_AT_INDEX]);
        } catch (NumberFormatException e) {
            throw new BusinessException(TOKEN_PARSE_FAILED_MESSAGE, e);
        }
        if (userId <= 0 || issuedAtEpochSeconds <= 0) {
            throw new BusinessException(TOKEN_PARSE_FAILED_MESSAGE);
        }
        Instant expiresAt = Instant.ofEpochSecond(issuedAtEpochSeconds).plusSeconds(MAINTAINER_EXPIRES_IN_SECONDS);
        if (!expiresAt.isAfter(Instant.now())) {
            throw new BusinessException(TOKEN_EXPIRED_MESSAGE);
        }
        return new ResolvedAuthToken(userId, expiresAt);
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
                // 注册前端头像为本地临时路径时无法由服务端读取，后续由已登录上传接口写回 COS 地址
                String originalAvatarUrl = request.getAvatarUrl();
                request.setAvatarUrl(uploadAvatarToCos(request.getAvatarUrl(), uniqueCode, ""));
                try {
                    user = userRegistrationService.createWechatMaintainerUser(
                            uniqueCode,
                            request,
                            phoneInfo,
                            openpid,
                            openidHash,
                            digestIdentifierIfPresent(session.getUnionid())
                    );
                } catch (DuplicateKeyException e) {
                    request.setAvatarUrl(originalAvatarUrl);
                    user = bindExistingPhoneUserAfterRegistrationConflict(request, session, phoneInfo, openpid, openidHash, e);
                }
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
     * 处理手机号并发首次注册冲突。
     * <p>数据库唯一索引会阻止重复用户；冲突后重新读取已有手机号用户，并复用普通“手机号已存在”分支完成微信身份绑定。</p>
     *
     * @param request 维护者微信登录请求
     * @param session 微信会话
     * @param phoneInfo 微信手机号信息
     * @param openpid 插件用户 openpid
     * @param openidHash openid 摘要
     * @param cause 数据库唯一键异常
     * @return 已存在的用户实体
     */
    private UserEntity bindExistingPhoneUserAfterRegistrationConflict(
            MaintainerWechatLoginRequest request,
            WechatSessionResponse session,
            WechatPhoneNumberResponse.PhoneInfo phoneInfo,
            String openpid,
            String openidHash,
            DuplicateKeyException cause
    ) {
        log.warn("手机号并发注册冲突，尝试复用已存在用户: phoneLast4={}", last4(phoneInfo.getPhoneNumber()));
        UserEntity existingUser = findActiveUserByPhone(phoneInfo.getPhoneNumber());
        if (existingUser == null) {
            throw new BusinessException(PHONE_REGISTRATION_CONFLICT_MESSAGE, cause);
        }
        updateWechatRegistrationProfile(existingUser, request, phoneInfo, openpid);
        createWechatAuth(existingUser, session, openidHash);
        return existingUser;
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
     * 将头像远程 URL 上传到 COS 的 {@code {uniqueCode}/others/} 目录。
     * 非 http(s) 地址通常是小程序本地临时路径，服务端无法读取，直接回退。
     *
     * @param avatarUrl 头像地址
     * @param uniqueCode 用户唯一码
     * @param fallbackUrl 无法上传时使用的回退地址
     * @return COS 公开 URL、原远程 URL 或回退地址
     */
    private String uploadAvatarToCos(String avatarUrl, String uniqueCode, String fallbackUrl) {
        log.info("注册头像上传 COS 开始: uniqueCode={}, avatarUrl={}", uniqueCode, avatarUrl);
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return defaultString(fallbackUrl, "");
        }
        String normalizedAvatarUrl = avatarUrl.trim();
        // 已经是 COS URL，跳过上传
        String publicBaseUrl = cosProperties.getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank() && normalizedAvatarUrl.startsWith(publicBaseUrl)) {
            return normalizedAvatarUrl;
        }
        if (!isHttpUrl(normalizedAvatarUrl)) {
            log.info("跳过非远程头像路径: uniqueCode={}, avatarUrl={}", uniqueCode, normalizedAvatarUrl);
            return defaultString(fallbackUrl, "");
        }
        try {
            String key = cosService.uploadFromUrl(normalizedAvatarUrl, uniqueCode + "/others");
            String cosUrl = cosService.publicUrl(key);
            log.info("头像上传 COS 成功: uniqueCode={}, cosUrl={}", uniqueCode, cosUrl);
            return cosUrl;
        } catch (Exception e) {
            log.warn("头像上传 COS 失败，使用原始 URL: uniqueCode={}, avatarUrl={}", uniqueCode, normalizedAvatarUrl, e);
            return normalizedAvatarUrl;
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
        String avatarUrl = uploadAvatarToCos(request.getAvatarUrl(), user.getUniqueCode(), user.getAvatarUrl());
        user.setNickname(nickname);

        // 头像地址变更时校验每月更新次数限制（跨月自动重置）
        String currentAvatarUrl = defaultString(user.getAvatarUrl(), "");
        if (!avatarUrl.equals(currentAvatarUrl)) {
            int currentCount = user.getAvatarUpdateCount() != null ? user.getAvatarUpdateCount() : 0;
            LocalDateTime lastUpdate = user.getLastAvatarUpdatedAt();
            boolean newMonth = lastUpdate == null
                    || lastUpdate.getYear() != now.getYear()
                    || lastUpdate.getMonthValue() != now.getMonthValue();
            int newCount = newMonth ? 1 : currentCount + 1;
            if (newCount > UserEntity.AVATAR_MONTHLY_MAX_COUNT) {
                throw new BusinessException("当月头像更新次数已达上限（" + UserEntity.AVATAR_MONTHLY_MAX_COUNT + "次），请下月再试");
            }
            user.setLastAvatarUpdatedAt(now);
            user.setAvatarUpdateCount(newCount);
        }
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
        response.setToken(encryptUserIdToken(userId));
        response.setUserId(userId);
        response.setExpiresInSeconds(MAINTAINER_EXPIRES_IN_SECONDS);
        return response;
    }

    /**
     * 加密用户 ID 生成维护者登录令牌。
     *
     * @param userId 当前登录用户 ID
     * @return 加密后的登录令牌
     */
    private String encryptUserIdToken(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("登录用户异常");
        }
        try {
            byte[] iv = new byte[TOKEN_IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, buildTokenSecretKey(), new GCMParameterSpec(TOKEN_GCM_TAG_LENGTH_BITS, iv));
            String payload = userId + TOKEN_PAYLOAD_SEPARATOR + Instant.now().getEpochSecond();
            byte[] cipherText = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] tokenBytes = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, tokenBytes, 0, iv.length);
            System.arraycopy(cipherText, 0, tokenBytes, iv.length, cipherText.length);
            return MAINTAINER_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        } catch (Exception e) {
            throw new BusinessException("登录令牌生成失败", e);
        }
    }

    /**
     * 从应用层令牌密钥派生令牌加密密钥。
     *
     * @return AES 密钥
     */
    private SecretKeySpec buildTokenSecretKey() {
        String tokenSecret = authTokenProperties.getSecret();
        if (tokenSecret == null || tokenSecret.isBlank()) {
            throw new BusinessException(TOKEN_SECRET_MISSING_MESSAGE);
        }
        try {
            byte[] key = MessageDigest.getInstance(SHA_256).digest(tokenSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, AES_ALGORITHM);
        } catch (Exception e) {
            throw new BusinessException("登录令牌密钥生成失败", e);
        }
    }

    /**
     * 生成个人唯一码 — 委托统一生成器，前缀 WF。
     *
     * @return 个人唯一码
     */
    private String generateUniqueCode() {
        return uniqueCodeGenerator.generate(UniqueCodeGenerator.USER_PREFIX, candidates ->
                userEntityMapper.selectList(
                                Wrappers.<UserEntity>query()
                                        .select("unique_code")
                                        .in("unique_code", candidates))
                        .stream()
                        .map(UserEntity::getUniqueCode)
                        .collect(Collectors.toSet()));
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
     * 判断是否为服务端可拉取的远程 HTTP 地址。
     *
     * @param value 待判断地址
     * @return 是否为 http 或 https 地址
     */
    private boolean isHttpUrl(String value) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return lowerValue.startsWith("http://") || lowerValue.startsWith("https://");
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
