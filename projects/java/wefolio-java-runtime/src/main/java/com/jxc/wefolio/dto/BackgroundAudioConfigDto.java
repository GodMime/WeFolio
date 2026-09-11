package com.jxc.wefolio.dto;

import lombok.Data;

/** 个人、团队作品集共用的背景音频配置；不持久化媒体地址。 */
@Data
public class BackgroundAudioConfigDto {

    /** 唱片样式。 */
    public static final String DISC = "DISC";

    /** 唱片套样式。 */
    public static final String SLEEVE = "SLEEVE";

    /** 迷你播放器样式。 */
    public static final String MINI_PLAYER = "MINI_PLAYER";

    /** 全局配置键，同时作为引用的组件键。 */
    public static final String CONFIG_KEY = "backgroundAudio";

    /** 音频引用路径。 */
    public static final String WORK_PATH = "backgroundAudio.workId";

    /** 是否开启，历史配置默认关闭。 */
    private Boolean enabled;

    /** 音频作品 ID，关闭时仍保留引用。 */
    private Long workId;

    /** 控件样式。 */
    private String displayStyle;
}
