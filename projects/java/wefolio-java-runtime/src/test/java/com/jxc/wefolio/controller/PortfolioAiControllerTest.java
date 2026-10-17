package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.service.portfolioai.PortfolioAiApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI控制器只负责访问注解、参数委派和HTTP响应映射。 */
class PortfolioAiControllerTest {
    /** 原始JSON不经HTTP层宽松解析，四个入口保持固定路由和维护者约束。 */
    @Test void delegatesRawInputAndMapsAllEndpoints() throws Exception {
        PortfolioAiApplicationService service = mock(PortfolioAiApplicationService.class);
        PortfolioAiController controller = new PortfolioAiController(service);
        Map<String,Object> capabilities = Map.of("enabled", false), state = Map.of("taskId", "7");
        String raw = "{\"prompt\":\"甲\",\"prompt\":\"乙\"}";
        when(service.capabilities()).thenReturn(capabilities); when(service.submit(raw)).thenReturn(state);
        when(service.query(7L)).thenReturn(state); when(service.cancel(7L)).thenReturn(state);
        assertThat(controller.capabilities().getData()).isSameAs(capabilities);
        assertThat(controller.submit(raw).getData()).isSameAs(state);
        assertThat(controller.query(7L).getData()).isSameAs(state); assertThat(controller.cancel(7L).getData()).isSameAs(state);
        verify(service).submit(raw); verify(service).query(7L); verify(service).cancel(7L); verify(service).capabilities();
        assertThat(PortfolioAiController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(PortfolioAiController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/mine/portfolio-ai");
        assertThat(PortfolioAiController.class.getMethod("capabilities").getAnnotation(GetMapping.class).value()).containsExactly("/capabilities");
        assertThat(PortfolioAiController.class.getMethod("submit", String.class).getAnnotation(PostMapping.class).value()).containsExactly("/tasks");
        assertThat(PortfolioAiController.class.getMethod("submit", String.class).getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(PortfolioAiController.class.getMethod("query", Long.class).getAnnotation(GetMapping.class).value()).containsExactly("/tasks/{id}");
        assertThat(PortfolioAiController.class.getMethod("cancel", Long.class).getAnnotation(PostMapping.class).value()).containsExactly("/tasks/{id}/cancel");
    }
}
