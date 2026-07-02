package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 创建作品集请求。
 */
@Data
public class MinePortfolioCreateRequest {

    /** 初始配置，可为空 */
    private PortfolioConfigDto config;
}
