package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.PortfolioConfigDto;

/** 标准个人作品集组件间距规范化，保证保存与渲染使用相同默认值和范围。 */
public final class PortfolioComponentSpacingSupport {

    /** 工具类不允许实例化。 */
    private PortfolioComponentSpacingSupport() {
    }

    /** 缺省或越界使用 32 rpx，保留 0–96 rpx 范围内的合法值。 */
    public static int normalize(Integer componentSpacingRpx) {
        if (componentSpacingRpx == null
                || componentSpacingRpx < PortfolioConfigDto.MIN_COMPONENT_SPACING_RPX
                || componentSpacingRpx > PortfolioConfigDto.MAX_COMPONENT_SPACING_RPX) {
            return PortfolioConfigDto.DEFAULT_COMPONENT_SPACING_RPX;
        }
        return componentSpacingRpx;
    }
}
