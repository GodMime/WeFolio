package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatMobileProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.WechatMobileMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 微信移动应用 OAuth 客户端 — 使用固定地址和超时，日志不记录 code、secret、token 或身份。 */
@Slf4j
@Service
public class RestWechatMobileClient implements WechatMobileClient {

    /** 官方移动应用授权兑换地址。 */
    private static final String TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    /** 授权兑换类型。 */
    private static final String GRANT_TYPE = "authorization_code";
    /** 微信错误码字段。 */
    private static final String ERROR_CODE = "errcode";
    /** 跨应用身份字段。 */
    private static final String UNION_ID = "unionid";
    /** 移动应用内部身份字段。 */
    private static final String OPEN_ID = "openid";
    /** 授权令牌字段。 */
    private static final String ACCESS_TOKEN = "access_token";
    /** 官方授权参数名。 */
    private static final String APP_ID_PARAM = "appid";
    /** 官方密钥参数名。 */
    private static final String SECRET_PARAM = "secret";
    /** 官方临时凭证参数名。 */
    private static final String CODE_PARAM = "code";
    /** 官方授权类型参数名。 */
    private static final String GRANT_TYPE_PARAM = "grant_type";
    /** 移动应用配置。 */
    private final WechatMobileProperties properties;
    /** 有界超时的 HTTP 客户端。 */
    private final RestClient client;

    /** 复用既有微信网络超时配置，但使用独立移动应用密钥。 */
    @Autowired
    public RestWechatMobileClient(WechatMobileProperties properties, WechatMiniappProperties networkProperties) {
        this(properties, WechatRestClientFactory.create(networkProperties));
    }

    /** 测试时注入不会连接真实微信的客户端。 */
    RestWechatMobileClient(WechatMobileProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** 仅将服务端验证的 UnionID 传递给账户关联层。 */
    @Override
    public String exchangeUnionId(String code) {
        if (!properties.isConfigured()) throw new BusinessException(WechatMobileMessage.NOT_ENABLED);
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new BusinessException(WechatMobileMessage.CODE_REQUIRED);
        }
        try {
            var uri = UriComponentsBuilder.fromUriString(TOKEN_URL)
                    .queryParam(APP_ID_PARAM, properties.getAppId())
                    .queryParam(SECRET_PARAM, properties.getAppSecret())
                    .queryParam(CODE_PARAM, code)
                    .queryParam(GRANT_TYPE_PARAM, GRANT_TYPE)
                    .build().encode().toUri();
            log.info("移动应用微信授权请求 appId={}, code=[REDACTED], secret=[REDACTED]", properties.getAppId());
            String raw = client.get().uri(uri).retrieve().body(String.class);
            JSONObject result = raw == null ? null : JSON.parseObject(raw);
            int errorCode = result == null ? -1 : result.getIntValue(ERROR_CODE);
            log.info("移动应用微信授权响应 errcode={}, hasUnionId={}", errorCode,
                    result != null && result.getString(UNION_ID) != null);
            if (result == null || errorCode != 0 || !hasText(result.getString(OPEN_ID))
                    || !hasText(result.getString(ACCESS_TOKEN))) {
                throw new BusinessException(WechatMobileMessage.EXCHANGE_FAILED);
            }
            String unionId = result.getString(UNION_ID);
            if (!hasText(unionId)) throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
            return unionId;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            // RestClient 异常可能携带含密钥的请求 URL，不能把原始异常写入日志或响应。
            log.warn("移动应用微信授权请求未完成，异常类型={}", exception.getClass().getSimpleName());
            throw new BusinessException(WechatMobileMessage.EXCHANGE_FAILED);
        }
    }

    /** 校验微信必须返回的身份字段。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
