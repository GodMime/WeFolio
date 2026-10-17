package com.jxc.wefolio.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dto.MineWorkAiContentResponse;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.MineWorkMessage;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import com.jxc.wefolio.service.portfolioai.PortfolioAiStoredVideoSummary;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 视频 AI 只读查询测试，覆盖归属、独立空态、严格源合同及私有帧地址。 */
@ExtendWith(MockitoExtension.class)
class MineWorkAiContentServiceTest {

    /** 当前用户视频对象，帧目录必须来自同一作品。 */
    private static final String SOURCE_KEY = "WFOWNER/work/video/sample.mp4";
    /** 本地测试源对象身份。 */
    private static final String SOURCE_ETAG = "source-etag";
    /** 本地测试源对象大小。 */
    private static final long SOURCE_BYTES = 1024L;
    /** 帧对象属于当前用户和当前作品的私有目录。 */
    private static final String FRAME_PREFIX = "WFOWNER/others/video-frames/18/";
    /** 作品读取器模拟。 */
    @Mock
    private WorkEntityMapper works;
    /** COS 元数据和签名模拟。 */
    @Mock
    private CosService cos;
    /** 查询入口，未使用的业务依赖不参与本用例。 */
    @InjectMocks
    private MineWorkService service;

    /** 设置当前维护者。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "test-token"));
    }

    /** 防止认证上下文污染其它测试。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 本人不存在、已删除和他人作品均不能读取显式 AI 快照或签名。 */
    @Test
    void mustAuthorizeBeforeReadingAiFields() {
        assertThatThrownBy(() -> service.getWorkAiContent(18L))
                .isInstanceOf(BusinessException.class).hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        WorkEntity other = work();
        other.setUserId(8L);
        when(works.selectById(18L)).thenReturn(other);
        assertThatThrownBy(() -> service.getWorkAiContent(18L))
                .isInstanceOf(BusinessException.class).hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        verify(works, never()).selectPortfolioAiMediaSnapshot(any());
        verifyNoInteractions(cos);
    }

    /** 初次授权后再次读取到删除或换属快照也拒绝展示。 */
    @Test
    void mustRecheckSnapshotOwnershipAndDeletion() {
        when(works.selectById(18L)).thenReturn(work());
        WorkEntity changed = work();
        changed.setUserId(8L);
        when(works.selectPortfolioAiMediaSnapshot(18L)).thenReturn(changed);
        assertThatThrownBy(() -> service.getWorkAiContent(18L)).hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        changed.setUserId(7L);
        changed.setDeleted(18L);
        assertThatThrownBy(() -> service.getWorkAiContent(18L)).hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        when(works.selectPortfolioAiMediaSnapshot(18L)).thenReturn(null);
        assertThatThrownBy(() -> service.getWorkAiContent(18L)).hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
        verifyNoInteractions(cos);
    }

    /** null、空串、纯空白保留原文，但全空时不应展示入口且无需访问 COS。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t", "　"})
    void emptyAudioPreservesSemanticsAndHidesEntry(String audio) {
        WorkEntity work = readableWork();
        work.setVideoAudioText(audio);
        MineWorkAiContentResponse response = service.getWorkAiContent(18L);
        assertThat(response.isHasContent()).isFalse();
        assertThat(response.getAudioText()).isEqualTo(audio);
        assertThat(response.getSummary()).isNull();
        assertThat(response.getFrames()).isEmpty();
        verifyNoInteractions(cos);
        verify(works).selectById(18L);
        verify(works).selectPortfolioAiMediaSnapshot(18L);
        verifyNoMoreInteractions(works);
    }

    /** 音频文字可独立展示，无需摘要、帧或 COS HEAD。 */
    @Test
    void transcriptOnlyIsVisibleWithoutStorageCall() {
        WorkEntity work = readableWork();
        work.setVideoAudioText("第一行\n第二行");
        MineWorkAiContentResponse response = service.getWorkAiContent(18L);
        assertThat(response.isHasContent()).isTrue();
        assertThat(response.getAudioText()).isEqualTo("第一行\n第二行");
        verifyNoInteractions(cos);
    }

    /** 非视频作品即使残留 AI 字段也不展示。 */
    @Test
    void nonVideoReturnsEmptyContent() {
        WorkEntity work = readableWork();
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        work.setVideoAudioText("残留文字");
        assertThat(service.getWorkAiContent(18L).isHasContent()).isFalse();
        verifyNoInteractions(cos);
    }

    /** 原视频摘要独立于帧和音频文字，观察要点可以单独成立。 */
    @Test
    void summaryOnlyAndObservationsOnlyRemainVisible() {
        WorkEntity work = readableWork();
        stubHead();
        work.setVideoSummaryJson(summary("婚礼现场", "仪式开始").toString());
        MineWorkAiContentResponse response = service.getWorkAiContent(18L);
        assertThat(response.isHasContent()).isTrue();
        assertThat(response.getSummary().summary()).isEqualTo("婚礼现场");
        assertThat(response.getSummary().observations()).containsExactly("仪式开始");
        assertThat(response.getAudioText()).isNull();
        work.setVideoSummaryJson(summary("", "仪式开始").toString());
        assertThat(service.getWorkAiContent(18L).getSummary().observations()).containsExactly("仪式开始");
        verify(cos, never()).signedGet(anyString(), any());
    }

