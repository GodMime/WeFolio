package com.jxc.wefolio.service;

import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客登录令牌服务测试。
 */
@ExtendWith(MockitoExtension.class)
class VisitorAuthTokenServiceTest {

    /** 访客令牌有效期 */
    private static final long VISITOR_EXPIRES_IN_SECONDS = 30L * 24L * 60L * 60L;

    /** 访客令牌前缀 */
    private static final String VISITOR_TOKEN_PREFIX = "wf-visitor-v1.";

    /** 朋友圈单页匿名访客令牌前缀 */
    private static final String TIMELINE_ANONYMOUS_TOKEN_PREFIX = "wf-visitor-timeline-v1.";

    /** 测试令牌密钥 */
    private static final String TOKEN_SECRET = "visitor-token-secret-for-test";

    @Mock
    private VisitorEntityMapper visitorEntityMapper;

    @Test
    void issuesAndResolvesVisitorToken() {
        VisitorAuthTokenService service = buildService();
        VisitorEntity visitor = visitor(1024L, "visitor-key");
        when(visitorEntityMapper.selectById(1024L)).thenReturn(visitor);

        VisitorAuthTokenService.VisitorLoginToken issued = service.issueToken(1024L, "visitor-key");
        Optional<VisitorAuthTokenService.ResolvedVisitorToken> resolved =
                service.resolveAuthenticatedVisitor("Bearer " + issued.token());

        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.token()).startsWith("wf-visitor-v1.");
        assertThat(issued.expiresInSeconds()).isEqualTo(VISITOR_EXPIRES_IN_SECONDS);
        assertThat(resolved).get()
                .extracting(
                        VisitorAuthTokenService.ResolvedVisitorToken::visitorId,
                        VisitorAuthTokenService.ResolvedVisitorToken::visitorKey
                )
                .containsExactly(1024L, "visitor-key");
        assertThat(resolved.get().expiresAt()).isAfter(java.time.Instant.now().plusSeconds(VISITOR_EXPIRES_IN_SECONDS - 60));
        assertThat(resolved.get().anonymousScope()).isNull();
    }

    @Test
    void issuesAndResolvesPortfolioScopedTimelineAnonymousToken() {
        VisitorAuthTokenService service = buildService();
        VisitorEntity visitor = visitor(2048L, "timeline-visitor-key");
        when(visitorEntityMapper.selectById(2048L)).thenReturn(visitor);

        VisitorAuthTokenService.VisitorLoginToken issued = service.issueTimelineAnonymousToken(
                2048L,
                "timeline-visitor-key",
                "PERSONAL:PF001"
        );
        Optional<VisitorAuthTokenService.ResolvedVisitorToken> resolved =
                service.resolveAuthenticatedVisitor("Bearer " + issued.token());

        assertThat(issued.token()).startsWith(TIMELINE_ANONYMOUS_TOKEN_PREFIX);
        assertThat(resolved).get().satisfies(token -> {
            assertThat(token.visitorId()).isEqualTo(2048L);
            assertThat(token.visitorKey()).isEqualTo("timeline-visitor-key");
            assertThat(token.anonymousScope()).isEqualTo("PERSONAL:PF001");
        });
    }

    /**
     * 签发访客令牌时使用配置中的有效期秒数。
     */
    @Test
    void issuesVisitorTokenWithConfiguredExpiresInSeconds() {
        VisitorAuthTokenService service = buildService(3600L);
        VisitorEntity visitor = visitor(1024L, "visitor-key");
        when(visitorEntityMapper.selectById(1024L)).thenReturn(visitor);

        VisitorAuthTokenService.VisitorLoginToken issued = service.issueToken(1024L, "visitor-key");
        Optional<VisitorAuthTokenService.ResolvedVisitorToken> resolved =
                service.resolveAuthenticatedVisitor("Bearer " + issued.token());

        assertThat(issued.expiresInSeconds()).isEqualTo(3600L);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().expiresAt()).isAfter(Instant.now().plusSeconds(3500));
        assertThat(resolved.get().expiresAt()).isBefore(Instant.now().plusSeconds(3700));
    }

    @Test
    void rejectsMissingOrWrongPrefixAuthorizationWithoutQueryingDatabase() {
        VisitorAuthTokenService service = buildService();

        assertThat(service.resolveAuthenticatedVisitor(null)).isEmpty();
        assertThat(service.resolveAuthenticatedVisitor("Bearer wf-maintainer-v1.fake")).isEmpty();

        verify(visitorEntityMapper, never()).selectById(1024L);
    }

    @Test
    void rejectsTamperedVisitorToken() {
        VisitorAuthTokenService service = buildService();
        VisitorAuthTokenService.VisitorLoginToken issued = service.issueToken(1024L, "visitor-key");

        Optional<VisitorAuthTokenService.ResolvedVisitorToken> resolved =
                service.resolveAuthenticatedVisitor("Bearer " + issued.token() + "broken");

        assertThat(resolved).isEmpty();
        verify(visitorEntityMapper, never()).selectById(1024L);
    }

    @Test
    void rejectsExpiredVisitorTokenWithoutQueryingDatabase() {
        VisitorAuthTokenService service = buildService(60L);
        String expiredPayload = "1024:visitor-key:" + Instant.now().minusSeconds(120).getEpochSecond();
        String expiredToken = new EncryptedAuthTokenService(authTokenProperties(60L))
                .encryptPayload(VISITOR_TOKEN_PREFIX, expiredPayload);

        Optional<VisitorAuthTokenService.ResolvedVisitorToken> resolved =
                service.resolveAuthenticatedVisitor("Bearer " + expiredToken);

        assertThat(resolved).isEmpty();
        verify(visitorEntityMapper, never()).selectById(1024L);
    }

    @Test
    void rejectsTokenWhenVisitorRecordMissingOrKeyMismatch() {
        VisitorAuthTokenService missingVisitorService = buildService();
        VisitorAuthTokenService.VisitorLoginToken missingVisitorToken =
                missingVisitorService.issueToken(1024L, "visitor-key");
        when(visitorEntityMapper.selectById(1024L)).thenReturn(null);

        Optional<VisitorAuthTokenService.ResolvedVisitorToken> missingVisitor =
                missingVisitorService.resolveAuthenticatedVisitor("Bearer " + missingVisitorToken.token());

        VisitorAuthTokenService mismatchService = buildService();
        VisitorAuthTokenService.VisitorLoginToken mismatchToken =
                mismatchService.issueToken(2048L, "visitor-key");
        when(visitorEntityMapper.selectById(2048L)).thenReturn(visitor(2048L, "other-key"));

        Optional<VisitorAuthTokenService.ResolvedVisitorToken> keyMismatch =
                mismatchService.resolveAuthenticatedVisitor("Bearer " + mismatchToken.token());

        assertThat(missingVisitor).isEmpty();
        assertThat(keyMismatch).isEmpty();
    }

    @Test
    void resolvesVisitorFromCacheWithoutQueryingDatabaseAgain() {
        VisitorAuthTokenService service = buildService();
        VisitorEntity visitor = visitor(1024L, "visitor-key");
        when(visitorEntityMapper.selectById(1024L)).thenReturn(visitor);
        VisitorAuthTokenService.VisitorLoginToken issued = service.issueToken(1024L, "visitor-key");

        Optional<VisitorAuthTokenService.ResolvedVisitorToken> first =
                service.resolveAuthenticatedVisitor("Bearer " + issued.token());
        Optional<VisitorAuthTokenService.ResolvedVisitorToken> second =
                service.resolveAuthenticatedVisitor("Bearer " + issued.token());

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        verify(visitorEntityMapper, times(1)).selectById(1024L);
    }

    /**
     * 构建待测服务。
     *
     * @return 访客登录令牌服务
     */
    private VisitorAuthTokenService buildService() {
        return buildService(VISITOR_EXPIRES_IN_SECONDS);
    }

    /**
     * 构建待测服务。
     *
     * @param expiresInSeconds 访客令牌有效期秒数
     * @return 访客登录令牌服务
     */
    private VisitorAuthTokenService buildService(long expiresInSeconds) {
        AuthTokenProperties properties = authTokenProperties(expiresInSeconds);
        return new VisitorAuthTokenService(
                properties,
                visitorEntityMapper,
                new LocalCacheService(new LocalCacheProperties()),
                new EncryptedAuthTokenService(properties)
        );
    }

    /**
     * 构造测试认证令牌配置。
     *
     * @param expiresInSeconds 访客令牌有效期秒数
     * @return 认证令牌配置
     */
    private AuthTokenProperties authTokenProperties(long expiresInSeconds) {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret(TOKEN_SECRET);
        properties.setVisitorExpiresInSeconds(expiresInSeconds);
        return properties;
    }

    /**
     * 构造访客实体。
     *
     * @param id 访客 ID
     * @param visitorKey 访客稳定 key
     * @return 访客实体
     */
    private VisitorEntity visitor(Long id, String visitorKey) {
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(id);
        visitor.setVisitorKey(visitorKey);
        return visitor;
    }
}
