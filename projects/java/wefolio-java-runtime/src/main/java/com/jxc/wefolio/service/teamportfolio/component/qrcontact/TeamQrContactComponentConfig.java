package com.jxc.wefolio.service.teamportfolio.component.qrcontact;

import lombok.Data;

/**
 * 团队二维码联系组件配置。
 */
@Data
public class TeamQrContactComponentConfig {

    /** 自定义二维码来源编码。 */
    private static final String CUSTOM = "CUSTOM";

    /** 二维码来源。 */
    private String qrUrlSource = CUSTOM;

    /** 自定义二维码地址。 */
    private String qrUrl;
}
