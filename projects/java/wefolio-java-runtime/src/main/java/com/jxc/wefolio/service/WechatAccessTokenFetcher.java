package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.WechatAccessTokenResponse;

/**
 * 微信接口调用凭证远端获取器。
 */
public interface WechatAccessTokenFetcher {

    /**
     * 从微信服务获取新的接口调用凭证。
     *
     * @return 微信凭证响应
     */
    WechatAccessTokenResponse fetch();
}
