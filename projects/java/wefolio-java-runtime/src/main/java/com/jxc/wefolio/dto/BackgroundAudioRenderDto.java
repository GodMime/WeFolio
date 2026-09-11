package com.jxc.wefolio.dto;

import lombok.Data;

/** 两类作品集共用的音频展示数据，仅在响应中生成 URL。 */
@Data
public class BackgroundAudioRenderDto {
    /** 可播放且已开启时为 true。 */
    private Boolean enabled = false;
    /** 配置引用的作品 ID。 */
    private Long workId;
    /** 展示样式。 */
    private String displayStyle;
    /** 当前作品标题。 */
    private String title;
    /** 当前有效封面或默认封面 URL。 */
    private String coverUrl;
    /** 音频媒体 URL。 */
    private String mediaUrl;
    /** 音频时长，单位毫秒。 */
    private Integer durationMs;
}
