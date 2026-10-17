package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 字体 HTTP 层仅绑定候选参数、认证身份并映射原 Response 包装。 */
@ExtendWith(MockitoExtension.class)
class PortfolioFontControllerTest {
    /** 目录应用服务替身。 */
    @Mock private PortfolioFontSources sources;
    /** 个人用例入口替身。 */
    @Mock private MinePortfolioService personal;
    /** 团队用例入口替身。 */
    @Mock private MineTeamPortfolioService team;
    /** 独立 MVC 路由适配器。 */
    private MockMvc mvc;

    /** 认证切面已验证身份时，Controller 从线程上下文取得用户。 */
    @BeforeEach void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "test-token"));
        mvc = MockMvcBuilders.standaloneSetup(new PortfolioFontController(sources, personal, team)).build();
    }
    /** 测试后释放请求身份，避免跨测试污染。 */
    @AfterEach void clearIdentity() { AuthContextHolder.clear(); }

    /** 目录路由保留维护者访问控制，并直接返回服务提供的能力状态。 */
    @Test void catalogDelegatesAndUsesMaintainerAccess() throws Exception {
        assertThat(PortfolioFontController.class.getAnnotation(MaintainerAccess.class)).isNotNull();
        when(sources.catalog()).thenReturn(Map.of("available", false, "fonts", List.of()));
        mvc.perform(get("/api/mine/portfolio-fonts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.available").value(false)).andExpect(jsonPath("$.data.fonts").isArray());
        verify(sources).catalog();
        verifyNoMoreInteractions(sources, personal, team);
    }

    /** 个人候选中的显式 null 和根表省略都由 DTO 保留，HTTP 层不推导字体业务。 */
    @Test void personalPrepareBindsThreeStateInputAndWrapsManifest() throws Exception {
        PortfolioFontManifestDto manifest = new PortfolioFontManifestDto();
        manifest.setPlanHash("personal-plan");
        when(personal.prepareFonts(eq(13L), any())).thenReturn(manifest);
        mvc.perform(post("/api/mine/portfolios/13/fonts/prepare").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientCapabilities":{"portfolioRemoteFont":1},"config":{"components":[
                         {"componentKey":"text","componentType":"TEXT_SECTION","config":{"fontId":null,"content":"候选"}}]}}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fontAssets.planHash").value("personal-plan"));
        var request = ArgumentCaptor.forClass(MinePortfolioDraftSaveRequest.class);
        verify(personal).prepareFonts(eq(13L), request.capture());
        assertThat(request.getValue().getConfig().isFontsProvided()).isFalse();
        assertThat(request.getValue().getConfig().getComponents().getFirst().getConfig()).containsEntry("fontId", null);
        verifyNoMoreInteractions(sources, personal, team);
    }

    /** 团队路径 ID、候选 DTO 和当前用户按原样委派，未隐式保存或发布。 */
    @Test void teamPrepareDelegatesAuthenticatedOwnerAndRootPresence() throws Exception {
        PortfolioFontManifestDto manifest = new PortfolioFontManifestDto();
        manifest.setPlanHash("team-plan");
        when(team.prepareFonts(eq(19L), any(), eq(7L))).thenReturn(manifest);
        mvc.perform(post("/api/mine/team-portfolios/19/fonts/prepare").contentType(MediaType.APPLICATION_JSON)
                .content("{\"config\":{\"fonts\":{},\"components\":[]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fontAssets.planHash").value("team-plan"));
        var request = ArgumentCaptor.forClass(TeamPortfolioDraftSaveRequest.class);
        verify(team).prepareFonts(eq(19L), request.capture(), eq(7L));
        assertThat(request.getValue().getConfig().isFontsProvided()).isTrue();
        verifyNoMoreInteractions(sources, personal, team);
    }
}
