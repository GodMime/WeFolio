package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;

/**
 * 保存团队作品集草稿请求。
 */
@Data
public class TeamPortfolioDraftSaveRequest {

    /** 可选客户端字体能力；旧端可以省略。 */
    private PortfolioClientCapabilities clientCapabilities;

    /** 草稿配置。 */
    private TeamPortfolioConfigDto config;

    /** 客户端读取到的草稿版本。 */
    private Integer clientRevision;

    /** 保存幂等键。 */
    private String idempotencyKey;
}
