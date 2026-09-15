package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.aspect.AuthAspect;
import com.jxc.wefolio.dict.LoginTabDict;
import com.jxc.wefolio.dto.LoginPageConfigResponse;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.LoginPageConfigService;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 登录页配置 HTTP 契约测试，使用真实认证切面验证免登录访问。 */
@ExtendWith(MockitoExtension.class)
class LoginPageConfigControllerTest {

    /** 新增公开接口的固定契约路径。 */
    private static final String CONFIG_PATH = "/api/auth/login-page-config";

    /** 模拟客户端残留的失效令牌。 */
    private static final String EXPIRED_AUTHORIZATION = "Bearer expired-login-page-token";

    /** 登录页配置服务模拟，仅提供应用结果。 */
    @Mock
    private LoginPageConfigService loginPageConfigService;

    /** 维护者认证服务模拟，公开配置接口不应访问。 */
    @Mock
    private AuthTokenService authTokenService;

    /** 访客认证服务模拟，公开配置接口不应访问。 */
    @Mock
    private VisitorAuthTokenService visitorAuthTokenService;

    /** 包含真实认证切面的 HTTP 测试入口。 */
    private MockMvc mockMvc;

    /** 可验证真实认证逻辑已执行的切面。 */
    private AuthAspect authAspect;

    /** 为控制器创建认证代理，覆盖请求经过切面时的真实公开访问行为。 */
    @BeforeEach
    void setUp() {
        authAspect = spy(new AuthAspect(authTokenService, visitorAuthTokenService));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(
                new LoginPageConfigController(loginPageConfigService));
        proxyFactory.addAspect(authAspect);
        LoginPageConfigController controller = proxyFactory.getProxy();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 新接口应显式声明登录类访问权限，并使用约定的 GET 路径。 */
    @Test
    void endpointShouldDeclarePublicLoginAccess() throws NoSuchMethodException {
        Method method = LoginPageConfigController.class.getMethod("getConfig");

        assertThat(method.isAnnotationPresent(LoginAccess.class)).isTrue();
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(CONFIG_PATH);
    }

    /** 匿名请求应返回统一响应包装及服务结果，且禁止缓存默认标签配置。 */
    @ParameterizedTest
    @EnumSource(LoginTabDict.class)
    void anonymousRequestShouldReturnServiceConfiguration(LoginTabDict tab) throws Throwable {
        LoginPageConfigResponse serviceResponse = new LoginPageConfigResponse();
        serviceResponse.setDefaultTab(tab.getCode());
        when(loginPageConfigService.getConfig()).thenReturn(serviceResponse);

        mockMvc.perform(get(CONFIG_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue()))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.defaultTab").value(tab.getCode()));

        verify(authAspect).authenticate(any());
        verify(loginPageConfigService).getConfig();
        verifyNoInteractions(authTokenService, visitorAuthTokenService);
    }

    /** 客户端即使带有失效令牌，也应能获取登录前的公开展示配置。 */
    @Test
    void staleAuthorizationShouldNotRequireAuthentication() throws Throwable {
        LoginPageConfigResponse serviceResponse = new LoginPageConfigResponse();
        serviceResponse.setDefaultTab(LoginTabDict.EXPERIENCE.getCode());
        when(loginPageConfigService.getConfig()).thenReturn(serviceResponse);

        mockMvc.perform(get(CONFIG_PATH).header(HttpHeaders.AUTHORIZATION, EXPIRED_AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.defaultTab").value(LoginTabDict.EXPERIENCE.getCode()));

        verify(authAspect).authenticate(any());
        verify(loginPageConfigService).getConfig();
        verifyNoInteractions(authTokenService, visitorAuthTokenService);
    }
}
