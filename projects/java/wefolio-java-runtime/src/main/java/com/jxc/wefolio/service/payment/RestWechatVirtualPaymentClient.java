package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatAccessTokenService;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信虚拟支付 REST 客户端 — 固定正式环境，并保证签名正文与发送正文完全一致。
 */
@Slf4j
@Service
public class RestWechatVirtualPaymentClient implements WechatVirtualPaymentClient {

    /** 微信 API 根地址。 */
    private static final String API_BASE_URL = "https://api.weixin.qq.com";

    /** 正式环境编号。 */
    private static final int FORMAL_ENVIRONMENT = 0;

    /** 默认分区。 */
    private static final String DEFAULT_ZONE_ID = "1";

    /** 虚拟支付币种。 */
    private static final String CURRENCY_TYPE = "CNY";

    /** 已支付订单的标准化状态。 */
    private static final String ORDER_STATUS_PAID = "PAID";

    /** 已退款订单的标准化状态。 */
    private static final String ORDER_STATUS_REFUNDED = "REFUND";

    /** 已关闭订单的标准化状态。 */
    private static final String ORDER_STATUS_CLOSED = "CLOSED";

    /** 退款失败订单的标准化状态。 */
    private static final String ORDER_STATUS_REFUND_FAILED = "REFUND_FAILED";

    /** 尚未支付订单的标准化状态。 */
    private static final String ORDER_STATUS_PENDING = "PENDING";

    private static final String QUERY_USER_BALANCE_PATH = "/xpay/query_user_balance";
    private static final String CURRENCY_PAY_PATH = "/xpay/currency_pay";
    private static final String PRESENT_CURRENCY_PATH = "/xpay/present_currency";
    private static final String QUERY_ORDER_PATH = "/xpay/query_order";

    /** 微信小程序配置。 */
    private final WechatMiniappProperties miniappProperties;

    /** 微信虚拟支付配置。 */
    private final WechatVirtualPaymentProperties virtualPaymentProperties;

    /** 共享 AccessToken 服务。 */
    private final WechatAccessTokenService wechatAccessTokenService;

    /** 微信虚拟支付签名器。 */
    private final WechatVirtualPaymentSigner signer;

    /** 微信错误分类器。 */
    private final WechatVirtualPaymentErrorClassifier errorClassifier;

    /** 微信交互日志脱敏组件。 */
    private final WechatInteractionLogSanitizer logSanitizer;

    /** 强制使用 HTTP/1.1 的 REST 客户端。 */
    private RestClient restClient;

