package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.UniqueCodeGenerator;
import com.jxc.wefolio.common.auth.AuthorizationHeaderUtils;
import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dto.AuthSessionResponse;
import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.MaintainerWechatLoginPrecheckRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginPrecheckResponse;
import com.jxc.wefolio.dto.MaintainerWechatSessionRefreshRequest;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.InvalidAuthTokenException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.MiniappAuthMessage;
import com.jxc.wefolio.service.payment.MaintainerWechatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
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

    /** 令牌类型 */
    private static final String TOKEN_TYPE = "Bearer";

    /** 维护者令牌有效期 */
    private static final long MAINTAINER_EXPIRES_IN_SECONDS = 30L * 24L * 60L * 60L;

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

    /** 加密认证令牌服务 */
    private final EncryptedAuthTokenService encryptedAuthTokenService;

    /** COS 对象存储服务 */
    private final CosService cosService;

    /** COS 配置 */
    private final CosProperties cosProperties;

    /** 用户注册服务 */
    private final UserRegistrationService userRegistrationService;

    /** 唯一码生成器 */
    private final UniqueCodeGenerator uniqueCodeGenerator;

    /** 维护者微信会话服务。 */
    private final MaintainerWechatSessionService maintainerWechatSessionService;

    /** 微信虚拟支付配置。 */
    private final WechatVirtualPaymentProperties wechatVirtualPaymentProperties;

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
            String payload = encryptedAuthTokenService.decryptPayload(MAINTAINER_TOKEN_PREFIX, token);
            return parseTokenPayload(payload);
        } catch (BusinessException e) {
            log.warn("维护者登录令牌解析失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("维护者登录令牌解析失败: errorType={}, errorMessage={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE, e);
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
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
        }
        long userId;
        long issuedAtEpochSeconds;
        try {
            userId = Long.parseLong(parts[TOKEN_PAYLOAD_USER_ID_INDEX]);
            issuedAtEpochSeconds = Long.parseLong(parts[TOKEN_PAYLOAD_ISSUED_AT_INDEX]);
        } catch (NumberFormatException e) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE, e);
        }
        if (userId <= 0 || issuedAtEpochSeconds <= 0) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
        }
        Instant expiresAt = Instant.ofEpochSecond(issuedAtEpochSeconds).plusSeconds(MAINTAINER_EXPIRES_IN_SECONDS);
        if (!expiresAt.isAfter(Instant.now())) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_EXPIRED_MESSAGE);
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
        long intervalSeconds = wechatVirtualPaymentProperties == null
                ? 300L
                : wechatVirtualPaymentProperties.sessionCheckIntervalSeconds();
        response.setWechatSessionCheckIntervalSeconds(intervalSeconds);
        return response;
    }

    /**
     * 维护者微信授权登录。
     *
     * @param request 维护者微信登录请求
     * @return 维护者登录响应
     */
    public MaintainerWechatLoginResponse loginMaintainerByWechat(MaintainerWechatLoginRequest request) {
        return loginMaintainerByWechat(request, null);
    }

    /**
     * 维护者微信授权登录并保存虚拟支付所需会话。
     *
     * @param request 维护者微信登录请求
     * @param clientIp 容器可信代理配置解析后的客户端 IP
     * @return 维护者登录响应
     */
    public MaintainerWechatLoginResponse loginMaintainerByWechat(
            MaintainerWechatLoginRequest request,
            String clientIp
    ) {
        if (request == null || request.getCode() == null || request.getCode().isBlank()) {
            throw new BusinessException(MiniappAuthMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        }

        WechatSessionResponse session = wechatMiniappClient.exchangeCode(request.getCode());
        String openId = normalizeRequiredOpenId(session.getOpenid());
        String openidHash = digestIdentifier(openId);
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
                request.setAvatarUrl(resolveAvatarUrl(request.getAvatarUrl(), uniqueCode, ""));
                try {
                    user = userRegistrationService.createWechatMaintainerUser(
                            uniqueCode,
                            request,
                            phoneInfo,
                            openpid,
                            openidHash,
                            openId,
                            digestIdentifierIfPresent(session.getUnionid())
                    );
                } catch (DuplicateKeyException e) {
                    request.setAvatarUrl(originalAvatarUrl);
                    user = bindExistingPhoneUserAfterRegistrationConflict(
                            request,
                            session,
                            phoneInfo,
                            openpid,
                            openidHash,
                            openId,
                            e
                    );
                }
            } else {
                updateWechatRegistrationProfile(user, request, phoneInfo, openpid);
                createWechatAuth(user, session, openidHash, openId);
            }
        } else {
            user = userEntityMapper.selectById(auth.getUserId());
            if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                throw new BusinessException("微信账号状态异常");
            }
            updateLoginTime(user, auth, openId);

            // 校验 COS 文件夹是否存在（处理老用户或意外删除场景），失败不影响登录
            ensureUserStorage(user);
        }

        saveMaintainerWechatSessionIfEnabled(user.getId(), openidHash, session, clientIp);
        return buildMaintainerLoginResponse(user.getId());
    }

    /**
     * 预检维护者微信身份是否需要手机号授权。
     *
     * @param request 维护者微信登录预检请求
     * @return 手机号授权要求
     */
    public MaintainerWechatLoginPrecheckResponse precheckMaintainerWechatLogin(
            MaintainerWechatLoginPrecheckRequest request
    ) {
        if (request == null || request.getCode() == null || request.getCode().isBlank()) {
            throw new BusinessException(MiniappAuthMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        }
        WechatSessionResponse session = wechatMiniappClient.exchangeCode(request.getCode());
        String openId = normalizeRequiredOpenId(session.getOpenid());
        String openidHash = digestIdentifier(openId);
        UserAuthEntity auth = findActiveAuth(WECHAT_AUTH_TYPE, openidHash);
        MaintainerWechatLoginPrecheckResponse response = new MaintainerWechatLoginPrecheckResponse();
        response.setPhoneAuthorizationRequired(auth == null);
        return response;
    }

    /**
     * 使用当前维护者身份刷新微信 session_key，openid 不一致时拒绝覆盖绑定关系。
     *
     * @param userId 当前维护者用户 ID
     * @param request 微信登录 code
     * @param clientIp 容器可信代理配置解析后的客户端 IP
     */
    public void refreshMaintainerWechatSession(
            Long userId,
            MaintainerWechatSessionRefreshRequest request,
            String clientIp
    ) {
        if (userId == null || request == null || request.getCode() == null || request.getCode().isBlank()) {
            throw new BusinessException(MiniappAuthMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        }
        WechatSessionResponse session = wechatMiniappClient.exchangeCode(request.getCode());
        String openId = normalizeRequiredOpenId(session.getOpenid());
        String openidHash = digestIdentifier(openId);
        UserAuthEntity auth = findActiveAuth(WECHAT_AUTH_TYPE, openidHash);
        if (auth == null || !Objects.equals(auth.getUserId(), userId)) {
            throw new BusinessException("微信身份与当前维护者不一致，请重新登录");
        }
        if (maintainerWechatSessionService == null) {
            throw new BusinessException("微信虚拟支付会话服务不可用");
        }
        maintainerWechatSessionService.saveAvailableSession(
                userId, auth.getId(), session.getSessionKey(), requireClientIp(clientIp));
    }

    /** 登录成功后在虚拟支付启用时保存维护者微信会话。 */
    private void saveMaintainerWechatSessionIfEnabled(
            Long userId,
            String openidHash,
            WechatSessionResponse session,
            String clientIp
    ) {
        if (maintainerWechatSessionService == null) {
            return;
        }
        UserAuthEntity auth = findActiveAuth(WECHAT_AUTH_TYPE, openidHash);
        if (auth == null || !Objects.equals(auth.getUserId(), userId)) {
            throw new BusinessException("微信身份保存失败，请重新登录");
        }
        maintainerWechatSessionService.saveAvailableSession(
                userId, auth.getId(), session.getSessionKey(), requireClientIp(clientIp));
    }

    /** 校验服务端解析得到的客户端 IP。 */
    private String requireClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            throw new BusinessException("无法确认客户端网络地址，请重新登录");
        }
        return clientIp.strip();
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
     * @param openId 微信 openid 明文，仅服务端用于本人访问识别
     * @param cause 数据库唯一键异常
     * @return 已存在的用户实体
     */
    private UserEntity bindExistingPhoneUserAfterRegistrationConflict(
            MaintainerWechatLoginRequest request,
            WechatSessionResponse session,
            WechatPhoneNumberResponse.PhoneInfo phoneInfo,
            String openpid,
            String openidHash,
            String openId,
            DuplicateKeyException cause
    ) {
        log.warn("手机号并发注册冲突，尝试复用已存在用户: phoneLast4={}", last4(phoneInfo.getPhoneNumber()));
        UserEntity existingUser = findActiveUserByPhone(phoneInfo.getPhoneNumber());
        if (existingUser == null) {
            throw new BusinessException(MiniappAuthMessage.PHONE_REGISTRATION_CONFLICT_MESSAGE, cause);
        }
        updateWechatRegistrationProfile(existingUser, request, phoneInfo, openpid);
        createWechatAuth(existingUser, session, openidHash, openId);
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
     * 解析头像 URL。
     * <p>头像文件必须通过已登录的 multipart 上传接口写入 COS；这里不再抓取客户端传入的远程地址，避免服务端访问不可信 URL。</p>
     *
     * @param avatarUrl 头像地址
     * @param uniqueCode 用户唯一码
     * @param fallbackUrl 无法上传时使用的回退地址
     * @return 已有 COS 公开 URL 或回退地址
     */
    private String resolveAvatarUrl(String avatarUrl, String uniqueCode, String fallbackUrl) {
        log.info("解析注册头像地址: uniqueCode={}, avatarUrl={}", uniqueCode, avatarUrl);
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
        log.info("跳过远程头像地址，等待已登录上传接口写入 COS: uniqueCode={}", uniqueCode);
        return defaultString(fallbackUrl, "");
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
        String avatarUrl = resolveAvatarUrl(request.getAvatarUrl(), user.getUniqueCode(), user.getAvatarUrl());
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
     * @param openId 微信 openid 明文，仅服务端用于本人访问识别
     */
    private void createWechatAuth(UserEntity user, WechatSessionResponse session, String openidHash, String openId) {
        LocalDateTime now = LocalDateTime.now();
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(user.getId());
        auth.setAuthType(WECHAT_AUTH_TYPE);
        auth.setIdentifierHash(openidHash);
        auth.setIdentifierCiphertext("WECHAT_OPENID_BOUND");
        auth.setOpenId(openId);
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
     * @param openId 本次微信会话 openid
     */
    private void updateLoginTime(UserEntity user, UserAuthEntity auth, String openId) {
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        userEntityMapper.updateById(user);
        if ((auth.getOpenId() == null || auth.getOpenId().isBlank()) && openId != null && !openId.isBlank()) {
            auth.setOpenId(openId);
            log.info("补写维护者微信 openId: userId={}, authId={}, openIdLast4={}",
                    user.getId(), auth.getId(), last4(openId));
        }
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
        String payload = userId + TOKEN_PAYLOAD_SEPARATOR + Instant.now().getEpochSecond();
        return encryptedAuthTokenService.encryptPayload(MAINTAINER_TOKEN_PREFIX, payload);
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
     * 规范化微信 openid。
     *
     * @param openId 微信会话 openid
     * @return 去除首尾空白后的 openid
     */
    private String normalizeRequiredOpenId(String openId) {
        if (openId == null || openId.isBlank()) {
            throw new BusinessException("微信身份标识不能为空");
        }
        return openId.strip();
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
     * 对微信身份标识做 HMAC 摘要，用于微信身份查询匹配。
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
