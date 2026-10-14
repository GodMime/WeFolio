package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 被测冻结服务及其发布开关。 */
    private final PortfolioAiMediaPlanService service = new PortfolioAiMediaPlanService(works, cos, properties);
    /** 当前有效视频基础资料。 */
    private WorkEntity video(Integer duration) {
        WorkEntity work = new WorkEntity(); work.setId(9L); work.setUserId(7L); work.setMediaType("VIDEO");
        work.setMediaObjectKey("OWNER/work/video/test.mp4"); work.setMediaSha256("sha"); work.setDurationMs(duration);
        work.setAuditRound(1); work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode()); work.setDeleted(0L);
        when(works.selectPortfolioAiMediaSnapshot(9L)).thenReturn(work); return work;
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
        return manifest(work, count, 30000000, 960, 540);
    }
    /** 构造同源且具备真实帧尺寸的清单，源文件大小独立于图片分辨率。 */
    private String manifest(WorkEntity work, int count, long sourceBytes, int width, int height) {
        ObjectNode manifest = StrictAiJson.mapper().createObjectNode().put("schemaVersion", "video-frames-v1")
                .put("sourceVersion", PortfolioAiMediaPlanService.fingerprint(work.getMediaObjectKey(), "ETAG", sourceBytes, "sha"))
                .put("frameCount", count);
        var frames = manifest.putArray("frames");
        for (int i = count; i > 0; i--) { frames.addObject().put("timeMs", i * 1000).put("objectKey", "OWNER/others/video-frames/9/source/policy/task/" + i + ".jpg")
                .put("bytes", 1000).put("width", width).put("height", height); }
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

    /** 短视频已有同源审核清单时复用帧，避免再次把整个视频送入模型。 */
    @Test void shortVideoWithPersistentFramesUsesFrameSelection() {
        WorkEntity work = video(15000);
        work.setVideoFramesJson(manifest(work, 8, 1000, 960, 540));
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        var entry = facts.path("mediaPlan").path("entries").get(0);
        assertThat(entry.path("route").asText()).isEqualTo("FRAMES");
        assertThat(entry.path("frames").size()).isEqualTo(8);
        assertThat(entry.path(PortfolioAiFrameSelection.FIELD).path("frames").size()).isEqualTo(8);
        assertThat(entry.path("estimatedInputTokens").asLong()).isLessThan(45000);
    }

    /** 已有短视频清单的源身份失效时继续拒绝，不可改走整段视频绕过检查。 */
    @Test void shortVideoWithInvalidManifestDoesNotFallBackToDirectVideo() {
        WorkEntity work = video(15000);
        work.setVideoFramesJson(manifest(work, 8));
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts()))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.MEDIA_NOT_PREPARED);
    }

    /** 非空损坏清单沿用严格 JSON 异常语义，空字符串也不能当作无帧短视频。 */
    @Test void malformedShortVideoManifestRetainsParsingFailureWithoutDirectFallback() {
        WorkEntity work = video(15000);
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        for (String invalid : new String[]{"", " ", "{broken", "[]"}) {
            work.setVideoFramesJson(invalid);
            assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts()))
                    .isInstanceOf(AiProtocolException.class);
        }
    }

    /** 模型帧数按真实图片长边限制，完整审核清单和旧三字段冻结引用仍保留。 */
    @ParameterizedTest
    @CsvSource({"512,288,24", "288,512,24", "768,432,12", "432,768,12", "960,540,8", "540,960,8"})
    void resolutionBudgetPreservesFullAuditManifest(int width, int height, int selectedCount) {
        WorkEntity work = video(600000);
        String originalManifest = manifest(work, 24, 30000000, width, height);
        work.setVideoFramesJson(originalManifest);
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        var entry = facts.path("mediaPlan").path("entries").get(0);
        var selection = entry.path(PortfolioAiFrameSelection.FIELD);
        assertThat(entry.path("frames").size()).isEqualTo(24);
        assertThat(selection.path("frames").size()).isEqualTo(selectedCount);
        assertThat(selection.path("policyVersion").asText()).isEqualTo("resolution-budget-v1");
        assertThat(entry.path("estimatedInputTokens").asLong())
                .isEqualTo(selection.path("estimatedInputTokens").asLong()).isPositive()
                .isLessThan(24 * PortfolioAiMediaContract.FRAME_ESTIMATED_INPUT_TOKENS
                        + PortfolioAiMediaContract.FRAMES_INPUT_OVERHEAD_TOKENS);
        for (var frame : entry.path("frames")) {
            assertThat(frame.size()).isEqualTo(3);
            assertThat(frame.has("width")).isFalse();
            assertThat(frame.has("height")).isFalse();
        }
        for (var frame : selection.path("frames")) {
            assertThat(frame.path("width").asInt()).isEqualTo(width);
            assertThat(frame.path("height").asInt()).isEqualTo(height);
        }
        assertThat(work.getVideoFramesJson()).isEqualTo(originalManifest);
    }

    /** 模型仅选部分帧也不能放宽整个审核清单的尺寸校验。 */
    @Test void invalidFrameOutsideTheModelBudgetStillRejectsTheCompleteManifest() {
        WorkEntity work = video(600000);
        ObjectNode invalid = StrictAiJson.parseObject(manifest(work, 24), 128 * 1024, 12);
        ((ObjectNode) invalid.path("frames").get(12)).put("width", 961);
        work.setVideoFramesJson(invalid.toString());
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts()))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.MEDIA_NOT_PREPARED);
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

    /** 全局合同放宽前默认策略须已支持新边界，防止只改常量后接受资产却无法冻结任务。 */
    @Test void defaultSelectionMustSupportGlobalFrameContractBoundaries() {
        WorkEntity work = video(600000);
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        for (int count : new int[]{PortfolioAiMediaContract.FRAME_MIN_COUNT, PortfolioAiMediaContract.FRAME_MAX_COUNT}) {
            for (int[] size : new int[][]{{PortfolioAiMediaContract.FRAME_MAX_EDGE, 1},
                    {1, PortfolioAiMediaContract.FRAME_MAX_EDGE},
                    {PortfolioAiMediaContract.FRAME_MAX_EDGE, PortfolioAiMediaContract.FRAME_MAX_EDGE}}) {
                ObjectNode manifest = StrictAiJson.parseObject(manifest(work, count, 30000000, size[0], size[1]), 128 * 1024, 12);
                for (var frame : manifest.path("frames")) {
                    ((ObjectNode) frame).put("bytes", PortfolioAiMediaContract.FRAME_MAX_BYTES);
                }
                work.setVideoFramesJson(manifest.toString());
                ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
                var entry = facts.path("mediaPlan").path("entries").get(0);
                assertThat(entry.path("frames")).hasSize(count);
                assertThat(PortfolioAiFrameSelection.validateFrozen(manifest.path("frames"), entry.path(PortfolioAiFrameSelection.FIELD)))
                        .isEqualTo(entry.path("estimatedInputTokens").asLong());
                assertThat(work.getVideoFramesJson()).isEqualTo(manifest.toString());
            }
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

    /** v2 在同一查询快照中读取文字，短视频和持久帧均保留原路线。 */
    @ParameterizedTest
    @CsvSource({"false,VIDEO", "true,FRAMES"})
    void audioTextIsFrozenWithBothVideoRoutes(boolean withFrames, String route) {
        properties.setMediaAudioTextEnabled(true);
        WorkEntity work = video(15000); work.setVideoAudioText("主持口播😀，联系13812345678");
        if (withFrames) { work.setVideoFramesJson(manifest(work, 8, 1000, 960, 540)); }
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        var entry = facts.path("mediaPlan").path("entries").get(0);
        assertThat(facts.path("mediaPlan").path("version").asText()).isEqualTo(PortfolioAiMediaContract.PLAN_VERSION);
        assertThat(entry.path("route").asText()).isEqualTo(route);
        assertThat(entry.path(PortfolioAiVideoAudioContext.TEXT).asText()).contains("主持口播😀", "已隐藏联系方式").doesNotContain("13812345678");
        assertThat(entry.path(PortfolioAiVideoAudioContext.FULL_HASH).asText()).isEqualTo(PortfolioAiVideoAudioContext.fullHash(work.getVideoAudioText()));
        work.setVideoAudioText("较新文字");
        assertThat(entry.path(PortfolioAiVideoAudioContext.TEXT).asText()).doesNotContain("较新文字");
        verify(works).selectPortfolioAiMediaSnapshot(9L); verify(works, never()).selectById(any());
    }

    /** 开关关闭仍产出 v1，已有数据库文字也不会静默送到旧消费者。 */
    @Test void rolloutSwitchDefaultsToLegacyWithoutTranscript() {
        video(15000).setVideoAudioText("尚未开启消费者合同");
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        assertThat(facts.path("mediaPlan").path("version").asText()).isEqualTo(PortfolioAiMediaContract.LEGACY_PLAN_VERSION);
        assertThat(facts.toString()).doesNotContain(PortfolioAiVideoAudioContext.TEXT, "尚未开启消费者合同");
    }

    /** v2 无字不阻断媒体，图片不会附带数据库中不合法的音频残留。 */
    @Test void missingAndEmptyAudioTextDoNotChangeAvailabilityAndImageNeverProjectsText() {
        properties.setMediaAudioTextEnabled(true);
        for (String audio : new String[]{null, ""}) {
            WorkEntity work = video(15000); work.setVideoAudioText(audio);
            when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
            ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
            assertThat(facts.path("mediaPlan").path("entries").get(0).has(PortfolioAiVideoAudioContext.TEXT)).isFalse();
        }
        WorkEntity image = video(null); image.setMediaType("IMAGE"); image.setVideoAudioText("非法残留");
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        assertThat(facts.toString()).doesNotContain("非法残留", PortfolioAiVideoAudioContext.FULL_HASH);
    }
    /** v3 原视频摘要命中不依赖帧或视频直传规格，旧音频开关关闭也冻结已有音频。 */
    @Test void storedSummaryV3FreezesFactsWithoutFramesAndIncludesAudioAutomatically() {
        properties.setMediaStoredVideoSummaryEnabled(true);
        WorkEntity work = video(600000);
        work.setVideoAudioText("婚礼主持词");
        work.setVideoFramesJson("broken-unneeded-frames");
        String identity = PortfolioAiMediaPlanService.fingerprint(work.getMediaObjectKey(), "ETAG", 30000000, "sha");
        work.setVideoSummaryJson(storedSummary(identity));
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 30000000, "ETAG", 0));
        ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
        var entry = facts.path("mediaPlan").path("entries").get(0);
        assertThat(facts.path("mediaPlan").path("version").asText()).isEqualTo(PortfolioAiMediaContract.STORED_SUMMARY_PLAN_VERSION);
        assertThat(entry.path("route").asText()).isEqualTo(PortfolioAiMediaContract.ROUTE_STORED_VIDEO_SUMMARY);
        assertThat(entry.path("auditRound").asInt()).isEqualTo(1);
        assertThat(entry.path("estimatedInputTokens").asInt()).isZero();
        assertThat(entry.has("frames")).isFalse();
        assertThat(entry.path(PortfolioAiVideoAudioContext.TEXT).asText()).isEqualTo("婚礼主持词");
        assertThat(PortfolioAiStoredVideoSummary.frozenContent(entry).path("summary").asText()).isEqualTo("婚礼仪式");
        verify(cos, times(1)).headObjectVersion(anyString());
    }

    /** 缺失、损坏、过期源摘要统一走帧兜底，即使是旧合同可直传的短视频也不例外。 */
    @Test void v3MissingOrStaleSummaryAlwaysUsesFrozenFramesAndAudio() {
        properties.setMediaStoredVideoSummaryEnabled(true);
        WorkEntity work = video(10000);
        work.setVideoAudioText("已有转写");
        work.setVideoFramesJson(manifest(work, 8, 1000, 960, 540));
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        for (String summary : new String[]{null, "", "null", "{broken", storedSummary("f".repeat(64))}) {
            work.setVideoSummaryJson(summary);
            ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
            var entry = facts.path("mediaPlan").path("entries").get(0);
            assertThat(entry.path("route").asText()).isEqualTo("FRAMES");
            assertThat(entry.has(PortfolioAiStoredVideoSummary.FIELD)).isFalse();
            assertThat(entry.path(PortfolioAiVideoAudioContext.TEXT).asText()).isEqualTo("已有转写");
            assertThat(entry.path(PortfolioAiFrameSelection.FIELD).path("frames")).hasSize(8);
        }
    }

    /** v3 没有摘要又没有帧时沿用准备失败，不能绕过新合同直传原短视频。 */
    @Test void v3MissingSummaryAndFramesRejectsEvenSmallShortVideo() {
        properties.setMediaStoredVideoSummaryEnabled(true); video(10000);
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        assertThatThrownBy(() -> service.freeze(StrictAiJson.mapper().createObjectNode(), facts()))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.MEDIA_NOT_PREPARED);
    }

    /** 关闭新开关不改变 v1/v2 直传短视频与旧帧行为。 */
    @Test void legacyVersionDoesNotAccidentallyConsumeNewStoredSummary() {
        WorkEntity work = video(10000);
        work.setVideoSummaryJson(storedSummary(PortfolioAiMediaPlanService.fingerprint(work.getMediaObjectKey(), "ETAG", 1000, "sha")));
        when(cos.headObjectVersion(anyString())).thenReturn(new CosService.VersionedObjectHead("video/mp4", 1000, "ETAG", 0));
        for (boolean audio : new boolean[]{false, true}) {
            properties.setMediaAudioTextEnabled(audio);
            ObjectNode facts = facts(); service.freeze(StrictAiJson.mapper().createObjectNode(), facts);
            var entry = facts.path("mediaPlan").path("entries").get(0);
            assertThat(entry.path("route").asText()).isEqualTo("VIDEO");
            assertThat(entry.has(PortfolioAiStoredVideoSummary.FIELD)).isFalse();
        }
    }

    /** 构造有效原视频理解包装，不依赖临时签名、帧或音频状态。 */
    private String storedSummary(String identity) {
        ObjectNode content = StrictAiJson.mapper().createObjectNode().put("summary", "婚礼仪式");
        content.putArray("observations"); content.putArray("limitations").add("可见画面");
        return PortfolioAiStoredVideoSummary.create(identity, "a".repeat(64), "glm-5.3-flash", "direct-v1",
                Instant.parse("2020-01-01T00:00:00Z"), content).toString();
    }

}
