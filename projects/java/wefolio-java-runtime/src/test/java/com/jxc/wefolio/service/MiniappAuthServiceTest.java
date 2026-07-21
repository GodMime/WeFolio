package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.MaintainerWechatLoginRequest;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.mapper.ReferralRelationEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.common.UniqueCodeGenerator;
import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.config.RegistrationPointProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小程序登录服务测试 — 覆盖维护者微信登录、注册冲突和令牌解析。
 */
@ExtendWith(MockitoExtension.class)
class MiniappAuthServiceTest {

    /** 测试用维护者令牌前缀 */
    private static final String MAINTAINER_TOKEN_PREFIX = "wf-maintainer-v1.";

    /** 测试用令牌 payload 分隔符 */
    private static final String TOKEN_PAYLOAD_SEPARATOR = ":";

    /** 维护者令牌有效期 */
    private static final long MAINTAINER_EXPIRES_IN_SECONDS = 30L * 24L * 60L * 60L;

    @Mock
    private UserEntityMapper userEntityMapper;

    @Mock
    private UserAuthEntityMapper userAuthEntityMapper;

    @Mock
    private ReferralRelationEntityMapper referralRelationEntityMapper;

    @Mock
    private WechatMiniappClient wechatMiniappClient;

    @Mock
    private CosService cosService;

    @Mock
    private CosProperties cosProperties;

    @Mock
    private PointService pointService;

    @Mock
    private UniqueCodeGenerator uniqueCodeGenerator;

    @Test
    void rejectsLegacyPredictableBearerToken() {
        MiniappAuthService service = buildService();

        assertThat(service.resolveUserId("Bearer wf-dev-user-42")).isNull();
        assertThat(service.resolveUserId("Bearer invalid")).isNull();
        assertThat(service.resolveUserId("")).isNull();
    }

    @Test
    void rejectsExpiredMaintainerTokenOnServer() {
        MiniappAuthService service = buildService();
        String token = buildMaintainerToken(7L,
                Instant.now().minusSeconds(MAINTAINER_EXPIRES_IN_SECONDS + 1));

        assertThatThrownBy(() -> service.resolveUserId("Bearer " + token))
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录令牌已过期，请重新登录");
    }

