package com.jxc.wefolio.job.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 帧链路旧用例显式关闭音频，转写集成用例另行覆盖结果和准入行为。 */
final class VideoAudioTestSupport {
    private VideoAudioTestSupport() { }

    /** 与生产关闭开关完全一致：不占收费预算，重建仍可失效旧派生内容。 */
    static VideoAudioTranscriptionService disabled() {
        VideoAudioTranscriptionService audio = mock(VideoAudioTranscriptionService.class);
        VideoAudioTranscriptionService.Preparation prepared = mock(VideoAudioTranscriptionService.Preparation.class);
        when(prepared.outcome()).thenReturn(VideoAudioTranscriptionService.Outcome.DISABLED);
        when(audio.newRun()).thenAnswer(ignored -> new VideoAudioTranscriptionService.RunBudget(100, 3600));
        when(audio.audioTimeoutSeconds()).thenReturn(180);
        when(audio.audioDeadline()).thenAnswer(ignored -> java.time.Instant.now().plusSeconds(180));
        when(audio.prepare(anyLong(), anyBoolean(), any(), any(), any())).thenReturn(prepared);
        when(audio.transcribe(anyLong(), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.DISABLED);
        when(audio.complete(any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.DISABLED);
        return audio;
    }
}
