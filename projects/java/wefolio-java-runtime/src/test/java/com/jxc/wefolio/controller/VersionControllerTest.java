package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.service.SystemStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 系统 Controller 仅委派应用入口，并保持原 HTTP 和 Response 契约。 */
class VersionControllerTest {
    /** 健康与版本接口返回服务层结果，不因字体 ready=false 改变整站状态。 */
    @Test void delegatesHealthAndVersionWithSystemAccess() throws Exception {
        var service = mock(SystemStatusService.class);
        when(service.health()).thenReturn(Map.of("status", "UP", "timestamp", "2026-09-22T10:00:00",
                "portfolioFonts", Map.of("enabled", true, "ready", false)));
        when(service.version()).thenReturn(Map.of("version", "test-version", "buildTime", "test-time"));
        var mvc = MockMvcBuilders.standaloneSetup(new VersionController(service)).build();
        assertThat(VersionController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.timestamp").value("2026-09-22T10:00:00"))
                .andExpect(jsonPath("$.data.portfolioFonts.ready").value(false));
        mvc.perform(get("/api/version")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("test-version"))
                .andExpect(jsonPath("$.data.buildTime").value("test-time"));
        verify(service).health(); verify(service).version();
    }
}
