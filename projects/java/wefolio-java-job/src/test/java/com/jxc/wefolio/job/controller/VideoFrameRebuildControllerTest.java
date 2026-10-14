package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.model.VideoFrameBackfillModels.RebuildRequest;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Submission;
import com.jxc.wefolio.job.service.VideoFrameBackfillExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 重建控制器只验证 HTTP 契约、可选参数委派和应用结果映射。 */
class VideoFrameRebuildControllerTest {
    /** 全局 context-path 负责 job-api 前缀，沿用运维头且允许无请求体。 */
    @Test
    void exposesManualRouteWithExistingSecretHeaderAndOptionalBody() throws Exception {
        var method = VideoFrameRebuildController.class.getMethod("rebuild", String.class, RebuildRequest.class);
        assertArrayEquals(new String[] { "/video-frames/rebuild" }, method.getAnnotation(PostMapping.class).value());
        var header = method.getParameters()[0].getAnnotation(RequestHeader.class);
        assertEquals("X-Admin-Point-Secret", header.value());
        assertFalse(header.required());
        assertFalse(method.getParameters()[1].getAnnotation(RequestBody.class).required());
    }

    /** 指定范围原样委派，控制器不重新判断作品范围或是否接受。 */
    @Test
    void delegatesRangeAndMapsAcceptedSubmission() {
        var service = mock(VideoFrameBackfillExecutionService.class);
        var controller = new VideoFrameRebuildController(service);
        var request = new RebuildRequest(8, 10L, 100L);
        var submission = new Submission(true, null, "RUNNING", "execution-1");
        when(service.rebuild("test-secret", request)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                VideoFrameBackfillExecutionService.Outcome.ACCEPTED, "accepted", submission));

        var response = controller.rebuild("test-secret", request);

        assertEquals(202, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertEquals("accepted", response.getBody().getMessage());
        assertSame(submission, response.getBody().getData());
        verify(service).rebuild("test-secret", request);
        verifyNoMoreInteractions(service);
    }

    /** 空请求原样传给用例，已有执行映射为 HTTP 200 且保留原执行编号。 */
    @Test
    void delegatesOptionalBodyAndMapsExistingExecution() {
        var service = mock(VideoFrameBackfillExecutionService.class);
        var controller = new VideoFrameRebuildController(service);
        var submission = new Submission(false, null, "RUNNING", "execution-1");
        when(service.rebuild("test-secret", null)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                VideoFrameBackfillExecutionService.Outcome.EXISTING, "existing", submission));

        var response = controller.rebuild("test-secret", null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertSame(submission, response.getBody().getData());
        verify(service).rebuild("test-secret", null);
        verifyNoMoreInteractions(service);
    }

    /** 鉴权、非法范围和资源占用都按用例结果映射，不能返回伪造的执行编号。 */
    @Test
    void mapsFailureOutcomesWithoutBusinessInference() {
        var service = mock(VideoFrameBackfillExecutionService.class);
        var controller = new VideoFrameRebuildController(service);
        var outcomes = new VideoFrameBackfillExecutionService.Outcome[] {
                VideoFrameBackfillExecutionService.Outcome.UNAUTHORIZED,
                VideoFrameBackfillExecutionService.Outcome.INVALID,
                VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE
        };
        int[] statuses = { 401, 400, 503 };
        for (int index = 0; index < outcomes.length; index++) {
            when(service.rebuild(null, null)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                    outcomes[index], "reason", null));
            var response = controller.rebuild(null, null);
            assertEquals(statuses[index], response.getStatusCode().value());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().isSuccess());
            assertEquals("reason", response.getBody().getMessage());
            assertNull(response.getBody().getData());
        }
        verify(service, times(3)).rebuild(null, null);
        verifyNoMoreInteractions(service);
    }
}
