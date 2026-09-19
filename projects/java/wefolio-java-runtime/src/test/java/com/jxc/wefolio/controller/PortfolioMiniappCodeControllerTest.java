package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dto.PortfolioMiniappCodeResponse;
import com.jxc.wefolio.service.miniappcode.PortfolioMiniappCodeApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 小程序码新增接口契约测试，只验证路由、身份委派和响应包装。 */
class PortfolioMiniappCodeControllerTest {
    /** 应用服务边界。 */
    private final PortfolioMiniappCodeApplicationService application = mock(PortfolioMiniappCodeApplicationService.class);
    /** 无需启动远端基础设施的 HTTP 测试入口。 */
    private MockMvc mvc;

    /** 建立当前维护者身份与独立控制器。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "test-token"));
        mvc = MockMvcBuilders.standaloneSetup(new PortfolioMiniappCodeController(application)).build();
    }

    /** 清理线程身份，避免测试之间污染。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 个人新增接口不需要请求体，且只透传路径和当前维护者身份。 */
    @Test
    void personalPostNeedsNoBodyAndWrapsImageContract() throws Exception {
        when(application.generatePersonal(11L, 7L)).thenReturn(
                new PortfolioMiniappCodeResponse("https://cdn.example.invalid/personal.png", "", "v1", PortfolioOwnerTypeDict.USER.getCode(), "name", "city", "title", 1080, 1440, 800, 120));
        mvc.perform(post("/api/mine/portfolios/11/miniapp-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.codeUrl").value("https://cdn.example.invalid/personal.png"))
                .andExpect(jsonPath("$.data.width").value(1080))
                .andExpect(jsonPath("$.data.height").value(1440))
                .andExpect(jsonPath("$.data.codeSize").value(800))
                .andExpect(jsonPath("$.data.avatarSize").value(120))
                .andExpect(jsonPath("$.data.avatarUrl").value(""))
                .andExpect(jsonPath("$.data.contentVersion").value("v1"))
                .andExpect(jsonPath("$.data.ownerType").value(PortfolioOwnerTypeDict.USER.getCode()))
                .andExpect(jsonPath("$.data.displayName").value("name"))
                .andExpect(jsonPath("$.data.subtitle").value("city"))
                .andExpect(jsonPath("$.data.shareTitle").value("title"))
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist());
        verify(application).generatePersonal(11L, 7L);
    }

    /** 团队新增接口保留相同响应契约，当前用户只能来自认证上下文。 */
    @Test
    void teamPostDelegatesAuthenticatedActor() throws Exception {
        when(application.generateTeam(21L, 7L)).thenReturn(
                new PortfolioMiniappCodeResponse("https://cdn.example.invalid/team.png", "", "v1", PortfolioOwnerTypeDict.USER.getCode(), "name", "city", "title", 1080, 1440, 800, 120));
        mvc.perform(post("/api/mine/team-portfolios/21/miniapp-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.codeUrl").value("https://cdn.example.invalid/team.png"))
                .andExpect(jsonPath("$.data.width").value(1080))
                .andExpect(jsonPath("$.data.height").value(1440))
                .andExpect(jsonPath("$.data.codeSize").value(800))
                .andExpect(jsonPath("$.data.avatarSize").value(120))
                .andExpect(jsonPath("$.data.avatarUrl").value(""))
                .andExpect(jsonPath("$.data.contentVersion").value("v1"))
                .andExpect(jsonPath("$.data.ownerType").value(PortfolioOwnerTypeDict.USER.getCode()))
                .andExpect(jsonPath("$.data.displayName").value("name"))
                .andExpect(jsonPath("$.data.subtitle").value("city"))
                .andExpect(jsonPath("$.data.shareTitle").value("title"))
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist());
        verify(application).generateTeam(21L, 7L);
    }

    /** 维护者注解让统一访问控制切面保护新增接口。 */
    @Test
    void controllerRequiresMaintainerAccess() {
        assertThat(PortfolioMiniappCodeController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
    }
}
