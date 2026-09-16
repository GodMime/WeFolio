package com.jxc.wefolio.service.payment;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatAccessTokenService;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import com.jxc.wefolio.message.WechatVirtualPaymentMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

    /** 统一结果采用请求协议的沙箱环境编号。 */
    private static final int SANDBOX_ENVIRONMENT = 1;

    /** 官方初始化示例中的环境占位值，不代表已验证为现网。 */
    private static final int ORDER_UNDECLARED_ENVIRONMENT_TYPE = 0;

    /** 微信查单响应使用独立环境编号：1 为现网，2 为沙箱。 */
    private static final int ORDER_FORMAL_ENVIRONMENT_TYPE = 1;
    private static final int ORDER_SANDBOX_ENVIRONMENT_TYPE = 2;

    /** 默认分区。 */
    private static final String DEFAULT_ZONE_ID = "1";

    /** 虚拟支付币种。 */
    private static final String CURRENCY_TYPE = "CNY";

    /** 兼容微信文本支付成功状态。 */
    private static final String ORDER_STATUS_SUCCESS = "SUCCESS";

    /** 可供现有错误日志采集规则识别的未知应答事件。 */
    private static final String UNKNOWN_RESPONSE_EVENT = "WECHAT_VIRTUAL_PAYMENT_RESPONSE_UNKNOWN";

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

    /** 微信响应字段。 */
    private static final String ERROR_CODE = "errcode";
    private static final String BALANCE = "balance";
    private static final String PRESENT_BALANCE = "present_balance";
    private static final String USED_PRESENT_AMOUNT = "used_present_amount";
    private static final String ORDER = "order";
    private static final String BUY_QUANTITY = "buy_quantity";
    private static final String PAID_FEE = "paid_fee";
    private static final String PAY_AMOUNT = "pay_amount";
    private static final String AMOUNT = "amount";
    private static final String ENVIRONMENT = "env";
    private static final String ORDER_ENVIRONMENT_TYPE = "env_type";

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

    /** 本进程累计未知应答数，重启后归零；各接口由日志的 operation/path 区分。 */
    private final AtomicLong unknownResponseCount = new AtomicLong();

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
        body.put(ENVIRONMENT, FORMAL_ENVIRONMENT);
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
            WechatVirtualPaymentResult result = parse(response, path, operation, referenceNo, userId, retryCount);
            if (result.errorType() == WechatVirtualPaymentErrorType.UNKNOWN) {
                log.error("event={} operation={} path={} httpStatus={} errcode={} unknownResponseCount={}",
                        UNKNOWN_RESPONSE_EVENT, operation, path, response.httpStatus(), result.errorCode(),
                        unknownResponseCount.incrementAndGet());
            }
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
            String path,
            String operation,
            String referenceNo,
            Long userId,
            int retryCount
    ) {
        try {
            JSONObject json = JSON.parseObject(response.body());
            Integer errorCode = json == null || json.get(ERROR_CODE) == null ? null
                    : Math.toIntExact(integerValue(json.get(ERROR_CODE)));
            WechatVirtualPaymentErrorType errorType = errorClassifier.classify(errorCode, response.httpStatus());
            JSONObject order = json == null ? null : json.getJSONObject(ORDER);
            // 重复成功没有余额字段，必须由处理器另行查询，不能按普通成功结构校验。
            if (errorType == WechatVirtualPaymentErrorType.SUCCESS) {
                validateSuccessPayload(path, json, order);
            }
            return new WechatVirtualPaymentResult(
                    errorCode,
                    json == null ? null : json.getString("errmsg"),
                    errorType,
                    longValue(json, BALANCE),
                    longValue(json, PRESENT_BALANCE),
                    longValue(json, USED_PRESENT_AMOUNT),
                    normalizeOrderStatus(order, json),
                    firstLong(order, json, BUY_QUANTITY),
                    firstLong(order, json, PAID_FEE, PAY_AMOUNT, AMOUNT),
                    response.httpStatus(),
                    firstString(order, json, "openid"),
                    QUERY_ORDER_PATH.equals(path)
                            ? normalizeOrderEnvironment(order, json)
                            : firstInteger(order, json, ENVIRONMENT),
                    firstString(order, json, "order_id", "out_trade_no")
            );
        } catch (JSONException | ArithmeticException exception) {
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

    /** 校验普通成功响应必需的字段，避免缺失或非法数值被转换成零。 */
    private void validateSuccessPayload(String path, JSONObject json, JSONObject order) {
        if (QUERY_ORDER_PATH.equals(path)) {
            String status = normalizeOrderStatus(order, json);
            if (status == null) {
                throw new JSONException(WechatVirtualPaymentMessage.ORDER_STATUS_MISSING_MESSAGE);
            }
            if (ORDER_STATUS_PAID.equals(status) || ORDER_STATUS_SUCCESS.equals(status)) {
                if (firstLong(order, json, PAID_FEE, PAY_AMOUNT, AMOUNT) <= 0L) {
                    throw new JSONException(WechatVirtualPaymentMessage.ORDER_AMOUNT_INVALID_MESSAGE);
                }
            }
            if (firstLong(order, json, BUY_QUANTITY) < 0L) {
                throw new JSONException(WechatVirtualPaymentMessage.ORDER_QUANTITY_INVALID_MESSAGE);
            }
            return;
        }
        long balance = requiredNonNegativeInteger(json, BALANCE);
        if (CURRENCY_PAY_PATH.equals(path)) {
            // 官方扣币应答只有总余额及本次赠送币用量，完整余额由处理器在成功后补查。
            requiredNonNegativeInteger(json, USED_PRESENT_AMOUNT);
            if (!json.containsKey(PRESENT_BALANCE)) {
                return;
            }
        }
        long presentBalance = requiredNonNegativeInteger(json, PRESENT_BALANCE);
        if (presentBalance > balance) {
            throw new JSONException(WechatVirtualPaymentMessage.PRESENT_BALANCE_INVALID_MESSAGE);
        }
    }

    /** 读取存在且非负的整数字段。 */
    private long requiredNonNegativeInteger(JSONObject json, String key) {
        if (json == null || json.get(key) == null) {
            throw new JSONException(WechatVirtualPaymentMessage.RESPONSE_FIELD_MISSING_MESSAGE + key);
        }
        long value = integerValue(json.get(key));
        if (value < 0L) {
            throw new JSONException(WechatVirtualPaymentMessage.RESPONSE_FIELD_NEGATIVE_MESSAGE + key);
        }
        return value;
    }

    /** 不接受字符串、浮点、布尔或超出长整数范围的数字。 */
    private long integerValue(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        throw new JSONException(WechatVirtualPaymentMessage.RESPONSE_INTEGER_REQUIRED_MESSAGE);
    }

    /** 安全读取 JSON 长整数。 */
    private long longValue(JSONObject json, String key) {
        Object value = json == null ? null : json.get(key);
        return value == null ? 0L : integerValue(value);
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
            Object value = nested == null ? null : nested.get(key);
            if (value == null && root != null) {
                value = root.get(key);
            }
            if (value != null) {
                return integerValue(value);
            }
        }
        return 0L;
    }

    /** 优先从订单对象读取整数。 */
    private Integer firstInteger(JSONObject nested, JSONObject root, String key) {
        Object value = nested == null ? null : nested.get(key);
        if (value == null && root != null) {
            value = root.get(key);
        }
        return value == null ? null : Math.toIntExact(integerValue(value));
    }

    /** 将查单环境编号转换为既有内部值；保留旧 env，并拒绝冲突的双字段。 */
    private Integer normalizeOrderEnvironment(JSONObject order, JSONObject root) {
        Integer legacyEnvironment = firstInteger(order, root, ENVIRONMENT);
        Integer environmentType = firstInteger(order, root, ORDER_ENVIRONMENT_TYPE);
        if (environmentType == null || environmentType == ORDER_UNDECLARED_ENVIRONMENT_TYPE) {
            // 可选字段缺失或官方初始化示例使用 0 占位时，只沿用旧字段，不推断正式环境。
            return legacyEnvironment;
        }
        int environment = switch (environmentType) {
            case ORDER_FORMAL_ENVIRONMENT_TYPE -> FORMAL_ENVIRONMENT;
            case ORDER_SANDBOX_ENVIRONMENT_TYPE -> SANDBOX_ENVIRONMENT;
            default -> throw new JSONException(WechatVirtualPaymentMessage.ORDER_ENVIRONMENT_INVALID_MESSAGE);
        };
        if (legacyEnvironment != null && legacyEnvironment != environment) {
            throw new JSONException(WechatVirtualPaymentMessage.ORDER_ENVIRONMENT_CONFLICT_MESSAGE);
        }
        return environment;
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
            // 官方 5 为订单已退款，8 为用户退款完成，均不再作为待支付状态。
            case 5, 8 -> ORDER_STATUS_REFUNDED;
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
