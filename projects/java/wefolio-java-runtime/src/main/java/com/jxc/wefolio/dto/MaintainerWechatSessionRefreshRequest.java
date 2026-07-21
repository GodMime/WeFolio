package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 维护者微信会话刷新请求。
 */
@Data
public class MaintainerWechatSessionRefreshRequest {

    /** {@code wx.login} 返回的一次性 code。 */
    private String code;
}
