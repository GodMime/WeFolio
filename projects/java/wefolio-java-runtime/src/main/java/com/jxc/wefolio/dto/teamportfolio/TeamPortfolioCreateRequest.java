package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;
import com.jxc.wefolio.dto.PortfolioRequestConfigBoundary;

/**
 * 创建标准团队作品集请求。
 */
@Data
public class TeamPortfolioCreateRequest {

    /** 可选客户端字体能力；旧端可以省略。 */
    private PortfolioClientCapabilities clientCapabilities;

    /** 初始团队作品集配置，可为空。 */
    private TeamPortfolioConfigDto config;

    /** 在普通配置转换之前拒绝高级版本根字段，不改变旧请求的其它字段。 */
    public void setConfig(Object value) {
        config = PortfolioRequestConfigBoundary.convert(value, TeamPortfolioConfigDto.class);
    }
}
