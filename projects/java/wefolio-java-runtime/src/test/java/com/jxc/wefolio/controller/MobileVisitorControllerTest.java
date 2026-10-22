package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioResponse;
import com.jxc.wefolio.service.MobileVisitorApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 新安卓作品集路由契约 — 仅委派认证上下文及请求，原路由不在此 Controller 注册。 */
class MobileVisitorControllerTest {
    /** 三类路由均要求维护者令牌，HTTP 适配只委派参数和包装原响应。 */
    @Test
    void authenticatedRoutesDelegateToApplicationService() throws Exception {
        var service = mock(MobileVisitorApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MobileVisitorController(service)).build();
        when(service.openPersonal(eq(7L), eq("share"), any())).thenReturn(new VisitorPortfolioResponse());
        when(service.openTeam(eq(7L), eq("share"), any())).thenReturn(new VisitorTeamPortfolioResponse());
        when(service.openAdvancedTeam(eq(7L), eq("share"), any())).thenReturn(new VisitorTeamPortfolioResponse());
        AuthContextHolder.set(new AuthContext(7L, "test-token"));
        try {
            for (String type : new String[]{"portfolios", "team-portfolios", "advanced-team-portfolios"}) {
                mvc.perform(post("/api/mobile/visitor/" + type + "/share/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"WECHAT_SHARE_CARD\",\"idempotencyKey\":\"mobile-open\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            }
            verify(service).openPersonal(eq(7L), eq("share"), argThat(r -> "mobile-open".equals(r.getIdempotencyKey())));
            verify(service).openTeam(eq(7L), eq("share"), any());
            verify(service).openAdvancedTeam(eq(7L), eq("share"), any());
            assertThat(MobileVisitorController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        } finally {
            AuthContextHolder.clear();
        }
    }
}
