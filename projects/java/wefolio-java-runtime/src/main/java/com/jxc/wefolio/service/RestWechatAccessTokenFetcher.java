package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.WechatAccessTokenResponse;
import com.jxc.wefolio.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;


/**
 * 微信接口调用凭证 REST 获取器。
 */
@Slf4j
@Service
public class RestWechatAccessTokenFetcher implements WechatAccessTokenFetcher {

    /** 微信 access_token 地址。 */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** 微信小程序配置。 */
    private final WechatMiniappProperties properties;

    /** 微信交互日志脱敏组件。 */
    private final WechatInteractionLogSanitizer logSanitizer;

    /** 强制使用 HTTP/1.1 的 REST 客户端。 */
    private final RestClient restClient;

    /**
     * 创建凭证获取器。
     *
     * @param properties   微信小程序配置
     * @param logSanitizer 微信交互日志脱敏组件
     */
    public RestWechatAccessTokenFetcher(
            WechatMiniappProperties properties,
            WechatInteractionLogSanitizer logSanitizer
    ) {
        this.properties = properties;
        this.logSanitizer = logSanitizer;
        this.restClient = WechatRestClientFactory.create(properties);
    }

    /**
     * 从微信获取新的接口调用凭证。
     *
     * @return 微信凭证响应
     */
    @Override
    public WechatAccessTokenResponse fetch() {
        String url = UriComponentsBuilder.fromUriString(ACCESS_TOKEN_URL)
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .build()
                .toUriString();
        long startedAt = System.nanoTime();
        log.info("微信交互请求 operation=获取AccessToken referenceNo=null userId=null method=GET path={} "
                        + "request={} retryCount=0",
                logSanitizer.sanitizeUrl(url), "{}");
        String body;
        int httpStatus;
        try {
            ResponseEntity<String> response = restClient.get().uri(url).retrieve().toEntity(String.class);
            body = response.getBody();
            httpStatus = response.getStatusCode().value();
            log.info("微信交互响应 operation=获取AccessToken referenceNo=null userId=null httpStatus={} "
                            + "response={} elapsedMs={} retryCount=0",
                    httpStatus, logSanitizer.sanitizeJson(body), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            log.warn("微信交互异常 operation=获取AccessToken referenceNo=null userId=null elapsedMs={} "
                            + "retryCount=0 exceptionType={} message={}",
                    elapsedMillis(startedAt), exception.getClass().getSimpleName(),
                    logSanitizer.sanitizeText(exception.getMessage()));
            throw new BusinessException("微信 access_token 服务请求失败");
        }
        try {
            WechatAccessTokenResponse response = JSON.parseObject(body, WechatAccessTokenResponse.class);
            return response;
        } catch (JSONException exception) {
            log.warn("微信交互响应解析失败 operation=获取AccessToken referenceNo=null userId=null "
                            + "httpStatus={} response={} retryCount=0 exceptionType={}",
                    httpStatus, logSanitizer.sanitizeText(body), exception.getClass().getSimpleName());
            throw new BusinessException("微信 access_token 服务响应格式异常");
        }
    }

    /** 计算调用耗时毫秒数。 */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
