package com.jxc.wefolio.dto;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传确认响应兼容性测试。
 */
class MineWorkUploadCompleteResponseTest {

    /** 普通失败保持旧结构，只有单帧降级项输出错误码。 */
    @Test
    void errorCodeIsOnlySerializedForSingleFrameFallback() {
        String ordinaryJson = JSON.toJSONString(
                MineWorkUploadCompleteResponse.Item.failure(1L, "普通失败"));
        String fallbackJson = JSON.toJSONString(
                MineWorkUploadCompleteResponse.Item.failure(
                        2L,
                        "ANIMATION_SINGLE_FRAME",
                        "该文件只有 1 帧，将按图片重新上传"));

        assertThat(ordinaryJson).doesNotContain("errorCode");
        assertThat(fallbackJson)
                .contains("\"errorCode\":\"ANIMATION_SINGLE_FRAME\"")
                .contains("\"success\":false");
    }
}