    /** 严格解析损坏、旧源、只有局限或重复字段摘要，不影响合法音频文字。 */
    @Test
    void invalidSummariesAreMissingInsteadOfDisplayed() {
        WorkEntity work = readableWork();
        stubHead();
        work.setVideoAudioText("正常音频文字");
        ObjectNode limitationsOnly = summary("正文", "观察");
        ObjectNode content = (ObjectNode) limitationsOnly.path(PortfolioAiStoredVideoSummary.KEY_CONTENT);
        content.put(PortfolioAiMediaContract.SUMMARY, "");
        content.putArray(PortfolioAiMediaContract.OBSERVATIONS);
        content.putArray(PortfolioAiMediaContract.LIMITATIONS).add("看不清");
        ObjectNode stale = summary("正文", "观察");
        stale.put(PortfolioAiMediaContract.KEY_SOURCE_VERSION, "0".repeat(64));
        for (String raw : new String[]{"{broken", limitationsOnly.toString(), stale.toString(),
                summary("正文", "观察").toString().replace("\"summary\":\"正文\"", "\"summary\":\"正文\",\"summary\":\"重复\"")}) {
            work.setVideoSummaryJson(raw);
            MineWorkAiContentResponse response = service.getWorkAiContent(18L);
            assertThat(response.getSummary()).isNull();
            assertThat(response.isHasContent()).isTrue();
            assertThat(response.getAudioText()).isEqualTo("正常音频文字");
        }
    }

    /** 源 ETag 变更使旧摘要和旧帧均失效，不签名旧结果。 */
    @Test
    void changedSourceInvalidatesPersistedContent() {
        WorkEntity work = readableWork();
        work.setVideoSummaryJson(summary("正文", "观察").toString());
        work.setVideoFramesJson(manifest().toString());
        when(cos.headObjectVersion(SOURCE_KEY)).thenReturn(new CosService.VersionedObjectHead("video/mp4", SOURCE_BYTES, "changed", 0));
        MineWorkAiContentResponse response = service.getWorkAiContent(18L);
        assertThat(response.isHasContent()).isFalse();
        verify(cos, never()).signedGet(anyString(), any());
    }

    /** 全量帧按时间排序，只为合法本作品对象签短期地址，不使用 JSON 中的外部 URL。 */
    @Test
    void signsValidPrivateFramesAfterSortingAndIgnoresStoredUrls() {
        WorkEntity work = readableWork();
        stubHead();
        ObjectNode manifest = manifest();
        ((ObjectNode) manifest.path(PortfolioAiMediaContract.KEY_FRAMES).get(0)).put("url", "https://untrusted.test/image.jpg");
        work.setVideoFramesJson(manifest.toString());
        when(cos.signedGet(anyString(), eq(Duration.ofMinutes(15))))
                .thenAnswer(invocation -> "https://cos.test/" + invocation.getArgument(0) + "?signature=test");
        MineWorkAiContentResponse response = service.getWorkAiContent(18L);
        assertThat(response.isHasContent()).isTrue();
        assertThat(response.getFrames()).hasSize(8);
        assertThat(response.getFrames()).extracting(MineWorkAiContentResponse.Frame::timestampMs)
                .containsExactly(0L, 1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L);
        assertThat(response.getFrames().getFirst().width()).isEqualTo(960);
        assertThat(response.getFrames().getFirst().height()).isEqualTo(540);
        assertThat(response.getFrames().getFirst().url()).startsWith("https://cos.test/" + FRAME_PREFIX);
        assertThat(response.getFrames()).noneMatch(frame -> frame.url().contains("untrusted"));
    }

    /** 跨用户、跨作品、目录穿越和编码路径均不能生成任何签名。 */
    @ParameterizedTest
    @ValueSource(strings = {"OTHER/others/video-frames/18/frame.jpg", "WFOWNER/others/video-frames/19/frame.jpg",
            "WFOWNER/others/video-frames/18/../secret.jpg", "WFOWNER/others/video-frames/18/%2e%2e/secret.jpg",
            "WFOWNER/others/video-frames/18/\\secret.jpg"})
    void refusesForeignOrUnsafeFrameKeys(String key) {
        WorkEntity work = readableWork();
        stubHead();
        ObjectNode manifest = manifest();
        ((ObjectNode) manifest.path(PortfolioAiMediaContract.KEY_FRAMES).get(7)).put(PortfolioAiMediaContract.KEY_OBJECT_KEY, key);
        work.setVideoFramesJson(manifest.toString());
        assertThat(service.getWorkAiContent(18L).getFrames()).isEmpty();
        verify(cos, never()).signedGet(anyString(), any());
    }

