package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/** 视频作品已有 AI 内容的只读响应，不参与作品编辑请求。 */
@Data
public class MineWorkAiContentResponse {

    /** 摘要、音频文字、关键帧中是否至少有一类有效内容。 */
    private boolean hasContent;

    /** 已校验的原视频摘要，没有有效摘要时为空。 */
    private Summary summary;

    /** 音频文字；保留未生成的 null 与已处理无文字的空串语义。 */
    private String audioText;

    /** 按时间升序排列的关键帧，地址仅允许临时读取。 */
    private List<Frame> frames = List.of();

    /** 原视频摘要的纯展示字段。 */
    public record Summary(String summary, List<String> observations, List<String> limitations) { }

    /** 关键帧的毫秒时间点、像素尺寸和临时读取地址。 */
    public record Frame(long timestampMs, int width, int height, String url) { }
}
