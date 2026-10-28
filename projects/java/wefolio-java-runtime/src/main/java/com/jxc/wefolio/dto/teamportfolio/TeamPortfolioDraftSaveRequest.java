package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;
import com.jxc.wefolio.dto.PortfolioRequestConfigBoundary;

/**
 * 保存团队作品集草稿请求。
 */
@Data
public class TeamPortfolioDraftSaveRequest {

    /** 可选客户端字体能力；旧端可以省略。 */
    private PortfolioClientCapabilities clientCapabilities;

    /** 草稿配置。 */
    private TeamPortfolioConfigDto config;

    /** 团队保存及字体准备在宽松 DTO 转换前检查高级版本根字段。 */
    public void setConfig(Object value) {
        config = PortfolioRequestConfigBoundary.convert(value, TeamPortfolioConfigDto.class);
    }

    /** 客户端读取到的草稿版本。 */
    private Integer clientRevision;

    /** 保存幂等键。 */
    private String idempotencyKey;
}
