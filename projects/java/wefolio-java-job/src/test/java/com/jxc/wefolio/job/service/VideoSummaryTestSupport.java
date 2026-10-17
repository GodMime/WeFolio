package com.jxc.wefolio.job.service;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 旧帧与审核用例显式关闭原视频摘要；新用例再覆盖对应输出与准入。 */
final class VideoSummaryTestSupport {
    private VideoSummaryTestSupport() { }

    /** 关闭时不收费、不拿作品锁，强制重建仍由仓储清空旧摘要。 */
    static VideoSummaryService disabled() {
        VideoSummaryService summary = mock(VideoSummaryService.class);
        VideoSummaryService.Preparation preparation = mock(VideoSummaryService.Preparation.class);
        when(preparation.outcome()).thenReturn(VideoSummaryService.Outcome.DISABLED);
        when(summary.newRun()).thenAnswer(ignored -> new VideoSummaryService.RunBudget(100, 500));
        when(summary.summaryTimeoutSeconds()).thenReturn(180);
        when(summary.summaryDeadline()).thenAnswer(ignored -> Instant.now().plusSeconds(180));
        when(summary.prepare(anyLong(), anyBoolean(), any(), any(), any())).thenReturn(preparation);
        when(summary.summarize(anyLong(), anyString(), nullable(String.class), any(), any(), any()))
                .thenReturn(VideoSummaryService.Outcome.DISABLED);
        when(summary.summarize(anyLong(), nullable(Integer.class), any(), any(), any(), any()))
                .thenReturn(VideoSummaryService.Outcome.DISABLED);
        when(summary.complete(any(), any())).thenReturn(VideoSummaryService.Outcome.DISABLED);
        return summary;
    }
}
