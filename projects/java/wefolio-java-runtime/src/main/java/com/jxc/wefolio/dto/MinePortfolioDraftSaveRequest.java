package com.jxc.wefolio.dto;

import lombok.Data;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;

/**
 * 保存作品集草稿请求。
 */
@Data
public class MinePortfolioDraftSaveRequest {

    /** 可选客户端字体能力；旧端可以省略。 */
    private PortfolioClientCapabilities clientCapabilities;

    /** 草稿配置 */
    private PortfolioConfigDto config;

    /** 客户端看到的草稿版本 */
    private Integer clientRevision;

    /** 保存幂等键 */
    private String idempotencyKey;
}
