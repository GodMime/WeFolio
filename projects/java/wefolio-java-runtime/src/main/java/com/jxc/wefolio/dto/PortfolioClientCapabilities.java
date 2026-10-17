package com.jxc.wefolio.dto;

import lombok.Data;

/** 客户端明确实现的作品集扩展能力，不从配置继承。 */
@Data
public class PortfolioClientCapabilities {
    /** 远程字体协议版本，当前为 1。 */
    private Integer portfolioRemoteFont;

    /** 只接受当前实现的能力版本。 */
    public boolean supportsRemoteFonts() {
        return Integer.valueOf(1).equals(portfolioRemoteFont);
    }
}
