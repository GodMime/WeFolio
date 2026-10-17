package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Controller 仅检查认证注解、原文委派和现有响应包装。 */
class PortfolioAiReferenceControllerTest {
    /** 三个入口委派应用服务，HTTP 层不解析凭证或文件事实。 */
    @Test void delegatesAllReferenceEndpoints() {
        PortfolioAiReferenceService service=mock(PortfolioAiReferenceService.class);PortfolioAiReferenceController controller=new PortfolioAiReferenceController(service);
        Map<String,Object> value=Map.of("value","ok");when(service.uploadTicket("upload")).thenReturn(value);when(service.complete("complete")).thenReturn(value);when(service.previewTicket("preview")).thenReturn(value);
        assertThat(PortfolioAiReferenceController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(controller.uploadTickets("upload").getData()).isSameAs(value);assertThat(controller.complete("complete").getData()).isSameAs(value);assertThat(controller.preview("preview").getData()).isSameAs(value);
        verify(service).uploadTicket("upload");verify(service).complete("complete");verify(service).previewTicket("preview");verifyNoMoreInteractions(service);
    }
    /** 必需媒体信息保持原文委派和原响应包装，Controller 不识别文件格式。 */
    @Test void delegatesClientMediaInfoWithoutChangingResponse() {
        PortfolioAiReferenceService service=mock(PortfolioAiReferenceService.class);
        PortfolioAiReferenceController controller=new PortfolioAiReferenceController(service);
        String request="{\"uploadToken\":\"signed\",\"mediaInfo\":{\"mimeType\":\"video/mp4\",\"codec\":\"hevc\",\"sizeBytes\":100,\"width\":64,\"height\":64,\"durationMs\":3000}}";
        Map<String,Object> value=Map.of("available",true);when(service.complete(request)).thenReturn(value);
        assertThat(controller.complete(request).getData()).isSameAs(value);verify(service).complete(request);verifyNoMoreInteractions(service);
    }
}
