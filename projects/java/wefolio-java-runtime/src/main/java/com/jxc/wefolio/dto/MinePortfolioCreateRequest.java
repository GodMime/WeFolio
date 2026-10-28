package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 创建作品集请求。
 */
@Data
public class MinePortfolioCreateRequest {

    /** 初始配置，可为空 */
    private PortfolioConfigDto config;

    /** 先检查原始配置根字段，避免普通入口把高级元数据作为未知字段丢弃。 */
    public void setConfig(Object value) {
        config = PortfolioRequestConfigBoundary.convert(value, PortfolioConfigDto.class);
    }
}
