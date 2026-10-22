package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.dto.MobileWechatLoginRequest;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.service.MobileWechatAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 安卓登录 HTTP 契约 — 仅验证参数委派、公开访问注解和原响应包装。 */
class MobileWechatAuthControllerTest {
    /** 关联接口必须用维护者认证覆盖类级公开访问规则，且只使用认证上下文中的用户编号。 */
    @Test
    void linkRequiresExistingMaintainerAndDelegatesCode() throws Exception {
        var service = mock(MobileWechatAuthService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MobileWechatAuthController(service)).build();
        assertThat(MobileWechatAuthController.class.getMethod("linkMiniapp", MobileWechatLoginRequest.class)
                .isAnnotationPresent(MaintainerAccess.class)).isTrue();
        AuthContextHolder.set(new AuthContext(7L, "test-token"));
        try {
            mvc.perform(post("/api/auth/mobile/miniapp-link").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"mini-code\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            verify(service).linkMiniapp(eq(7L), argThat(r -> "mini-code".equals(r.code())));
        } finally {
            AuthContextHolder.clear();
        }
    }

    /** 独立移动端路由使用既有成功响应结构，不修改小程序登录路由。 */
    @Test
    void delegatesCodeAndMapsSession() throws Exception {
        MobileWechatAuthService service = mock(MobileWechatAuthService.class);
        MaintainerWechatLoginResponse response = new MaintainerWechatLoginResponse();
        response.setToken("test-token"); response.setTokenType("Bearer"); response.setUserId(7L);
        when(service.login(argThat(request -> request.code().equals("code")))).thenReturn(response);
        var mvc = MockMvcBuilders.standaloneSetup(new MobileWechatAuthController(service)).build();
        mvc.perform(post("/api/auth/mobile/wechat-login").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("test-token"))
                .andExpect(jsonPath("$.data.userId").value(7));
        assertThat(MobileWechatAuthController.class.isAnnotationPresent(LoginAccess.class)).isTrue();
    }

    /** 配置接口只返回公开能力，不包含 AppSecret。 */
    @Test
    void exposesOnlyCapabilities() throws Exception {
        MobileWechatAuthService service = mock(MobileWechatAuthService.class);
        when(service.capabilities()).thenReturn(new MobileWechatAuthService.Capabilities(false, false, false));
        MockMvcBuilders.standaloneSetup(new MobileWechatAuthController(service)).build()
                .perform(get("/api/auth/mobile/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.registrationEnabled").value(false))
                .andExpect(jsonPath("$.data.paymentsEnabled").value(false))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist());
    }

}
