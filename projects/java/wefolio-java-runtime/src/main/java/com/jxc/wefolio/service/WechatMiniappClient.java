package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;

/**
 * 微信小程序客户端 — 封装微信服务端接口调用
 */
public interface WechatMiniappClient {

    /**
     * 使用 wx.login code 换取微信会话
     *
     * @param code wx.login 返回的临时登录凭证
     * @return 微信会话响应
     */
    WechatSessionResponse exchangeCode(String code);

    /**
     * 使用手机号快速验证组件 code 换取手机号
     *
     * @param code getPhoneNumber 事件返回的 code
     * @return 用户手机号信息
     */
    WechatPhoneNumberResponse.PhoneInfo exchangePhoneCode(String code);

    /**
     * 使用 wx.pluginLogin code 换取插件用户 openpid
     *
     * @param code wx.pluginLogin 返回的插件用户标志凭证
     * @return 插件用户 openpid
     */
    String exchangePluginOpenpid(String code);
}
