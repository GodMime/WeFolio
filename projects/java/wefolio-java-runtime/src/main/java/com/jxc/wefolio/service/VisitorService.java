package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.VisitorMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 访客身份服务 — 负责按微信 openid 维护全局访客资料和资料授权 token。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisitorService {

    /** 访客资料 token 缓存前缀 */
    private static final String PROFILE_TOKEN_CACHE_PREFIX = "visitor:profile-token:";

    /** 朋友圈单页匿名会话格式 */
    private static final Pattern TIMELINE_ANONYMOUS_SESSION_PATTERN =
            Pattern.compile("^timeline-[a-z0-9-]{32,119}$");

    /** 朋友圈单页匿名身份命名空间 */
    private static final String TIMELINE_ANONYMOUS_IDENTITY_PREFIX = "timeline:";

    /** 服务端匿名身份作用域格式 */
    private static final Pattern ANONYMOUS_IDENTITY_SCOPE_PATTERN =
            Pattern.compile("^(PERSONAL|TEAM):[1-9][0-9]{0,18}$");

    /** 匿名身份摘要算法 */
    private static final String ANONYMOUS_IDENTITY_DIGEST_ALGORITHM = "SHA-256";

    /** 摘要算法不可用提示 */
    private static final String DIGEST_ALGORITHM_UNAVAILABLE_MESSAGE = "匿名访客身份摘要算法不可用";

    /** 访客资料 token 有效期 */
    private static final Duration PROFILE_TOKEN_TTL = Duration.ofMinutes(30);

    /** 访客资料 token 随机字节数 */
    private static final int PROFILE_TOKEN_RANDOM_BYTES = 32;

    /** 访客头像 COS 目录 */
    private static final String VISITOR_AVATAR_FOLDER = "visit";

    /** 访客头像文件名前缀 */
    private static final String VISITOR_AVATAR_FILE_PREFIX = "visitor-avatar";

    /** 头像文件名时间格式 */
    private static final DateTimeFormatter AVATAR_FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 文件名随机后缀长度 */
    private static final int AVATAR_RANDOM_LENGTH = 8;

    /** 访客头像最大字节数 */
    private static final long VISITOR_AVATAR_MAX_BYTES = 200L * 1024L;

    /** 头像 ticket 过期分钟数 */
    private static final long AVATAR_TICKET_EXPIRE_MINUTES = 15L;

    /** 访客昵称最大长度 */
    private static final int VISITOR_NICKNAME_MAX_LENGTH = 50;

    /** 访客头像地址最大长度 */
    private static final int VISITOR_AVATAR_URL_MAX_LENGTH = 512;

    /** JPEG MIME 类型 */
    private static final String MIME_IMAGE_JPEG = "image/jpeg";

    /** JPG MIME 类型兼容值 */
    private static final String MIME_IMAGE_JPG = "image/jpg";

    /** PNG MIME 类型 */
    private static final String MIME_IMAGE_PNG = "image/png";

    /** WEBP MIME 类型 */
    private static final String MIME_IMAGE_WEBP = "image/webp";

    /** JPG 扩展名 */
    private static final String EXTENSION_JPG = "jpg";

    /** PNG 扩展名 */
    private static final String EXTENSION_PNG = "png";

    /** WEBP 扩展名 */
    private static final String EXTENSION_WEBP = "webp";

    /** 安全随机数 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 访客 Mapper */
    private final VisitorEntityMapper visitorEntityMapper;

    /** 微信小程序客户端 */
    private final WechatMiniappClient wechatMiniappClient;

    /** 缓存服务 */
    private final CacheService cacheService;

    /** COS 服务 */
    private final CosService cosService;

    /** 访客身份短事务持久化服务。 */
    private final VisitorIdentityPersistenceService visitorIdentityPersistenceService;

    /**
     * 按 wx.login code 解析或创建全局访客。
     *
     * @param loginCode wx.login 返回的临时登录凭证
     * @return 访客会话
     */
    public VisitorSession resolveByLoginCode(String loginCode) {
        return resolveByLoginCode(loginCode, null);
    }

    /**
     * 按 wx.login code 解析或创建全局访客，并把远端调用与身份持久化分别计时。
     *
     * @param loginCode wx.login 返回的临时登录凭证
     * @param trace 作品集打开链路追踪器；非打开链路可传 null
     * @return 访客会话
     */
    public VisitorSession resolveByLoginCode(
            String loginCode,
            PortfolioOpenPerformanceLogger.Trace trace
    ) {
        WechatSessionResponse session = measure(trace, PortfolioOpenPerformanceLogger.Phase.WECHAT_LOGIN, () -> {
            String normalizedCode = normalizeRequired(
                    loginCode, PortfolioMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
            WechatSessionResponse exchangedSession = wechatMiniappClient.exchangeCode(normalizedCode);
            if (exchangedSession == null || exchangedSession.getOpenid() == null
                    || exchangedSession.getOpenid().isBlank()) {
                throw new BusinessException(PortfolioMessage.WECHAT_OPENID_MISSING_MESSAGE);
            }
            return exchangedSession;
        });
        String openid = session.getOpenid().strip();
        String unionid = normalizeNullable(session.getUnionid());
        VisitorIdentityPersistenceService.VisitorIdentity identity = measure(
                trace,
                PortfolioOpenPerformanceLogger.Phase.VISITOR_PERSIST,
                () -> visitorIdentityPersistenceService
                        .tryResolveOrCreate(openid, unionid)
                        .orElseGet(() -> visitorIdentityPersistenceService.recoverIgnoredInsert(openid, unionid)));
        return new VisitorSession(identity.visitor(), identity.newVisitor(), false);
    }

    /**
     * 解析作品集打开身份；普通场景优先使用微信登录码，朋友圈单页模式使用匿名会话。
     *
     * @param loginCode wx.login 返回的临时登录凭证
     * @param anonymousSessionId 朋友圈单页匿名会话标识
     * @param anonymousIdentityScope 服务端生成的作品集匿名身份作用域
     * @param trace 作品集打开链路追踪器
     * @return 访客会话
     */
    public VisitorSession resolveForOpen(
            String loginCode,
            String anonymousSessionId,
            String anonymousIdentityScope,
            PortfolioOpenPerformanceLogger.Trace trace
    ) {
        if (hasText(loginCode)) {
            return resolveByLoginCode(loginCode, trace);
        }
        String normalizedAnonymousSessionId = normalizeNullable(anonymousSessionId);
        if (normalizedAnonymousSessionId == null
                || !TIMELINE_ANONYMOUS_SESSION_PATTERN.matcher(normalizedAnonymousSessionId).matches()) {
            throw new BusinessException(PortfolioMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        }
        String normalizedAnonymousIdentityScope = normalizeNullable(anonymousIdentityScope);
        if (normalizedAnonymousIdentityScope == null
                || !ANONYMOUS_IDENTITY_SCOPE_PATTERN.matcher(normalizedAnonymousIdentityScope).matches()) {
            throw new BusinessException(PortfolioMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        }
        String anonymousIdentity = TIMELINE_ANONYMOUS_IDENTITY_PREFIX
                + sha256Hex(normalizedAnonymousIdentityScope);
        VisitorIdentityPersistenceService.VisitorIdentity identity = measure(
                trace,
                PortfolioOpenPerformanceLogger.Phase.VISITOR_PERSIST,
                () -> visitorIdentityPersistenceService
                        .tryResolveOrCreate(anonymousIdentity, null)
                        .orElseGet(() -> visitorIdentityPersistenceService
                                .recoverIgnoredInsert(anonymousIdentity, null)));
        return new VisitorSession(identity.visitor(), identity.newVisitor(), true);
    }

    /** 计算匿名身份作用域的 SHA-256 十六进制摘要。 */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ANONYMOUS_IDENTITY_DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(DIGEST_ALGORITHM_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /** 在存在追踪器时计量指定阶段。 */
    private <T> T measure(
            PortfolioOpenPerformanceLogger.Trace trace,
            PortfolioOpenPerformanceLogger.Phase phase,
            Supplier<T> action
    ) {
        return trace == null ? action.get() : trace.measure(phase, action);
    }

    /**
     * 按访客 ID 查询访客资料。
     *
     * @param visitorId 访客 ID
     * @return 访客实体，不存在时返回空
     */
    public VisitorEntity findById(Long visitorId) {
        if (visitorId == null) {
            return null;
        }
        return visitorEntityMapper.selectById(visitorId);
    }

    /**
     * 创建访客资料短期授权 token。
     *
     * @param visitorId 访客 ID
     * @param portfolioId 作品集 ID
     * @param visitRecordId 访问汇总 ID
     * @return 短期 token
     */
    public String createProfileToken(Long visitorId, Long portfolioId, Long visitRecordId) {
        String token = randomToken();
        cacheService.put(
                profileTokenCacheKey(token),
                new VisitorProfileTokenContext(visitorId, portfolioId, visitRecordId),
                PROFILE_TOKEN_TTL
        );
        return token;
    }

    /**
     * 创建访客头像直传 COS 票据。
     *
     * @param portfolioId 作品集 ID
     * @param request 票据请求
     * @return 票据响应
     */
    public VisitorAvatarUploadTicketResponse createAvatarUploadTicket(
            Long portfolioId,
            VisitorAvatarUploadTicketRequest request
    ) {
        VisitorProfileTokenContext context = requireProfileContext(
                request == null ? null : request.getVisitorProfileToken(),
                portfolioId
        );
        String contentType = normalizeAvatarContentType(request == null ? null : request.getMimeType());
        validateAvatarUploadSize(request == null ? null : request.getFileSize());
        String objectKey = buildVisitorAvatarObjectKey(context.visitorId(), contentType, LocalDateTime.now());
        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                objectKey,
                contentType,
                VISITOR_AVATAR_MAX_BYTES,
                LocalDateTime.now().plusMinutes(AVATAR_TICKET_EXPIRE_MINUTES)
        );
        return buildAvatarUploadTicketResponse(ticket);
    }

    /**
     * 保存访客头像昵称。
     *
     * @param portfolioId 作品集 ID
     * @param request 保存请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveProfile(Long portfolioId, VisitorProfileUpdateRequest request) {
        VisitorProfileTokenContext context = requireProfileContext(
                request == null ? null : request.getVisitorProfileToken(),
                portfolioId
        );
        String nickname = trimRequiredAndCheckLength(
                request == null ? null : request.getNickname(),
                VisitorMessage.VISITOR_NICKNAME_REQUIRED_MESSAGE,
                VISITOR_NICKNAME_MAX_LENGTH,
                VisitorMessage.VISITOR_NICKNAME_LENGTH_MESSAGE
        );
        String avatarUrl = trimRequiredAndCheckLength(
                request == null ? null : request.getAvatarUrl(),
                VisitorMessage.VISITOR_AVATAR_REQUIRED_MESSAGE,
                VISITOR_AVATAR_URL_MAX_LENGTH,
                VisitorMessage.VISITOR_AVATAR_URL_LENGTH_MESSAGE
        );
        String objectKey = extractVisitorAvatarObjectKey(avatarUrl, context.visitorId());
        validateSavedAvatarObject(objectKey);
        VisitorEntity visitor = visitorEntityMapper.selectById(context.visitorId());
        if (visitor == null) {
            throw new BusinessException(VisitorMessage.VISITOR_PROFILE_SAVE_FAILED_MESSAGE);
        }
        visitor.setNickname(nickname);
        visitor.setAvatarUrl(avatarUrl);
        visitor.setProfileAuthorizedAt(LocalDateTime.now());
        if (visitorEntityMapper.updateById(visitor) <= 0) {
            throw new BusinessException(VisitorMessage.VISITOR_PROFILE_SAVE_FAILED_MESSAGE);
        }
    }

    /**
     * 构建头像对象键。
     *
     * @param visitorId 访客 ID
     * @param contentType MIME 类型
     * @param now 当前时间
     * @return COS 对象键
     */
    private String buildVisitorAvatarObjectKey(Long visitorId, String contentType, LocalDateTime now) {
        return VISITOR_AVATAR_FOLDER
                + "/"
                + VISITOR_AVATAR_FILE_PREFIX
                + "-"
                + visitorId
                + "-"
                + now.format(AVATAR_FILE_TIME_FORMATTER)
                + "-"
                + randomHex(AVATAR_RANDOM_LENGTH / 2)
                + "."
                + resolveAvatarExtension(contentType);
    }

    /**
     * 构建头像直传票据响应。
     *
     * @param ticket COS 直传票据
     * @return 响应
     */
    private VisitorAvatarUploadTicketResponse buildAvatarUploadTicketResponse(CosService.PostUploadTicket ticket) {
        VisitorAvatarUploadTicketResponse response = new VisitorAvatarUploadTicketResponse();
        response.setObjectKey(ticket.objectKey());
        response.setPublicUrl(cosService.publicUrl(ticket.objectKey()));
        response.setUploadUrl(ticket.uploadUrl());
        response.setContentType(ticket.contentType());
        response.setFormData(ticket.formData());
        response.setExpiresAt(ticket.expiresAt());
        response.setMaxBytes(ticket.maxBytes());
        return response;
    }

    /**
     * 读取并校验资料 token。
     *
     * @param token 原 token
     * @param portfolioId 当前作品集 ID
     * @return token 上下文
     */
    private VisitorProfileTokenContext requireProfileContext(String token, Long portfolioId) {
        String normalizedToken = normalizeRequired(token, VisitorMessage.VISITOR_PROFILE_TOKEN_INVALID_MESSAGE);
        VisitorProfileTokenContext context = cacheService.get(
                profileTokenCacheKey(normalizedToken),
                VisitorProfileTokenContext.class
        ).orElseThrow(() -> new BusinessException(VisitorMessage.VISITOR_PROFILE_TOKEN_INVALID_MESSAGE));
        if (!Objects.equals(context.portfolioId(), portfolioId)) {
            log.warn(
                    "访客资料 token 作品集不匹配: tokenPortfolioId={}, requestPortfolioId={}, visitorId={}",
                    context.portfolioId(),
                    portfolioId,
                    context.visitorId()
            );
            throw new BusinessException(VisitorMessage.VISITOR_PROFILE_TOKEN_INVALID_MESSAGE);
        }
        Long currentVisitorId = VisitorContextHolder.requireVisitorId();
        if (!Objects.equals(context.visitorId(), currentVisitorId)) {
            log.warn(
                    "访客资料 token 跨访客使用: tokenVisitorId={}, currentVisitorId={}, portfolioId={}",
                    context.visitorId(),
                    currentVisitorId,
                    portfolioId
            );
            throw new BusinessException(VisitorMessage.VISITOR_PROFILE_TOKEN_INVALID_MESSAGE);
        }
        return context;
    }

    /**
     * 规范化头像 MIME 类型。
     *
     * @param mimeType 原 MIME 类型
     * @return 规范化 MIME 类型
     */
    private String normalizeAvatarContentType(String mimeType) {
        String normalized = defaultString(mimeType).toLowerCase(Locale.ROOT);
        if (MIME_IMAGE_JPEG.equals(normalized) || MIME_IMAGE_JPG.equals(normalized)) {
            return MIME_IMAGE_JPEG;
        }
        if (MIME_IMAGE_PNG.equals(normalized) || MIME_IMAGE_WEBP.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(VisitorMessage.VISITOR_AVATAR_FORMAT_UNSUPPORTED_MESSAGE);
    }

    /**
     * 校验前端声明的头像大小。
     *
     * @param fileSize 文件字节数
     */
    private void validateAvatarUploadSize(Long fileSize) {
        long normalizedSize = fileSize == null ? 0L : fileSize;
        if (normalizedSize <= 0L) {
            throw new BusinessException(VisitorMessage.VISITOR_AVATAR_SIZE_INVALID_MESSAGE);
        }
        if (normalizedSize > VISITOR_AVATAR_MAX_BYTES) {
            throw new BusinessException(VisitorMessage.VISITOR_AVATAR_SIZE_LIMIT_MESSAGE);
        }
    }

    /**
     * 校验已上传头像对象头。
     *
     * @param objectKey COS 对象键
     */
    private void validateSavedAvatarObject(String objectKey) {
        CosService.ObjectHead objectHead;
        try {
            objectHead = cosService.headObject(objectKey);
        } catch (RuntimeException e) {
            throw new BusinessException(VisitorMessage.VISITOR_AVATAR_EXPIRED_MESSAGE);
        }
        normalizeAvatarContentType(objectHead.contentType());
        if (objectHead.contentLength() <= 0L || objectHead.contentLength() > VISITOR_AVATAR_MAX_BYTES) {
            throw new BusinessException(VisitorMessage.VISITOR_AVATAR_SIZE_LIMIT_MESSAGE);
        }
    }

    /**
     * 从公开 URL 提取访客头像对象键并校验归属。
     *
     * @param avatarUrl 头像公开 URL
     * @param visitorId 访客 ID
     * @return COS 对象键
     */
    private String extractVisitorAvatarObjectKey(String avatarUrl, Long visitorId) {
        String normalizedUrl = stripUrlSuffix(defaultString(avatarUrl));
        String keyPrefix = VISITOR_AVATAR_FOLDER + "/" + VISITOR_AVATAR_FILE_PREFIX + "-";
        int index = normalizedUrl.indexOf(keyPrefix);
        if (index < 0) {
            throw new BusinessException(VisitorMessage.VISITOR_AVATAR_OWNERSHIP_INVALID_MESSAGE);
        }
        String objectKey = normalizedUrl.substring(index);
        String objectKeyPattern = VISITOR_AVATAR_FOLDER
                + "/"
                + VISITOR_AVATAR_FILE_PREFIX
                + "-"
                + visitorId
                + "-\\d{14}-[a-f0-9]{8}\\.(jpg|png|webp)";
        if (!objectKey.matches(objectKeyPattern)) {
            throw new BusinessException(VisitorMessage.VISITOR_AVATAR_OWNERSHIP_INVALID_MESSAGE);
        }
        return objectKey;
    }

    /**
     * 去除 URL 查询参数和锚点。
     *
     * @param url 原始 URL
     * @return 去除后缀后的 URL
     */
    private String stripUrlSuffix(String url) {
        int queryIndex = url.indexOf('?');
        int fragmentIndex = url.indexOf('#');
        int endIndex = url.length();
        if (queryIndex >= 0) {
            endIndex = Math.min(endIndex, queryIndex);
        }
        if (fragmentIndex >= 0) {
            endIndex = Math.min(endIndex, fragmentIndex);
        }
        return url.substring(0, endIndex);
    }

    /**
     * 解析头像扩展名。
     *
     * @param contentType MIME 类型
     * @return 扩展名
     */
    private String resolveAvatarExtension(String contentType) {
        if (MIME_IMAGE_PNG.equals(contentType)) {
            return EXTENSION_PNG;
        }
        if (MIME_IMAGE_WEBP.equals(contentType)) {
            return EXTENSION_WEBP;
        }
        return EXTENSION_JPG;
    }

    /**
     * 生成随机十六进制字符串。
     *
     * @param byteLength 随机字节数
     * @return 十六进制字符串
     */
    private String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 生成资料 token。
     *
     * @return URL 安全 token
     */
    private String randomToken() {
        byte[] bytes = new byte[PROFILE_TOKEN_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 构建资料 token 缓存键。
     *
     * @param token 资料 token
     * @return 缓存键
     */
    private String profileTokenCacheKey(String token) {
        return PROFILE_TOKEN_CACHE_PREFIX + token;
    }

    /**
     * 校验必填文本。
     *
     * @param value 原值
     * @param message 错误提示
     * @return 去空格文本
     */
    private String normalizeRequired(String value, String message) {
        String normalized = defaultString(value);
        if (normalized.isBlank()) {
            throw new BusinessException(message);
        }
        return normalized.strip();
    }

    /**
     * 去空格并校验长度。
     *
     * @param value 原值
     * @param maxLength 最大长度
     * @param message 超长提示
     * @return 去空格文本
     */
    private String trimAndCheckLength(String value, int maxLength, String message) {
        String normalized = defaultString(value).strip();
        if (normalized.length() > maxLength) {
            throw new BusinessException(message);
        }
        return normalized;
    }

    /**
     * 校验必填文本并限制长度。
     *
     * @param value 原值
     * @param requiredMessage 必填提示
     * @param maxLength 最大长度
     * @param lengthMessage 超长提示
     * @return 去空格文本
     */
    private String trimRequiredAndCheckLength(
            String value,
            String requiredMessage,
            int maxLength,
            String lengthMessage
    ) {
        String normalized = trimAndCheckLength(value, maxLength, lengthMessage);
        if (normalized.isBlank()) {
            throw new BusinessException(requiredMessage);
        }
        return normalized;
    }

    /**
     * 规范化可空文本。
     *
     * @param value 原值
     * @return 去空格文本或空
     */
    private String normalizeNullable(String value) {
        return value == null ? null : value.strip();
    }

    /**
     * 判断文本是否有内容。
     *
     * @param value 原值
     * @return 是否有非空白内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 空值安全字符串。
     *
     * @param value 原字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 访客会话。
     *
     * @param visitor 访客实体
     * @param newVisitor 是否本次新建
     * @param anonymous 是否朋友圈单页匿名会话
     */
    public record VisitorSession(VisitorEntity visitor, boolean newVisitor, boolean anonymous) {

        /** 兼容既有普通微信访客会话构造方式。 */
        public VisitorSession(VisitorEntity visitor, boolean newVisitor) {
            this(visitor, newVisitor, false);
        }
    }

    /**
     * 访客资料 token 上下文。
     *
     * @param visitorId 访客 ID
     * @param portfolioId 作品集 ID
     * @param visitRecordId 访问汇总 ID
     */
    public record VisitorProfileTokenContext(Long visitorId, Long portfolioId, Long visitRecordId) {
    }
}
