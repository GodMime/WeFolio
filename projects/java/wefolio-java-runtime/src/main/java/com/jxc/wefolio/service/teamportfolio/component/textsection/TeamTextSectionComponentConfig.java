package com.jxc.wefolio.service.teamportfolio.component.textsection;

import lombok.Data;
import java.math.BigDecimal;

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

    /** 正文字号，单位 rpx，取值为 10 至 96 的整数。 */
    private Integer fontSizeRpx;

    /** 可选行高字号倍数，范围 0.5 至 3.0、步长 0.1；省略时保持旧样式。 */
    private BigDecimal lineHeight;
}
