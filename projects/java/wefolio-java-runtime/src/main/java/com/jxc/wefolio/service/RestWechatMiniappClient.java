package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.dto.WechatAccessTokenResponse;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.dto.WechatPluginOpenpidResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST 微信小程序客户端 — 统一封装微信小程序服务端接口调用、原始入参出参日志和响应解析
 */
@Slf4j
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

    /** 微信 access_token 缓存键前缀 */
    private static final String ACCESS_TOKEN_CACHE_KEY_PREFIX = "wechat:miniapp:access-token:";

    /** access_token 过期前刷新缓冲秒数 */
    private static final long ACCESS_TOKEN_REFRESH_BUFFER_SECONDS = 5L * 60L;

    /** 微信小程序配置 */
    private final WechatMiniappProperties properties;

    /** 缓存服务 */
    private final CacheService cacheService;

    /**
     * REST 客户端 — 强制 HTTP/1.1。
     * JDK HttpClient 默认通过 ALPN 协商 HTTP/2，但部分中间 CDN/网关对
     * HTTP/2 POST 处理异常（直接返回 412 且 body 为空），curl 走 HTTP/1.1 则正常。
     */
    private final RestClient restClient = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            ))
            .build();

    /** JSON 解析 — 使用 Fastjson2 统一 JSON 处理 */

    /**
     * 使用 wx.login code 换取微信会话
     *
     * @param code wx.login 返回的临时登录凭证
     * @return 微信会话响应
     */
    @Override
    public WechatSessionResponse exchangeCode(String code) {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new BusinessException("微信小程序配置缺失");
        }

        String url = UriComponentsBuilder.fromUriString(CODE_SESSION_URL)
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .build()
                .toUriString();

        WechatSessionResponse response = getWechatResponse(url, WechatSessionResponse.class, "微信登录服务");

        if (response == null) {
            throw new BusinessException("微信登录服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new BusinessException("微信登录失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getOpenid())) {
            throw new BusinessException("微信登录未返回 openid");
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
            throw new BusinessException("手机号授权凭证不能为空");
        }
        String url = UriComponentsBuilder.fromUriString(PHONE_NUMBER_URL)
                .queryParam("access_token", accessToken())
                .build()
                .toUriString();

        WechatPhoneNumberResponse response = postWechatResponse(
                url,
                Map.of("code", code),
                WechatPhoneNumberResponse.class,
                "微信手机号服务"
        );

        if (response == null) {
            throw new BusinessException("微信手机号服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new BusinessException("微信手机号获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (response.getPhoneInfo() == null || isBlank(response.getPhoneInfo().getPhoneNumber())) {
            throw new BusinessException("微信手机号服务未返回手机号");
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
            throw new BusinessException("微信插件登录凭证不能为空");
        }
        String url = UriComponentsBuilder.fromUriString(PLUGIN_OPENPID_URL)
                .queryParam("access_token", accessToken())
                .build()
                .toUriString();

        WechatPluginOpenpidResponse response = postWechatResponse(
                url,
                Map.of("code", code),
                WechatPluginOpenpidResponse.class,
                "微信 openpid 服务"
        );

        if (response == null) {
            throw new BusinessException("微信 openpid 服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new BusinessException("微信 openpid 获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getOpenpid())) {
            throw new BusinessException("微信 openpid 服务未返回 openpid");
        }
        return response.getOpenpid();
    }

    /**
     * 获取并缓存微信接口调用凭证
     *
     * @return access_token
     */
    private synchronized String accessToken() {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new BusinessException("微信小程序配置缺失");
        }
        String cacheKey = accessTokenCacheKey();
        Optional<String> cachedAccessToken = cacheService.get(cacheKey, String.class);
        if (cachedAccessToken.isPresent() && !isBlank(cachedAccessToken.get())) {
            return cachedAccessToken.get();
        }

        String url = UriComponentsBuilder.fromUriString(ACCESS_TOKEN_URL)
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .build()
                .toUriString();

        WechatAccessTokenResponse response = getWechatResponse(url, WechatAccessTokenResponse.class, "微信 access_token 服务");

        if (response == null) {
            throw new BusinessException("微信 access_token 服务无响应");
        }
        if (response.getErrcode() != null && response.getErrcode() != 0) {
            throw new BusinessException("微信 access_token 获取失败：" + defaultString(response.getErrmsg(), "未知错误"));
        }
        if (isBlank(response.getAccessToken())) {
            throw new BusinessException("微信 access_token 服务未返回凭证");
        }
        cacheService.put(cacheKey, response.getAccessToken(), accessTokenCacheTtl(response));
        return response.getAccessToken();
    }

    /**
     * 构建微信 access_token 缓存键
     *
     * @return 缓存键
     */
    private String accessTokenCacheKey() {
        return ACCESS_TOKEN_CACHE_KEY_PREFIX + properties.getAppId();
    }

    /**
     * 计算微信 access_token 缓存有效期。为避免临界过期，缓存时间会短于微信返回的 expires_in。
     *
     * @param response 微信 access_token 响应
     * @return 缓存有效期
     */
    private Duration accessTokenCacheTtl(WechatAccessTokenResponse response) {
        long expiresInSeconds = Math.max(60L, response.getExpiresIn() == null ? 7200L : response.getExpiresIn());
        long refreshBufferSeconds = Math.min(ACCESS_TOKEN_REFRESH_BUFFER_SECONDS, expiresInSeconds / 2);
        return Duration.ofSeconds(expiresInSeconds - refreshBufferSeconds);
    }

    /**
     * 读取微信 GET 响应。微信部分接口会返回 JSON body 但声明 text/plain，
     * 因此先按字符串接收，再统一反序列化。
     *
     * @param url 请求地址
     * @param responseType 响应类型
     * @param serviceName 服务名称
     * @return 响应对象
     * @param <T> 响应类型
     */
    private <T> T getWechatResponse(String url, Class<T> responseType, String serviceName) {
        logWechatRequest(serviceName, "GET", url, "");
        String body = restClient.get()
                .uri(url)
                .exchange((request, response) -> readWechatHttpBody(response, serviceName));
        return parseWechatResponse(body, responseType);
    }

    /**
     * 读取微信 POST 响应。微信部分接口会返回 JSON body 但声明 text/plain，
     * 因此先按字符串接收，再统一反序列化。
     *
     * @param url 请求地址
     * @param requestBody 请求体
     * @param responseType 响应类型
     * @param serviceName 服务名称
     * @return 响应对象
     * @param <T> 响应类型
     */
    private <T> T postWechatResponse(
            String url,
            Map<String, String> requestBody,
            Class<T> responseType,
            String serviceName
    ) {
        String requestBodyText = serializeRequestBody(requestBody);
        logWechatRequest(serviceName, "POST", url, requestBodyText);
        // 手动序列化为 String 传入 body()，避免 RestClient 消息转换器对 Map 的序列化
        // 行为与 ObjectMapper 不一致（如字段排序、null 处理等），导致微信网关 412。
        String body = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBodyText)
                .exchange((request, response) -> {
                    // 打印实际发出的请求头，方便与 curl 比对差异
                    log.info("微信远端请求头 headers={}", request.getHeaders());
                    return readWechatHttpBody(response, serviceName);
                });
        return parseWechatResponse(body, responseType);
    }

    /**
     * 读取微信 HTTP 响应，并把 HTTP 失败状态转换为业务异常。
     *
     * @param response HTTP 响应
     * @param serviceName 服务名称
     * @return 响应体文本
     * @throws IOException 响应体读取失败
     */
    private String readWechatHttpBody(ClientHttpResponse response, String serviceName) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        logWechatResponse(serviceName, response.getStatusCode().value(), body);
        if (response.getStatusCode().isError()) {
            // 错误时打印响应头，帮助定位网关/CDN/代理层面的问题
            log.warn("微信远端响应异常 headers={}", response.getHeaders());
            throw new BusinessException(buildWechatHttpErrorMessage(response, serviceName, body));
        }
        return body;
    }

    /**
     * 打印微信远端请求原始入参 — URL 中的 secret 无条件脱敏，access_token 按配置脱敏。
     *
     * @param serviceName 服务名称
     * @param method HTTP 方法
     * @param url 请求地址
     * @param requestBody 请求体文本
     */
    private void logWechatRequest(String serviceName, String method, String url, String requestBody) {
        log.info(
                "微信远端请求入参 serviceName={} method={} url={} requestBody={}",
                serviceName,
                method,
                maskUrl(url),
                requestBody
        );
    }

    /**
     * 打印微信远端响应原始出参 — 按配置脱敏响应体中的手机号等敏感字段。
     *
     * @param serviceName 服务名称
     * @param status HTTP 状态码
     * @param responseBody 响应体文本
     */
    private void logWechatResponse(String serviceName, int status, String responseBody) {
        log.info(
                "微信远端响应出参 serviceName={} status={} responseBody={}",
                serviceName,
                status,
                maskResponseBody(responseBody)
        );
    }

    /**
     * 脱敏 URL 中的敏感查询参数。
     *
     * <p>secret 无条件脱敏（永不打日志）。
     * access_token 按 {@code wechat.miniapp.log-verbose} 配置脱敏。</p>
     *
     * @param url 原始 URL
     * @return 脱敏后的 URL
     */
    private String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        // secret 永不打日志
        String masked = url.replaceAll("(?<=[&?]secret=)[^&]+", "***");
        // access_token 按配置脱敏
        if (!properties.isLogVerbose()) {
            masked = masked.replaceAll("(?<=[&?]access_token=)[^&]+", "***");
        }
        return masked;
    }

    /**
     * 脱敏微信响应体中的手机号等敏感字段。
     *
     * <p>非 verbose 模式时，递归遍历 JSON 替换敏感字段值为 {@code ***}。
     * JSON 解析失败时回退为正则替换，保证日志不丢失。</p>
     *
     * @param body 原始响应体
     * @return 脱敏后的响应体
     */
    private String maskResponseBody(String body) {
        if (properties.isLogVerbose() || isBlank(body)) {
            return body;
        }
        try {
            Map<?, ?> map = JSON.parseObject(body, Map.class);
            return JSON.toJSONString(maskSensitiveFields(map));
        } catch (JSONException e) {
            // JSON 解析失败时回退为正则脱敏，保证日志不丢失
            return body
                    .replaceAll("\"(phoneNumber|purePhoneNumber)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"***\"")
                    .replaceAll("\"countryCode\"\\s*:\\s*\"[^\"]*\"", "\"countryCode\":\"***\"");
        }
    }

    /**
     * 递归脱敏 Map 中的手机号等敏感字段。
     *
     * @param map 原始 Map
     * @return 脱敏后的 Map
     */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> maskSensitiveFields(Map<?, ?> map) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (isPhoneField(key) && value instanceof String) {
                result.put(key, "***");
            } else if (value instanceof Map) {
                result.put(key, maskSensitiveFields((Map<?, ?>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 判断是否为手机号相关字段。
     *
     * @param key JSON 键名
     * @return 是否为手机号字段
     */
    private boolean isPhoneField(String key) {
        return "phoneNumber".equals(key) || "purePhoneNumber".equals(key) || "countryCode".equals(key);
    }

    /**
     * 将微信请求体序列化为日志文本
     *
     * @param requestBody 请求体
     * @return JSON 文本
     */
    private String serializeRequestBody(Map<String, String> requestBody) {
        try {
            return JSON.toJSONString(requestBody);
        } catch (JSONException e) {
            throw new BusinessException("微信接口请求参数序列化失败", e);
        }
    }

    /**
     * 构建微信 HTTP 错误信息
     *
     * @param response HTTP 响应
     * @param serviceName 服务名称
     * @param body 响应体文本
     * @return 错误信息
     * @throws IOException 状态文本读取失败
     */
    private String buildWechatHttpErrorMessage(
            ClientHttpResponse response,
            String serviceName,
            String body
    ) throws IOException {
        String statusText = response.getStatusText();
        String statusMessage = "HTTP " + response.getStatusCode().value()
                + (isBlank(statusText) ? "" : " " + statusText);
        String wechatErrorMessage = parseWechatErrorMessage(body);
        if (isBlank(wechatErrorMessage)) {
            return serviceName + "请求失败：" + statusMessage;
        }
        return serviceName + "请求失败：" + statusMessage + "，" + wechatErrorMessage;
    }

    /**
     * 解析微信错误响应中的 errcode 和 errmsg
     *
     * @param body 响应体文本
     * @return 微信错误信息
     */
    private String parseWechatErrorMessage(String body) {
        if (isBlank(body)) {
            return "";
        }
        try {
            Map<?, ?> error = JSON.parseObject(body, Map.class);
            Object errcode = error.get("errcode");
            Object errmsg = error.get("errmsg");
            if (errcode != null && errmsg != null) {
                return "微信错误 " + errcode + "：" + errmsg;
            }
            if (errmsg != null) {
                return String.valueOf(errmsg);
            }
            if (errcode != null) {
                return "微信错误 " + errcode;
            }
        } catch (JSONException e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
        return "";
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
            return JSON.parseObject(body, responseType);
        } catch (JSONException e) {
            // 打印原始 body 和 Fastjson 具体原因，方便定位字段不匹配问题（body 已脱敏）
            log.error("微信接口响应解析失败 body={} targetType={}", maskResponseBody(body), responseType.getSimpleName(), e);
            throw new BusinessException("微信接口响应解析失败：" + e.getMessage());
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
