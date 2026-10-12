package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Request;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Submission;
import com.jxc.wefolio.job.service.VideoFrameBackfillExecutionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 控制器只验证用例委派与 HTTP 结果映射。 */
class VideoFrameBackfillControllerTest {
    @Test
    void delegatesOptionalRequestAndMapsAccepted() {
        var service = mock(VideoFrameBackfillExecutionService.class);
        var controller = new VideoFrameBackfillController(service);
        Request request = new Request(null,8);
        when(service.execute("test-secret",request)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                VideoFrameBackfillExecutionService.Outcome.ACCEPTED,"accepted",new Submission(true,null,"RUNNING","execution-1")));
        var response = controller.execute("test-secret",request);
        assertEquals(202,response.getStatusCode().value());
        verify(service).execute("test-secret",request); verifyNoMoreInteractions(service);
    }
    @Test
    void mapsAuthorizationAndExistingExecutionWithoutBusinessInference() {
        var service = mock(VideoFrameBackfillExecutionService.class);
        var controller = new VideoFrameBackfillController(service);
        when(service.execute(null,null)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                VideoFrameBackfillExecutionService.Outcome.UNAUTHORIZED,"invalid",null));
        assertEquals(401,controller.execute(null,null).getStatusCode().value());
        when(service.execute("test-secret",null)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                VideoFrameBackfillExecutionService.Outcome.EXISTING,"existing",new Submission(false,null,"RUNNING","execution-1")));
        assertEquals(200,controller.execute("test-secret",null).getStatusCode().value());
    }

    /** 清扫占用时沿用不可用映射，响应不得伪造补帧执行编号。 */
    @Test
    void mapsMaintenanceUnavailableWithoutClaimingExistingBackfill() {
        var service = mock(VideoFrameBackfillExecutionService.class);
        var controller = new VideoFrameBackfillController(service);
        when(service.execute("test-secret", null)).thenReturn(new VideoFrameBackfillExecutionService.Result(
                VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE, "清扫中，请重新提交", null));

        var response = controller.execute("test-secret", null);
        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNull(response.getBody().getData());
        verify(service).execute("test-secret", null);
        verifyNoMoreInteractions(service);
    }
}
