package com.jxc.wefolio.service.teamportfolio.component.divider;

import lombok.Data;

/**
 * 分割线组件配置。
 */
@Data
public class TeamDividerComponentConfig {

    /** 分割线颜色：六位十六进制颜色，兼容 BLACK / WHITE / GRAY / TRANSPARENT。 */
    private String color;

    /** 分割线高度像素值。 */
    private Integer heightPx;
}
