package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 维护者微信登录预检响应 — 仅返回下一步是否必须授权手机号。
 */
@Data
public class MaintainerWechatLoginPrecheckResponse {

    /** 下一步是否必须授权手机号 */
    private boolean phoneAuthorizationRequired;
}