    /** 创建微信虚拟支付 REST 客户端。 */
    public RestWechatVirtualPaymentClient(
            WechatMiniappProperties miniappProperties,
            WechatVirtualPaymentProperties virtualPaymentProperties,
            WechatAccessTokenService wechatAccessTokenService,
            WechatVirtualPaymentSigner signer,
            WechatVirtualPaymentErrorClassifier errorClassifier,
            WechatInteractionLogSanitizer logSanitizer
    ) {
        this.miniappProperties = miniappProperties;
        this.virtualPaymentProperties = virtualPaymentProperties;
        this.wechatAccessTokenService = wechatAccessTokenService;
        this.signer = signer;
        this.errorClassifier = errorClassifier;
        this.logSanitizer = logSanitizer;
        Duration timeout = virtualPaymentProperties.getRequestTimeout() == null
                ? Duration.ofSeconds(5L)
                : virtualPaymentProperties.getRequestTimeout();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(timeout)
                        .build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /** 查询用户微信代币余额。 */
    @Override
    public WechatVirtualPaymentResult queryUserBalance(WechatBalanceQueryRequest request) {
        Map<String, Object> body = commonUserBody(
                request.openid(), request.timestampSeconds(), request.userIp());
        return invoke(QUERY_USER_BALANCE_PATH, body, request.sessionKey(),
                "查询余额", request.referenceNo(), request.userId(), 0L);
    }

    /** 扣减用户微信代币。 */
    @Override
    public WechatVirtualPaymentResult currencyPay(WechatCurrencyPayRequest request) {
        Map<String, Object> body = commonUserBody(
                request.openid(), request.timestampSeconds(), request.userIp());
        body.put("amount", request.amount());
        body.put("order_id", request.orderId());
        body.put("currency_type", CURRENCY_TYPE);
        return invoke(CURRENCY_PAY_PATH, body, request.sessionKey(),
                "扣减代币", request.taskNo(), request.userId(), request.amount());
    }

    /** 赠送用户微信代币。 */
    @Override
    public WechatVirtualPaymentResult presentCurrency(WechatPresentCurrencyRequest request) {
        Map<String, Object> body = commonIdentityBody(request.openid(), request.timestampSeconds());
        body.put("amount", request.amount());
        body.put("order_id", request.orderId());
        body.put("currency_type", CURRENCY_TYPE);
        return invoke(PRESENT_CURRENCY_PATH, body, null,
                "赠送代币", request.localOrderNo(), request.userId(), request.amount());
    }

    /** 查询微信虚拟支付订单。 */
    @Override
    public WechatVirtualPaymentResult queryOrder(WechatQueryOrderRequest request) {
        Map<String, Object> body = commonUserBody(
                request.openid(), request.timestampSeconds(), request.userIp());
        body.put("order_id", request.orderId());
        return invoke(QUERY_ORDER_PATH, body, request.sessionKey(),
                "查询订单", request.localOrderNo(), request.userId(), 0L);
    }

    /** 构造包含可信客户端 IP 的用户请求正文。 */
    private Map<String, Object> commonUserBody(String openid, long timestampSeconds, String userIp) {
        Map<String, Object> body = commonIdentityBody(openid, timestampSeconds);
        body.put("user_ip", userIp);
        return body;
    }

    /** 构造固定顺序的公共身份请求正文。 */
    private Map<String, Object> commonIdentityBody(String openid, long timestampSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", miniappProperties.getAppId());
        body.put("offer_id", virtualPaymentProperties.getOfferId());
        body.put("openid", openid);
        body.put("ts", timestampSeconds);
        body.put("zone_id", DEFAULT_ZONE_ID);
        body.put("env", FORMAL_ENVIRONMENT);
        return body;
    }

    /**
     * 序列化一次并执行远端请求。仅 AccessToken 明确失效时原样重放一次。
     */
    private WechatVirtualPaymentResult invoke(
            String path,
            Map<String, Object> requestBody,
            String sessionKey,
            String operation,
            String referenceNo,
            Long userId,
            long amount
    ) {
        String body = JSON.toJSONString(requestBody);
        String accessToken = wechatAccessTokenService.getAccessToken();
        WechatVirtualPaymentResult first = send(
                path, body, sessionKey, accessToken, operation, referenceNo, userId, amount, 0);
        if (first.errorType() != WechatVirtualPaymentErrorType.ACCESS_TOKEN_REJECTED) {
            return first;
        }
        String refreshedToken = wechatAccessTokenService.refreshAfterRejected(accessToken);
        return send(path, body, sessionKey, refreshedToken, operation, referenceNo, userId, amount, 1);
    }

    /** 执行单次 HTTP 调用。 */
    private WechatVirtualPaymentResult send(
            String path,
            String body,
            String sessionKey,
            String accessToken,
            String operation,
            String referenceNo,
            Long userId,
            long amount,
            int retryCount
    ) {
        String paySignature = signer.paySignature(virtualPaymentProperties.getAppKey(), path, body);
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(API_BASE_URL + path)
                .queryParam("access_token", accessToken)
                .queryParam("pay_sig", paySignature);
        if (sessionKey != null && !sessionKey.isBlank()) {
            uriBuilder.queryParam("signature", signer.userSignature(sessionKey, body));
        }
        String url = uriBuilder.build().toUriString();
        long startedAt = System.nanoTime();
        log.info("微信交互请求 operation={} referenceNo={} userId={} method=POST path={} request={} retryCount={}",
                operation, referenceNo, userId, path, logSanitizer.sanitizeJson(body), retryCount);
        try {
            HttpCallResult response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, httpResponse) -> new HttpCallResult(
                            httpResponse.getStatusCode().value(),
                            new String(httpResponse.getBody().readAllBytes())));
            log.info("微信交互响应 operation={} referenceNo={} userId={} httpStatus={} response={} "
                            + "elapsedMs={} retryCount={}",
                    operation, referenceNo, userId, response.httpStatus(),
                    logSanitizer.sanitizeJson(response.body()), elapsedMillis(startedAt), retryCount);
            WechatVirtualPaymentResult result = parse(response, operation, referenceNo, userId, retryCount);
            return result;
        } catch (RuntimeException exception) {
            log.warn("微信交互异常 operation={} referenceNo={} userId={} elapsedMs={} retryCount={} "
                            + "exceptionType={} message={}",
                    operation, referenceNo, userId, elapsedMillis(startedAt), retryCount,
                    exception.getClass().getSimpleName(), logSanitizer.sanitizeText(exception.getMessage()));
            return new WechatVirtualPaymentResult(null, "微信虚拟支付请求异常",
                    WechatVirtualPaymentErrorType.TRANSIENT, 0L, 0L, 0L,
                    null, 0L, 0L, 0);
        }
    }

    /** 解析并分类微信响应，不保留完整原始报文。 */
    private WechatVirtualPaymentResult parse(
            HttpCallResult response,
            String operation,
            String referenceNo,
            Long userId,
            int retryCount
    ) {
        try {
            JSONObject json = JSON.parseObject(response.body());
            Integer errorCode = json == null ? null : json.getInteger("errcode");
            JSONObject order = json == null ? null : json.getJSONObject("order");
            return new WechatVirtualPaymentResult(
                    errorCode,
                    json == null ? null : json.getString("errmsg"),
                    errorClassifier.classify(errorCode, response.httpStatus()),
                    longValue(json, "balance"),
                    longValue(json, "present_balance"),
                    longValue(json, "used_present_amount"),
                    normalizeOrderStatus(order, json),
                    firstLong(order, json, "buy_quantity"),
                    firstLong(order, json, "paid_fee", "pay_amount", "amount"),
                    response.httpStatus(),
                    firstString(order, json, "openid"),
                    firstInteger(order, json, "env"),
                    firstString(order, json, "order_id", "out_trade_no")
            );
        } catch (JSONException exception) {
            log.warn("微信交互响应解析失败 operation={} referenceNo={} userId={} httpStatus={} response={} "
                            + "retryCount={} exceptionType={}",
                    operation, referenceNo, userId, response.httpStatus(),
                    logSanitizer.sanitizeText(response.body()), retryCount,
                    exception.getClass().getSimpleName());
            return new WechatVirtualPaymentResult(null, "微信虚拟支付响应格式异常",
                    errorClassifier.classify(null, response.httpStatus()),
                    0L, 0L, 0L, null, 0L, 0L, response.httpStatus());
        }
    }

    /** 安全读取 JSON 长整数。 */
    private long longValue(JSONObject json, String key) {
        Long value = json == null ? null : json.getLong(key);
        return value == null ? 0L : value;
    }

    /** 优先从订单对象读取非空文本，并兼容顶层字段。 */
    private String firstString(JSONObject nested, JSONObject root, String... keys) {
        for (String key : keys) {
            String value = nested == null ? null : nested.getString(key);
            if (value == null && root != null) {
                value = root.getString(key);
            }
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 优先从订单对象读取长整数。 */
    private long firstLong(JSONObject nested, JSONObject root, String... keys) {
        for (String key : keys) {
            Long value = nested == null ? null : nested.getLong(key);
            if (value == null && root != null) {
                value = root.getLong(key);
            }
            if (value != null) {
                return value;
            }
        }
        return 0L;
    }

    /** 优先从订单对象读取整数。 */
    private Integer firstInteger(JSONObject nested, JSONObject root, String key) {
        Integer value = nested == null ? null : nested.getInteger(key);
        return value == null && root != null ? root.getInteger(key) : value;
    }

    /** 将微信查单数字状态转换为充值业务可识别的稳定状态。 */
    private String normalizeOrderStatus(JSONObject order, JSONObject root) {
        String textualStatus = firstString(order, root, "order_status", "order_state");
        if (textualStatus != null) {
            return textualStatus;
        }
        String rawStatus = firstString(order, root, "status");
        if (rawStatus == null) {
            return null;
        }
        int numericStatus;
        try {
            numericStatus = Integer.parseInt(rawStatus);
        } catch (NumberFormatException exception) {
            return rawStatus;
        }
        return switch (numericStatus) {
            case 2, 3, 4 -> ORDER_STATUS_PAID;
            case 5 -> ORDER_STATUS_REFUNDED;
            case 6 -> ORDER_STATUS_CLOSED;
            case 7 -> ORDER_STATUS_REFUND_FAILED;
            default -> ORDER_STATUS_PENDING;
        };
    }

    /** 计算调用耗时毫秒数。 */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** 单次 HTTP 调用结果。 */
    private record HttpCallResult(int httpStatus, String body) {
    }
}
