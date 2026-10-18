package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.dto.MineWorkAiContentResponse;
import com.jxc.wefolio.service.MineWorkAuditApplicationService;
import com.jxc.wefolio.service.MineWorkService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI 内容查询 HTTP 合同，只验证委派、维护者认证标记和只读响应。 */
class MineWorkAiContentControllerTest {

    /** 无需签名的关键帧 CDN 地址。 */
    private static final String FRAME_CDN_URL = "https://cdn.test/frame.jpg";

    /** 已有响应包装和完整展示字段应原样返回，控制器不解析业务内容。 */
    @Test
    void getDelegatesToServiceAndPreservesResponseEnvelope() throws Exception {
        MineWorkService service = mock(MineWorkService.class);
        MineWorkController controller = new MineWorkController(service, mock(MineWorkAuditApplicationService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MineWorkAiContentResponse response = new MineWorkAiContentResponse();
        response.setHasContent(true);
        response.setSummary(new MineWorkAiContentResponse.Summary("现场摘要", List.of("观察"), List.of("局限")));
        response.setAudioText("现场音频文字");
        response.setFrames(List.of(new MineWorkAiContentResponse.Frame(1000, 960, 540, FRAME_CDN_URL)));
        when(service.getWorkAiContent(18L)).thenReturn(response);

        mvc.perform(get("/api/mine/works/18/ai-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.hasContent").value(true))
                .andExpect(jsonPath("$.data.summary.summary").value("现场摘要"))
                .andExpect(jsonPath("$.data.summary.observations[0]").value("观察"))
                .andExpect(jsonPath("$.data.summary.limitations[0]").value("局限"))
                .andExpect(jsonPath("$.data.audioText").value("现场音频文字"))
                .andExpect(jsonPath("$.data.frames[0].timestampMs").value(1000))
                .andExpect(jsonPath("$.data.frames[0].width").value(960))
                .andExpect(jsonPath("$.data.frames[0].height").value(540))
                .andExpect(jsonPath("$.data.frames[0].url").value(FRAME_CDN_URL));

        verify(service).getWorkAiContent(18L);
        assertThat(MineWorkController.class.getAnnotation(MaintainerAccess.class)).isNotNull();
    }

    /** AI 入口只有 GET，空内容保持成功响应和明确空值。 */
    @Test
    void emptyResultIsSuccessfulAndAiRouteHasNoWriteMethod() throws Exception {
        MineWorkService service = mock(MineWorkService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MineWorkController(
                service, mock(MineWorkAuditApplicationService.class))).build();
        when(service.getWorkAiContent(18L)).thenReturn(new MineWorkAiContentResponse());
        mvc.perform(get("/api/mine/works/18/ai-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hasContent").value(false))
                .andExpect(jsonPath("$.data.summary").isEmpty())
                .andExpect(jsonPath("$.data.audioText").isEmpty())
                .andExpect(jsonPath("$.data.frames").isEmpty());
        mvc.perform(post("/api/mine/works/18/ai-content"))
                .andExpect(status().isMethodNotAllowed());
    }
}