    /** 非法帧数量、尺寸、时间与重复帧均按整个清单不可用处理。 */
    @Test
    void rejectsInvalidFrameManifestBeforeAnySignature() {
        WorkEntity work = readableWork();
        stubHead();
        for (String field : new String[]{PortfolioAiMediaContract.KEY_WIDTH, PortfolioAiMediaContract.KEY_HEIGHT,
                PortfolioAiMediaContract.KEY_TIME_MS, PortfolioAiMediaContract.KEY_BYTES}) {
            ObjectNode manifest = manifest();
            ((ObjectNode) manifest.path(PortfolioAiMediaContract.KEY_FRAMES).get(7)).put(field, -1);
            work.setVideoFramesJson(manifest.toString());
            assertThat(service.getWorkAiContent(18L).getFrames()).isEmpty();
        }
        ObjectNode duplicate = manifest();
        ((ObjectNode) duplicate.path(PortfolioAiMediaContract.KEY_FRAMES).get(7)).put(PortfolioAiMediaContract.KEY_TIME_MS, 7000);
        work.setVideoFramesJson(duplicate.toString());
        assertThat(service.getWorkAiContent(18L).getFrames()).isEmpty();
        ObjectNode shortList = manifest();
        shortList.put(PortfolioAiMediaContract.KEY_FRAME_COUNT, 1);
        work.setVideoFramesJson(shortList.toString());
        assertThat(service.getWorkAiContent(18L).isHasContent()).isFalse();
        work.setVideoFramesJson("{broken");
        assertThat(service.getWorkAiContent(18L).getFrames()).isEmpty();
        verify(cos, never()).signedGet(anyString(), any());
    }

    /** 读存储失败交给页面手动重试，不伪装成三类全空。 */
    @Test
    void storageFailurePropagatesForManualRetry() {
        WorkEntity work = readableWork();
        work.setVideoFramesJson(manifest().toString());
        when(cos.headObjectVersion(SOURCE_KEY)).thenThrow(new IllegalStateException("temporary"));
        assertThatThrownBy(() -> service.getWorkAiContent(18L)).isInstanceOf(IllegalStateException.class);
        verify(cos, never()).signedGet(anyString(), any());
    }

    /** 构造本人未删除视频作品。 */
    private WorkEntity work() {
        WorkEntity work = new WorkEntity();
        work.setId(18L);
        work.setUserId(7L);
        work.setDeleted(0L);
        work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey(SOURCE_KEY);
        return work;
    }

    /** 授权查询和显式快照读取均返回当前作品。 */
    private WorkEntity readableWork() {
        WorkEntity work = work();
        when(works.selectById(18L)).thenReturn(work);
        when(works.selectPortfolioAiMediaSnapshot(18L)).thenReturn(work);
        return work;
    }

    /** 模拟真实源对象 HEAD，包含 ETag 引号和大小写兼容。 */
    private void stubHead() {
        when(cos.headObjectVersion(SOURCE_KEY)).thenReturn(new CosService.VersionedObjectHead(
                "video/mp4", SOURCE_BYTES, "\"SOURCE-ETAG\"", 0));
    }

    /** 与生产者一致的源指纹。 */
    private String sourceVersion() {
        return PortfolioAiStoredVideoSummary.sourceVersion(SOURCE_KEY, SOURCE_ETAG, SOURCE_BYTES, null);
    }

    /** 使用严格合同创建可用于查询的持久摘要。 */
    private ObjectNode summary(String body, String observation) {
        ObjectNode content = StrictAiJson.mapper().createObjectNode().put(PortfolioAiMediaContract.SUMMARY, body);
        content.putArray(PortfolioAiMediaContract.OBSERVATIONS).add(observation);
        content.putArray(PortfolioAiMediaContract.LIMITATIONS);
        return PortfolioAiStoredVideoSummary.create(sourceVersion(), "a".repeat(64), "test-model", "test-prompt",
                Instant.parse("2026-10-15T00:00:00Z"), content);
    }

    /** 构造逆序的合法八帧清单，校验查询会按时间恢复顺序。 */
    private ObjectNode manifest() {
        ObjectNode manifest = StrictAiJson.mapper().createObjectNode()
                .put(PortfolioAiMediaContract.KEY_SCHEMA_VERSION, PortfolioAiMediaContract.FRAME_SCHEMA)
                .put(PortfolioAiMediaContract.KEY_SOURCE_VERSION, sourceVersion())
                .put(PortfolioAiMediaContract.KEY_FRAME_COUNT, 8);
        var frames = manifest.putArray(PortfolioAiMediaContract.KEY_FRAMES);
        for (int i = 7; i >= 0; i--) {
            frames.addObject().put(PortfolioAiMediaContract.KEY_OBJECT_KEY, FRAME_PREFIX + i + ".jpg")
                    .put(PortfolioAiMediaContract.KEY_TIME_MS, i * 1000)
                    .put(PortfolioAiMediaContract.KEY_BYTES, 1000)
                    .put(PortfolioAiMediaContract.KEY_WIDTH, 960)
                    .put(PortfolioAiMediaContract.KEY_HEIGHT, 540);
        }
        return manifest;
    }
}
