package com.jxc.wefolio.service.teamportfolio.component.textsection;

import lombok.Data;

/**
 * 文字说明组件配置。
 */
@Data
public class TeamTextSectionComponentConfig {

    /** 普通文字颜色：AUTO 或六位十六进制；旧配置默认 AUTO。 */
    private String color;

    /** 文字内容。 */
    private String content;

    /** 文字对齐方式。 */
    private String alignment;

    /** 正文字体。 */
    private String fontFamily;

    /** 正文字号，单位 rpx。 */
    private Integer fontSizeRpx;
}