    @Test
    void raisesBusinessErrorForBrokenMaintainerToken() {
        MiniappAuthService service = buildService();

        assertThatThrownBy(() -> service.resolveUserId("Bearer " + MAINTAINER_TOKEN_PREFIX + "broken"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录令牌解析失败，请重新登录");
    }

    @Test
    void rejectsBlankWechatCode() {
        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode(" ");

        assertThatThrownBy(() -> service.loginMaintainerByWechat(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信登录凭证不能为空");
    }

    @Test
    void maintainerWechatLoginCreatesUserAuthAfterCodeSessionExchange() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        session.setUnionid("union-456");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        WechatPhoneNumberResponse.PhoneInfo phoneInfo = new WechatPhoneNumberResponse.PhoneInfo();
        phoneInfo.setPhoneNumber("+8613812348000");
        phoneInfo.setPurePhoneNumber("13812348000");
        phoneInfo.setCountryCode("86");
        when(wechatMiniappClient.exchangePhoneCode("phone-code")).thenReturn(phoneInfo);
        when(wechatMiniappClient.exchangePluginOpenpid("plugin-code")).thenReturn("openpid-abc");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);
        when(uniqueCodeGenerator.generate(eq(UniqueCodeGenerator.USER_PREFIX), any())).thenReturn("WFTEST0001");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));
        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");
        request.setNickname("林安");
        request.setAvatarUrl("https://example.com/avatar.jpg");
        request.setPhoneCode("phone-code");
        request.setPluginLoginCode("plugin-code");

        MaintainerWechatLoginResponse response = service.loginMaintainerByWechat(request);

        assertThat(response.getToken()).isNotEqualTo("wf-dev-user-11");
        assertThat(service.resolveUserId("Bearer " + response.getToken())).isEqualTo(11L);
        assertThat(response.getUserId()).isEqualTo(11L);
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        verify(cosService).initUserStorage(any());
        verify(pointService).ensureAccount(11L);
        verify(cosService, never()).uploadFromUrl(any(), any());
        verify(userEntityMapper).insert(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                "林安".equals(user.getNickname())
                        && "".equals(user.getAvatarUrl())
                        && "+8613812348000".equals(user.getPhoneNumber())
                        && "86".equals(user.getPhoneCountryCode())
                        && "8000".equals(user.getPhoneLast4())
                        && user.getPhoneBoundAt() != null
                        && "openpid-abc".equals(user.getWechatOpenpid())
        ));
        ArgumentCaptor<UserAuthEntity> authCaptor = ArgumentCaptor.forClass(UserAuthEntity.class);
        verify(userAuthEntityMapper).insert(authCaptor.capture());
        assertThat(authCaptor.getValue().getOpenId()).isEqualTo("openid-123");
    }

    @Test
    void wechatRegistrationAllowsMissingPluginOpenpidCodeWhenRuntimeDoesNotSupportIt() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        WechatPhoneNumberResponse.PhoneInfo phoneInfo = new WechatPhoneNumberResponse.PhoneInfo();
        phoneInfo.setPhoneNumber("+8613812348000");
        phoneInfo.setPurePhoneNumber("13812348000");
        phoneInfo.setCountryCode("86");
        when(wechatMiniappClient.exchangePhoneCode("phone-code")).thenReturn(phoneInfo);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);
        when(uniqueCodeGenerator.generate(eq(UniqueCodeGenerator.USER_PREFIX), any())).thenReturn("WFTEST0001");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));
        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");
        request.setNickname("林安");
        request.setAvatarUrl("https://example.com/avatar.jpg");
        request.setPhoneCode("phone-code");

        MaintainerWechatLoginResponse response = service.loginMaintainerByWechat(request);

        assertThat(response.getToken()).isNotEqualTo("wf-dev-user-11");
        assertThat(service.resolveUserId("Bearer " + response.getToken())).isEqualTo(11L);
        verify(wechatMiniappClient, never()).exchangePluginOpenpid(any());
        verify(cosService).initUserStorage(any());
        verify(pointService).ensureAccount(11L);
        verify(cosService, never()).uploadFromUrl(any(), any());
        verify(userEntityMapper).insert(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                "林安".equals(user.getNickname())
                        && "".equals(user.getAvatarUrl())
                        && user.getWechatOpenpid() == null
        ));
        ArgumentCaptor<UserAuthEntity> authCaptor = ArgumentCaptor.forClass(UserAuthEntity.class);
        verify(userAuthEntityMapper).insert(authCaptor.capture());
        assertThat(authCaptor.getValue().getOpenId()).isEqualTo("openid-123");
    }

    @Test
    void wechatRegistrationIgnoresLocalTemporaryAvatarPath() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        WechatPhoneNumberResponse.PhoneInfo phoneInfo = new WechatPhoneNumberResponse.PhoneInfo();
        phoneInfo.setPhoneNumber("+8613812348000");
        phoneInfo.setPurePhoneNumber("13812348000");
        phoneInfo.setCountryCode("86");
        when(wechatMiniappClient.exchangePhoneCode("phone-code")).thenReturn(phoneInfo);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);
        when(uniqueCodeGenerator.generate(eq(UniqueCodeGenerator.USER_PREFIX), any())).thenReturn("WFTEST0001");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));

        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");
        request.setNickname("林安");
        request.setAvatarUrl("wxfile://tmp_avatar.jpg");
        request.setPhoneCode("phone-code");

        MaintainerWechatLoginResponse response = service.loginMaintainerByWechat(request);

        assertThat(response.getToken()).isNotEqualTo("wf-dev-user-11");
        assertThat(service.resolveUserId("Bearer " + response.getToken())).isEqualTo(11L);
        verify(cosService).initUserStorage(any());
        verify(cosService, never()).uploadFromUrl(any(), any());
        verify(pointService).ensureAccount(11L);
        verify(userEntityMapper).insert(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                "林安".equals(user.getNickname())
                        && "".equals(user.getAvatarUrl())
                        && "+8613812348000".equals(user.getPhoneNumber())
        ));
    }

    @Test
    void concurrentWechatRegistrationReusesExistingUserAfterPhoneUniqueConflict() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-new");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        WechatPhoneNumberResponse.PhoneInfo phoneInfo = new WechatPhoneNumberResponse.PhoneInfo();
        phoneInfo.setPhoneNumber("+8613812348000");
        phoneInfo.setPurePhoneNumber("13812348000");
        phoneInfo.setCountryCode("86");
        when(wechatMiniappClient.exchangePhoneCode("phone-code")).thenReturn(phoneInfo);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);
        when(uniqueCodeGenerator.generate(eq(UniqueCodeGenerator.USER_PREFIX), any())).thenReturn("WFTEST0002");
        doAnswer(invocation -> {
            throw new DuplicateKeyException("uk_user_phone_number");
        }).when(userEntityMapper).insert(any(UserEntity.class));

        UserEntity existingUser = new UserEntity();
        existingUser.setId(22L);
        existingUser.setUniqueCode("WFOLD0001");
        existingUser.setNickname("旧用户");
        existingUser.setAvatarUrl("");
        existingUser.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userEntityMapper.selectOne(any())).thenReturn(null, existingUser);

        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");
        request.setNickname("林安");
        request.setAvatarUrl("wxfile://tmp_avatar.jpg");
        request.setPhoneCode("phone-code");

        MaintainerWechatLoginResponse response = service.loginMaintainerByWechat(request);

        assertThat(response.getUserId()).isEqualTo(22L);
        assertThat(service.resolveUserId("Bearer " + response.getToken())).isEqualTo(22L);
        verify(cosService).initUserStorage("WFTEST0002");
        verify(userEntityMapper).updateById(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                Long.valueOf(22L).equals(user.getId())
                        && "林安".equals(user.getNickname())
                        && "+8613812348000".equals(user.getPhoneNumber())
        ));
        ArgumentCaptor<UserAuthEntity> authCaptor = ArgumentCaptor.forClass(UserAuthEntity.class);
        verify(userAuthEntityMapper).insert(authCaptor.capture());
        assertThat(authCaptor.getValue().getOpenId()).isEqualTo("openid-new");
    }

    @Test
    void existingWechatUserLogsInWithoutRegistrationPhoneCodeAndBackfillsOpenIdWhenMissing() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);

        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(3L);
        auth.setUserId(7L);
        auth.setStatus("ACTIVE");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);

        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");

        MaintainerWechatLoginResponse response = service.loginMaintainerByWechat(request);

        assertThat(response.getToken()).isNotEqualTo("wf-dev-user-7");
        assertThat(service.resolveUserId("Bearer " + response.getToken())).isEqualTo(7L);
        assertThat(auth.getOpenId()).isEqualTo("openid-123");
        verify(wechatMiniappClient, never()).exchangePhoneCode(any());
        verify(wechatMiniappClient, never()).exchangePluginOpenpid(any());
        verify(userEntityMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void existingWechatUserLoginShouldNotOverwriteStoredOpenId() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-fresh");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);

        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(3L);
        auth.setUserId(7L);
        auth.setStatus("ACTIVE");
        auth.setOpenId("openid-existing");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);

        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");

        service.loginMaintainerByWechat(request);

        assertThat(auth.getOpenId()).isEqualTo("openid-existing");
        verify(wechatMiniappClient, never()).exchangePhoneCode(any());
    }

    @Test
    void appSecretRotationDoesNotInvalidateIssuedMaintainerToken() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);

        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(3L);
        auth.setUserId(7L);
        auth.setStatus("ACTIVE");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);

        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MiniappAuthService service = buildService(properties("old-wechat-secret"), authTokenProperties("stable-token-secret"));
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");

        MaintainerWechatLoginResponse response = service.loginMaintainerByWechat(request);
        MiniappAuthService rotatedWechatSecretService =
                buildService(properties("new-wechat-secret"), authTokenProperties("stable-token-secret"));

        assertThat(rotatedWechatSecretService.resolveUserId("Bearer " + response.getToken())).isEqualTo(7L);
    }

    @Test
    void newWechatUserRequiresPhoneAuthorizationCode() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);

        MiniappAuthService service = buildService();
        MaintainerWechatLoginRequest request = new MaintainerWechatLoginRequest();
        request.setCode("wx-code");

        assertThatThrownBy(() -> service.loginMaintainerByWechat(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先完成手机号授权注册");
    }

    private MiniappAuthService buildService() {
        return buildService(properties(), authTokenProperties("secret-for-token"));
    }

    /**
     * 构造被测服务。
     *
     * @param wechatProperties 微信小程序配置
     * @param authTokenProperties 认证令牌配置
     * @return 被测服务
     */
    private MiniappAuthService buildService(
            WechatMiniappProperties wechatProperties,
            AuthTokenProperties authTokenProperties
    ) {
        return new MiniappAuthService(
                userEntityMapper,
                userAuthEntityMapper,
                wechatMiniappClient,
                wechatProperties,
                new EncryptedAuthTokenService(authTokenProperties),
                cosService,
                cosProperties,
                new UserRegistrationService(
                        userEntityMapper,
                        userAuthEntityMapper,
                        referralRelationEntityMapper,
                        pointService,
                        registrationPointProperties()
                ),
                uniqueCodeGenerator,
                null,
                new WechatVirtualPaymentProperties()
        );
    }

    private WechatMiniappProperties properties() {
        return properties("secret-for-hmac");
    }

    private WechatMiniappProperties properties(String appSecret) {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret(appSecret);
        return properties;
    }

    /**
     * 构造测试认证令牌配置。
     *
     * @param secret 令牌密钥
     * @return 认证令牌配置
     */
    private AuthTokenProperties authTokenProperties(String secret) {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret(secret);
        return properties;
    }

    /**
     * 构造测试注册积分配置。
     *
     * @return 注册积分配置
     */
    private RegistrationPointProperties registrationPointProperties() {
        RegistrationPointProperties properties = new RegistrationPointProperties();
        properties.setNewUserGiftPoints(500L);
        properties.setReferralGiftPoints(500L);
        return properties;
    }

    /**
     * 构造指定签发时间的维护者令牌。
     *
     * @param userId 用户 ID
     * @param issuedAt 签发时间
     * @return 维护者登录令牌
     */
    private String buildMaintainerToken(Long userId, Instant issuedAt) {
        String payload = userId + TOKEN_PAYLOAD_SEPARATOR + issuedAt.getEpochSecond();
        return new EncryptedAuthTokenService(authTokenProperties("secret-for-token"))
                .encryptPayload(MAINTAINER_TOKEN_PREFIX, payload);
    }
}
