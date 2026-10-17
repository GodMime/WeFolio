package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 作品删除的帧身份隔离验证，不访问真实 COS。 */
class VideoFrameObjectKeysTest {
    /** 当前作品允许的帧地址。 */
    private static final String OWN_KEY = "OWNER/others/video-frames/18/source/policy/task/0001.jpg";

    /** 有效帧按唯一对象键清理，不依赖公开 URL。 */
    @Test void collectsOnlyOwnedFrameKeys() {
        WorkEntity work = work("{\"frameCount\":1,\"frames\":[{\"objectKey\":\"" + OWN_KEY + "\"}]}");
        assertThat(VideoFrameObjectKeys.collect(work)).containsExactly(OWN_KEY);
    }

    /** 清理侧遵守共享帧数上限，完整上限清单可清理，超过上限仍整体拒绝。 */
    @Test void usesSharedFrameCountLimit() {
        int maxCount = PortfolioAiMediaContract.FRAME_MAX_COUNT;
        assertThat(VideoFrameObjectKeys.collect(work(manifestWithFrameCount(maxCount)))).hasSize(maxCount);
        assertThat(VideoFrameObjectKeys.collect(work(manifestWithFrameCount(maxCount + 1)))).isEmpty();
    }

    /** 一个跨作品对象即拒绝整份清单，不能借由本作品删除任意对象。 */
    @Test void rejectsCrossWorkAndEncodedPaths() {
        for (String key : new String[] {"OTHER/others/video-frames/18/s/p/t/0001.jpg",
                "OWNER/others/video-frames/19/s/p/t/0001.jpg", "OWNER/others/video-frames/18/../19/0001.jpg",
                "OWNER/others/video-frames/18/%2e%2e/0001.jpg"}) {
            assertThat(VideoFrameObjectKeys.collect(work("{\"frameCount\":2,\"frames\":[{\"objectKey\":\""
                    + OWN_KEY + "\"},{\"objectKey\":\"" + key + "\"}]}"))).isEmpty();
        }
    }

    /** 损坏、重复 JSON 键或数量不齐时不依据不完整清单清理。 */
    @Test void rejectsBrokenOrAmbiguousManifests() {
        for (String json : new String[] {"{", "{\"frameCount\":2,\"frames\":[]}",
                "{\"frameCount\":1,\"frameCount\":2,\"frames\":[]}"}) {
            assertThat(VideoFrameObjectKeys.collect(work(json))).isEmpty();
        }
    }

    /** 非视频作品保持原删除契约。 */
    @Test void ignoresNonVideoWorks() {
        WorkEntity work = work("{\"frameCount\":1,\"frames\":[{\"objectKey\":\"" + OWN_KEY + "\"}]}");
        work.setMediaType(MediaTypeDict.IMAGE.getCode());
        assertThat(VideoFrameObjectKeys.collect(work)).isEmpty();
    }

    /** 构造无需数据库的当前作品身份。 */
    private WorkEntity work(String manifest) {
        WorkEntity work = new WorkEntity();
        work.setId(18L); work.setMediaType(MediaTypeDict.VIDEO.getCode());
        work.setMediaObjectKey("OWNER/work/video/source.mp4"); work.setVideoFramesJson(manifest);
        return work;
    }

    /** 按共享目录生成互不重复的当前作品帧身份，避免测试另有一份固定上限。 */
    private String manifestWithFrameCount(int count) {
        String prefix = "OWNER" + PortfolioAiMediaContract.FRAME_DIRECTORY + "18/source/policy/task/";
        String frames = IntStream.range(0, count)
                .mapToObj(index -> "{\"objectKey\":\"" + prefix + index + ".jpg\"}")
                .collect(Collectors.joining(","));
        return "{\"frameCount\":" + count + ",\"frames\":[" + frames + "]}";
    }
}
