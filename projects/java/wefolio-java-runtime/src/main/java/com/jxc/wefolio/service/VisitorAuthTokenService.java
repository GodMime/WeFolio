package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthorizationHeaderUtils;
import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.InvalidAuthTokenException;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import com.jxc.wefolio.message.MiniappAuthMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 访客登录令牌服务 — 负责签发、解析并缓存访客身份。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisitorAuthTokenService {

    /** 访客登录令牌前缀 */
    private static final String VISITOR_TOKEN_PREFIX = "wf-visitor-v1.";

    /** 访客登录令牌缓存 key 前缀 */
    private static final String CACHE_KEY_PREFIX = "visitor:auth-token:";

    /** 令牌类型 */
    private static final String TOKEN_TYPE = "Bearer";

    /** 访客令牌 payload 分隔符 */
    private static final String TOKEN_PAYLOAD_SEPARATOR = ":";

    /** 访客令牌 payload 字段数量 */
    private static final int TOKEN_PAYLOAD_PART_COUNT = 3;

    /** 访客令牌 payload 访客 ID 下标 */
    private static final int TOKEN_PAYLOAD_VISITOR_ID_INDEX = 0;

    /** 访客令牌 payload 访客 key 下标 */
    private static final int TOKEN_PAYLOAD_VISITOR_KEY_INDEX = 1;

    /** 访客令牌 payload 签发时间下标 */
    private static final int TOKEN_PAYLOAD_ISSUED_AT_INDEX = 2;

    /** 登录态解析缓存有效期 */
    private static final Duration AUTH_CACHE_TTL = Duration.ofMinutes(10);

    /** SHA-256 摘要算法 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 认证令牌配置 */
    private final AuthTokenProperties authTokenProperties;

    /** 访客 Mapper */
    private final VisitorEntityMapper visitorEntityMapper;

    /** 统一缓存服务 */
    private final CacheService cacheService;

    /** 加密认证令牌服务 */
    private final EncryptedAuthTokenService encryptedAuthTokenService;

    /**
     * 访客登录令牌。
     *
     * @param tokenType 令牌类型
     * @param token 加密令牌
     * @param expiresInSeconds 有效期秒数
     */
    public record VisitorLoginToken(String tokenType, String token, long expiresInSeconds) {
    }

    /**
     * 已解析的访客登录令牌。
     *
     * @param visitorId 访客 ID
     * @param visitorKey 访客稳定 key
     * @param expiresAt 令牌服务端过期时间
     */
    public record ResolvedVisitorToken(Long visitorId, String visitorKey, Instant expiresAt) {
    }

    /**
     * 签发访客登录令牌。
     *
     * @param visitorId 访客 ID
     * @param visitorKey 访客稳定 key
     * @return 访客登录令牌
     */
    public VisitorLoginToken issueToken(Long visitorId, String visitorKey) {
        validateVisitorIdentity(visitorId, visitorKey);
        String payload = visitorId
                + TOKEN_PAYLOAD_SEPARATOR
                + visitorKey
                + TOKEN_PAYLOAD_SEPARATOR
                + Instant.now().getEpochSecond();
        String token = encryptedAuthTokenService.encryptPayload(VISITOR_TOKEN_PREFIX, payload);
        return new VisitorLoginToken(TOKEN_TYPE, token, visitorExpiresInSeconds());
    }

    /**
     * 解析并校验访客登录令牌。
     *
     * @param authorization Authorization 请求头
     * @return 已认证访客令牌
     */
    public Optional<ResolvedVisitorToken> resolveAuthenticatedVisitor(String authorization) {
        String token = AuthorizationHeaderUtils.normalizeBearerToken(authorization);
        if (token.isBlank() || !token.startsWith(VISITOR_TOKEN_PREFIX)) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(token);
        Optional<ResolvedVisitorToken> cachedToken = cacheService.get(cacheKey, ResolvedVisitorToken.class);
        if (cachedToken.isPresent()) {
            return cachedToken;
        }

        ResolvedVisitorToken resolvedToken;
        try {
            resolvedToken = decryptVisitorToken(token);
        } catch (InvalidAuthTokenException e) {
            log.debug("访客令牌解析失败: {}", e.getMessage(), e);
            return Optional.empty();
        }
        if (!validateVisitorRecord(resolvedToken)) {
            return Optional.empty();
        }

        Duration cacheTtl = resolveCacheTtl(resolvedToken.expiresAt());
        if (cacheTtl.isZero() || cacheTtl.isNegative()) {
            return Optional.empty();
        }
        cacheService.put(cacheKey, resolvedToken, cacheTtl);
        return Optional.of(resolvedToken);
    }

    /**
     * 解密访客登录令牌。
     *
     * @param token 登录令牌
     * @return 登录态信息
     */
    private ResolvedVisitorToken decryptVisitorToken(String token) {
        try {
            String payload = encryptedAuthTokenService.decryptPayload(VISITOR_TOKEN_PREFIX, token);
            return parseTokenPayload(payload);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE, e);
        }
    }

    /**
     * 解析访客登录令牌 payload。
     *
     * @param payload 解密后的 payload
     * @return 登录态信息
     */
    private ResolvedVisitorToken parseTokenPayload(String payload) {
        String[] parts = payload.split(TOKEN_PAYLOAD_SEPARATOR, -1);
        if (parts.length != TOKEN_PAYLOAD_PART_COUNT) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
        }
        long visitorId;
        long issuedAtEpochSeconds;
        String visitorKey = parts[TOKEN_PAYLOAD_VISITOR_KEY_INDEX];
        try {
            visitorId = Long.parseLong(parts[TOKEN_PAYLOAD_VISITOR_ID_INDEX]);
            issuedAtEpochSeconds = Long.parseLong(parts[TOKEN_PAYLOAD_ISSUED_AT_INDEX]);
        } catch (NumberFormatException e) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE, e);
        }
        if (visitorId <= 0 || visitorKey == null || visitorKey.isBlank() || issuedAtEpochSeconds <= 0) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
        }
        Instant expiresAt = Instant.ofEpochSecond(issuedAtEpochSeconds).plusSeconds(visitorExpiresInSeconds());
        if (!expiresAt.isAfter(Instant.now())) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_EXPIRED_MESSAGE);
        }
        return new ResolvedVisitorToken(visitorId, visitorKey, expiresAt);
    }

    /**
     * 校验访客身份参数。
     *
     * @param visitorId 访客 ID
     * @param visitorKey 访客稳定 key
     */
    private void validateVisitorIdentity(Long visitorId, String visitorKey) {
        if (visitorId == null || visitorId <= 0 || visitorKey == null || visitorKey.isBlank()) {
            throw new BusinessException("访客身份异常");
        }
    }

    /**
     * 校验访客记录存在且 key 一致。
     *
     * @param resolvedToken 已解析访客令牌
     * @return 是否有效
     */
    private boolean validateVisitorRecord(ResolvedVisitorToken resolvedToken) {
        VisitorEntity visitor = visitorEntityMapper.selectById(resolvedToken.visitorId());
        return visitor != null && Objects.equals(visitor.getVisitorKey(), resolvedToken.visitorKey());
    }

    /**
     * 构建访客登录令牌缓存 key。
     *
     * @param token 标准化后的令牌
     * @return 缓存 key
     */
    private String buildCacheKey(String token) {
        return CACHE_KEY_PREFIX + sha256Hex(token);
    }

    /**
     * 计算登录态缓存有效期，不能超过令牌服务端剩余有效期。
     *
     * @param tokenExpiresAt 令牌服务端过期时间
     * @return 登录态缓存有效期
     */
    private Duration resolveCacheTtl(Instant tokenExpiresAt) {
        if (tokenExpiresAt == null) {
            return Duration.ZERO;
        }
        Duration remainingTtl = Duration.between(Instant.now(), tokenExpiresAt);
        if (remainingTtl.compareTo(AUTH_CACHE_TTL) < 0) {
            return remainingTtl;
        }
        return AUTH_CACHE_TTL;
    }

    /**
     * 读取访客令牌有效期配置。
     *
     * @return 访客令牌有效期秒数
     */
    private long visitorExpiresInSeconds() {
        long expiresInSeconds = authTokenProperties.getVisitorExpiresInSeconds();
        if (expiresInSeconds <= 0) {
            throw new BusinessException(MiniappAuthMessage.VISITOR_TOKEN_EXPIRES_INVALID_MESSAGE);
        }
        return expiresInSeconds;
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 原始文本
     * @return 摘要
     */
    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BusinessException("访客登录令牌摘要生成失败", e);
        }
    }
}
