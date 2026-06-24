package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.WechatLoginRequest;
import com.jxc.wefolio.dto.WechatLoginResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MiniappAuthServiceTest {

    @Mock
    private UserEntityMapper userEntityMapper;

    @Mock
    private UserAuthEntityMapper userAuthEntityMapper;

    @Mock
    private WechatMiniappClient wechatMiniappClient;

    @Mock
    private CosService cosService;

    @Test
    void parsesDevelopmentBearerToken() {
        MiniappAuthService service = buildService();

        assertThat(service.resolveUserId("Bearer wf-dev-user-42")).isEqualTo(42L);
        assertThat(service.resolveUserId("Bearer invalid")).isNull();
        assertThat(service.resolveUserId("")).isNull();
    }

    @Test
    void rejectsBlankWechatCode() {
        MiniappAuthService service = buildService();
        WechatLoginRequest request = new WechatLoginRequest();
        request.setCode(" ");

        assertThatThrownBy(() -> service.loginByWechat(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信登录凭证不能为空");
    }

    @Test
    void wechatLoginCreatesUserAuthAfterCodeSessionExchange() {
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
        when(userEntityMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));

        MiniappAuthService service = buildService();
        WechatLoginRequest request = new WechatLoginRequest();
        request.setCode("wx-code");
        request.setNickname("林安");
        request.setAvatarUrl("https://example.com/avatar.jpg");
        request.setPhoneCode("phone-code");
        request.setPluginLoginCode("plugin-code");

        WechatLoginResponse response = service.loginByWechat(request);

        assertThat(response.getToken()).isEqualTo("wf-dev-user-11");
        assertThat(response.getUserId()).isEqualTo(11L);
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        verify(cosService).initUserStorage(any());
        verify(userEntityMapper).insert(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                "林安".equals(user.getNickname())
                        && "https://example.com/avatar.jpg".equals(user.getAvatarUrl())
                        && "+8613812348000".equals(user.getPhoneNumber())
                        && "86".equals(user.getPhoneCountryCode())
                        && "8000".equals(user.getPhoneLast4())
                        && user.getPhoneBoundAt() != null
                        && "openpid-abc".equals(user.getWechatOpenpid())
        ));
        verify(userAuthEntityMapper).insert(any(UserAuthEntity.class));
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
        when(userEntityMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return 1;
        }).when(userEntityMapper).insert(any(UserEntity.class));

        MiniappAuthService service = buildService();
        WechatLoginRequest request = new WechatLoginRequest();
        request.setCode("wx-code");
        request.setNickname("林安");
        request.setAvatarUrl("https://example.com/avatar.jpg");
        request.setPhoneCode("phone-code");

        WechatLoginResponse response = service.loginByWechat(request);

        assertThat(response.getToken()).isEqualTo("wf-dev-user-11");
        verify(wechatMiniappClient, never()).exchangePluginOpenpid(any());
        verify(cosService).initUserStorage(any());
        verify(userEntityMapper).insert(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                "林安".equals(user.getNickname())
                        && "https://example.com/avatar.jpg".equals(user.getAvatarUrl())
                        && user.getWechatOpenpid() == null
        ));
        verify(userAuthEntityMapper).insert(any(UserAuthEntity.class));
    }

    @Test
    void existingWechatUserLogsInWithoutRegistrationPhoneCode() {
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
        WechatLoginRequest request = new WechatLoginRequest();
        request.setCode("wx-code");

        WechatLoginResponse response = service.loginByWechat(request);

        assertThat(response.getToken()).isEqualTo("wf-dev-user-7");
        verify(wechatMiniappClient, never()).exchangePhoneCode(any());
        verify(wechatMiniappClient, never()).exchangePluginOpenpid(any());
        verify(userEntityMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void newWechatUserRequiresPhoneAuthorizationCode() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-123");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(null);

        MiniappAuthService service = buildService();
        WechatLoginRequest request = new WechatLoginRequest();
        request.setCode("wx-code");

        assertThatThrownBy(() -> service.loginByWechat(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先完成手机号授权注册");
    }

    private MiniappAuthService buildService() {
        MiniappAuthService service = new MiniappAuthService(
                userEntityMapper,
                userAuthEntityMapper,
                wechatMiniappClient,
                properties(),
                cosService
        );
        // 注入 self 代理，使 @Transactional 方法能通过自调用走 AOP
        service.self = service;
        return service;
    }

    private WechatMiniappProperties properties() {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("secret-for-hmac");
        return properties;
    }
}
