package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 真实HEAD分流、缺帧拒绝和同源旧资产复用，不访问实际COS。 */
class PortfolioAiMediaPlanServiceTest {
    /** 当前授权作品读取。 */
    private final WorkEntityMapper works = mock(WorkEntityMapper.class);
    /** HEAD替身，不下载视频。 */
    private final CosService cos = mock(CosService.class);
    /** 被测媒体计划。 */
    private final PortfolioAiMediaPlanService service = new PortfolioAiMediaPlanService(works, cos);
    /** 当前有效视频基础资料。 */
    private WorkEntity video(Integer duration) {
        WorkEntity work = new WorkEntity(); work.setId(9L); work.setUserId(7L); work.setMediaType("VIDEO");
        work.setMediaObjectKey("OWNER/work/video/test.mp4"); work.setMediaSha256("sha"); work.setDurationMs(duration);
        work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode()); work.setDeleted(0L);
        when(works.selectById(9L)).thenReturn(work); return work;
    }
    /** 只有已授权可绑定WORK进入计划。 */
    private ObjectNode facts() {
        ObjectNode facts = StrictAiJson.mapper().createObjectNode();
        facts.putArray("sources").addObject().put("sourceNo", 3).put("sourceId", "9").put("kind", "WORK").put("canBind", true)
                .put("mediaUrl", "https://public.example/work.mp4");
        return facts;
    }
    /** 当前完整旧帧允许原count，排序采用真实时间。 */
    private String manifest(WorkEntity work, int count) {
        ObjectNode manifest = StrictAiJson.mapper().createObjectNode().put("schemaVersion", "video-frames-v1")
                .put("sourceVersion", PortfolioAiMediaPlanService.fingerprint(work.getMediaObjectKey(), "ETAG", 30000000, "sha"))
                .put("frameCount", count);
        var frames = manifest.putArray("frames");
        for (int i = count; i > 0; i--) { frames.addObject().put("timeMs", i * 1000).put("objectKey", "OWNER/others/video-frames/9/source/policy/task/" + i + ".jpg")
                .put("bytes", 1000).put("width", 960).put("height", 540); }
        return manifest.toString();
    }
    /** 直视频必须真实大小、前端有效时长和预计token均合格。 */
    @Test void shortSmallVideoUsesHeadAndFreezesNoSignedOrPublicUrl() {
        video(15000); when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 20L*1024*1024, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        assertThat(facts.path("mediaPlan").path("entries").get(0).path("route").asText()).isEqualTo("VIDEO");
        assertThat(facts.toString()).doesNotContain("public.example", "q-signature");
        assertThat(facts.path("mediaPlan").path("entries").get(0).path("estimatedInputTokens").asLong()).isEqualTo(45000);
        verify(cos).headObjectVersion("OWNER/work/video/test.mp4");
    }
    /** 长视频缺清单必须明确拒绝，不临时截帧或文字降级。 */
    @Test void longOrUnknownDurationWithoutFramesFailsImmediately() {
        for (Integer duration : new Integer[]{15001, null, 0}) {
            video(duration); when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
            assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts())).hasMessageContaining("持久帧尚未准备完成");
        }
    }
    /** 旧12帧资产完整即可，长视频不为了当前N24重新取帧。 */
    @Test void largeVideoReusesCompleteOldTwelveFramesAndSortsRealTimes() {
        WorkEntity work = video(600000); work.setVideoFramesJson(manifest(work, 12));
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        var entry = facts.path("mediaPlan").path("entries").get(0);
        assertThat(entry.path("route").asText()).isEqualTo("FRAMES"); assertThat(entry.path("frames").size()).isEqualTo(12);
        assertThat(entry.path("frames").get(0).path("timeMs").asLong()).isEqualTo(1000);
    }
    /** 持久化的固定帧数上限应被 Runtime 接受，超合同上下界必须拒绝。 */
    @Test void frameCountBoundariesFollowTheSharedProducerContract() {
        WorkEntity work = video(600000);
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        for (int count : new int[]{PortfolioAiMediaContract.FRAME_MIN_COUNT, PortfolioAiMediaContract.FRAME_MAX_COUNT}) {
            work.setVideoFramesJson(manifest(work, count));
            ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
            assertThat(facts.path("mediaPlan").path("entries").get(0).path("frames").size()).isEqualTo(count);
        }
        for (int count : new int[]{PortfolioAiMediaContract.FRAME_MIN_COUNT - 1, PortfolioAiMediaContract.FRAME_MAX_COUNT + 1}) {
            work.setVideoFramesJson(manifest(work, count));
            assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts())).hasMessageContaining("持久帧");
        }
    }

    /** 非整数时间、异主目录与编码路径均不能冻结为可读帧。 */
    @Test void invalidFrameIdentityOrTimestampIsRejected() {
        WorkEntity work = video(600000); when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        for (String replacement : new String[]{"\"1000\"", "-1000"}) {
            work.setVideoFramesJson(manifest(work, 8).replace("\"timeMs\":1000", "\"timeMs\":" + replacement));
            assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts())).hasMessageContaining("持久帧");
        }
        work.setVideoFramesJson(manifest(work, 8).replace("OWNER/others/", "OTHER/others/"));
        assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts())).hasMessageContaining("持久帧");
    }
}
