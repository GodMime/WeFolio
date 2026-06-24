package com.jxc.wefolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.WechatAccessTokenResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatPluginOpenpidResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * REST 微信小程序客户端 — 调用微信 jscode2session 接口
 */
@Service
@RequiredArgsConstructor
public class RestWechatMiniappClient implements WechatMiniappClient {

    /** 微信 jscode2session 地址 */
    private static final String CODE_SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    /** 微信 access_token 地址 */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** 微信手机号快速验证地址 */
    private static final String PHONE_NUMBER_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    /** 微信插件 openpid 地址 */
    private static final String PLUGIN_OPENPID_URL = "https://api.weixin.qq.com/wxa/getpluginopenpid";

    /** access_token 过期前刷新缓冲 */
    private static final long ACCESS_TOKEN_REFRESH_BUFFER_MILLIS = 5L * 60L * 1000L;

    /** 微信小程序配置 */
    private final WechatMiniappProperties properties;

    /** REST 客户端 */
    private final RestClient restClient = RestClient.create();

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 缓存的 access_token */
    private volatile String cachedAccessToken;

    /** 缓存 access_token 过期时间戳 */
    private volatile long cachedAccessTokenExpiresAt;

    /**
     * 使用 wx.login code 换取微信会话
     *
     * @param code wx.login 返回的临时登录凭证
     * @return 微信会话响应
     */
    @Override
    public WechatSessionResponse exchangeCode(String code) {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new IllegalStateException("微信小程序配置缺失");
        }

        String url = UriComponentsBuilder.fromUriString(CODE_SESSION_URL)
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .build()
                .toUriString();

        WechatSessionResponse response = getWechatResponse(url, WechatSessionResponse.class);

        if (response == null) {
            throw new IllegalArgumentException("微信登录服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new IllegalArgumentException("微信登录失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getOpenid())) {
            throw new IllegalArgumentException("微信登录未返回 openid");
        }
        return response;
    }

    /**
     * 使用手机号快速验证组件 code 换取手机号
     *
     * @param code getPhoneNumber 事件返回的 code
     * @return 用户手机号信息
     */
    @Override
    public WechatPhoneNumberResponse.PhoneInfo exchangePhoneCode(String code) {
        if (isBlank(code)) {
            throw new IllegalArgumentException("手机号授权凭证不能为空");
        }
        String url = UriComponentsBuilder.fromUriString(PHONE_NUMBER_URL)
                .queryParam("access_token", accessToken())
                .build()
                .toUriString();

        WechatPhoneNumberResponse response = postWechatResponse(
                url,
                Map.of("code", code),
                WechatPhoneNumberResponse.class
        );

        if (response == null) {
            throw new IllegalArgumentException("微信手机号服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new IllegalArgumentException("微信手机号获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (response.getPhoneInfo() == null || isBlank(response.getPhoneInfo().getPhoneNumber())) {
            throw new IllegalArgumentException("微信手机号服务未返回手机号");
        }
        return response.getPhoneInfo();
    }

    /**
     * 使用 wx.pluginLogin code 换取插件用户 openpid
     *
     * @param code wx.pluginLogin 返回的插件用户标志凭证
     * @return 插件用户 openpid
     */
    @Override
    public String exchangePluginOpenpid(String code) {
        if (isBlank(code)) {
            throw new IllegalArgumentException("微信插件登录凭证不能为空");
        }
        String url = UriComponentsBuilder.fromUriString(PLUGIN_OPENPID_URL)
                .queryParam("access_token", accessToken())
                .build()
                .toUriString();

        WechatPluginOpenpidResponse response = postWechatResponse(
                url,
                Map.of("code", code),
                WechatPluginOpenpidResponse.class
        );

        if (response == null) {
            throw new IllegalArgumentException("微信 openpid 服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new IllegalArgumentException("微信 openpid 获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getOpenpid())) {
            throw new IllegalArgumentException("微信 openpid 服务未返回 openpid");
        }
        return response.getOpenpid();
    }

    /**
     * 获取并缓存微信接口调用凭证
     *
     * @return access_token
     */
    private synchronized String accessToken() {
        long now = System.currentTimeMillis();
        if (!isBlank(cachedAccessToken) && cachedAccessTokenExpiresAt > now) {
            return cachedAccessToken;
        }
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new IllegalStateException("微信小程序配置缺失");
        }

        String url = UriComponentsBuilder.fromUriString(ACCESS_TOKEN_URL)
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .build()
                .toUriString();

        WechatAccessTokenResponse response = getWechatResponse(url, WechatAccessTokenResponse.class);

        if (response == null) {
            throw new IllegalArgumentException("微信 access_token 服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new IllegalArgumentException("微信 access_token 获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getAccessToken())) {
            throw new IllegalArgumentException("微信 access_token 服务未返回凭证");
        }
        long expiresInMillis = Math.max(60L, response.getExpiresIn() == null ? 7200L : response.getExpiresIn()) * 1000L;
        long refreshBufferMillis = Math.min(ACCESS_TOKEN_REFRESH_BUFFER_MILLIS, expiresInMillis / 2);
        cachedAccessToken = response.getAccessToken();
        cachedAccessTokenExpiresAt = now + expiresInMillis - refreshBufferMillis;
        return cachedAccessToken;
    }

    /**
     * 读取微信 GET 响应。微信部分接口会返回 JSON body 但声明 text/plain，
     * 因此先按字符串接收，再统一反序列化。
     *
     * @param url 请求地址
     * @param responseType 响应类型
     * @return 响应对象
     * @param <T> 响应类型
     */
    private <T> T getWechatResponse(String url, Class<T> responseType) {
        String body = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        return parseWechatResponse(body, responseType);
    }

    /**
     * 读取微信 POST 响应。微信部分接口会返回 JSON body 但声明 text/plain，
     * 因此先按字符串接收，再统一反序列化。
     *
     * @param url 请求地址
     * @param requestBody 请求体
     * @param responseType 响应类型
     * @return 响应对象
     * @param <T> 响应类型
     */
    private <T> T postWechatResponse(String url, Map<String, String> requestBody, Class<T> responseType) {
        String body = restClient.post()
                .uri(url)
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return parseWechatResponse(body, responseType);
    }

    /**
     * 解析微信 JSON 响应
     *
     * @param body 响应体
     * @param responseType 响应类型
     * @return 响应对象
     * @param <T> 响应类型
     */
    private <T> T parseWechatResponse(String body, Class<T> responseType) {
        if (isBlank(body)) {
            return null;
        }
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("微信接口响应解析失败", e);
        }
    }

    /**
     * 判断字符串是否为空
     *
     * @param value 原字符串
     * @return 是否为空
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 默认字符串
     *
     * @param value 原字符串
     * @param fallback 兜底字符串
     * @return 非空字符串
     */
    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
