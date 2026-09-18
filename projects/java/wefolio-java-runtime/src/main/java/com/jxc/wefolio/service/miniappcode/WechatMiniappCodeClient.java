package com.jxc.wefolio.service.miniappcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.config.PortfolioMiniappCodeProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMiniappCodeImageMessage;
import com.jxc.wefolio.service.WechatAccessTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 微信不限量小程序码客户端；仅凭证拒绝时条件刷新并重试一次。 */
@Service
@Slf4j
public class WechatMiniappCodeClient {
    /** 官方接口地址，不接收外部指定地址。 */
    private static final String ENDPOINT = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token={token}";
    /** 微信协议字段 page。 */
    private static final String FIELD_PAGE = "page";
    /** 微信协议字段 scene。 */
    private static final String FIELD_SCENE = "scene";
    /** 微信协议字段 check_path。 */
    private static final String FIELD_CHECK_PATH = "check_path";
    /** 微信协议字段 env_version。 */
    private static final String FIELD_ENV_VERSION = "env_version";
    /** 微信协议字段 width。 */
    private static final String FIELD_WIDTH = "width";
    /** 微信协议字段 auto_color。 */
    private static final String FIELD_AUTO_COLOR = "auto_color";
    /** 微信协议字段 line_color。 */
    private static final String FIELD_LINE_COLOR = "line_color";
    /** 微信协议字段 r。 */
    private static final String FIELD_RED = "r";
    /** 微信协议字段 g。 */
    private static final String FIELD_GREEN = "g";
    /** 微信协议字段 b。 */
    private static final String FIELD_BLUE = "b";
    /** 微信协议字段 is_hyaline。 */
    private static final String FIELD_HYALINE = "is_hyaline";
    /** 微信协议字段 errcode。 */
    private static final String FIELD_ERROR_CODE = "errcode";
    /** 官方允许的场景字符集。 */
    private static final Pattern SCENE = Pattern.compile("[a-zA-Z0-9!#$&'()*+,/:;=?@._~-]{1,32}");
    /** 页面不包含查询参数或前导斜杠。 */
    private static final Pattern PAGE = Pattern.compile("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)+");
    /** 凭证失效错误码。 */
    private static final Set<Integer> TOKEN_ERRORS = Set.of(40001, 40014, 42001);
    /** 微信支持的环境。 */
    private static final Set<String> ENVIRONMENTS = Set.of("release", "trial", "develop");
    /** 原码尺寸，合成时保持一比一像素。 */
    static final int CODE_SIZE = 800;
    /** 共享凭证服务。 */
    private final WechatAccessTokenService tokens;
    /** 服务端生成参数。 */
    private final PortfolioMiniappCodeProperties properties;
    /** JSON 编解码器。 */
    private final ObjectMapper mapper;
    /** 带超时且不自动重定向的客户端。 */
    private final RestClient client;
    /** 构造生产客户端。 */
    @Autowired
    public WechatMiniappCodeClient(WechatAccessTokenService tokens, PortfolioMiniappCodeProperties properties,
                                  ObjectMapper mapper, WechatMiniappProperties wechat) {
        this(tokens, properties, mapper, createClient(wechat));
    }
    /** 注入 HTTP 边界，便于离线协议验证。 */
    WechatMiniappCodeClient(WechatAccessTokenService tokens, PortfolioMiniappCodeProperties properties,
                            ObjectMapper mapper, RestClient client) {
        this.tokens = tokens; this.properties = properties; this.mapper = mapper; this.client = client;
    }
    /** 构造具有连接和读取时限的微信专用客户端。 */
    private static RestClient createClient(WechatMiniappProperties config) {
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
    /** 验证服务端参数并获取完整原码，不输出微信原始错误文案或凭证。 */
    public byte[] generate(String page, String scene) {
        if (scene == null || !SCENE.matcher(scene).matches() || page == null || !PAGE.matcher(page).matches()
                || !ENVIRONMENTS.contains(properties.getEnvVersion())) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.INVALID_PARAMETERS);
        }
        String token = tokens.getAccessToken();
        for (int attempt = 0; attempt < 2; attempt++) {
            long started = System.nanoTime();
            try {
                Map<String, Object> request = Map.of(FIELD_PAGE, page, FIELD_SCENE, scene,
                        FIELD_CHECK_PATH, properties.isCheckPath(), FIELD_ENV_VERSION, properties.getEnvVersion(),
                        FIELD_WIDTH, CODE_SIZE, FIELD_AUTO_COLOR, false, FIELD_LINE_COLOR, Map.of(FIELD_RED, 0, FIELD_GREEN, 0, FIELD_BLUE, 0),
                        FIELD_HYALINE, false);
                log.info("微信小程序码请求 page={} sceneLength={} env={} attempt={}", page, scene.length(), properties.getEnvVersion(), attempt);
                byte[] bytes = client.post().uri(ENDPOINT, token).contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(request)).exchange((req, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
                            return response.getBody().readNBytes(MiniappCodeImages.MAX_BYTES + 1);
                        });
                if (bytes == null || bytes.length == 0 || bytes.length > MiniappCodeImages.MAX_BYTES) throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
                int first = 0;
                while (first < bytes.length && Character.isWhitespace(bytes[first])) first++;
                if (first < bytes.length && bytes[first] == '{') {
                    int error = mapper.readTree(bytes).path(FIELD_ERROR_CODE).asInt(-1);
                    log.warn("微信小程序码失败 errcode={} elapsedMs={}", error, (System.nanoTime() - started) / 1_000_000);
                    if (attempt == 0 && TOKEN_ERRORS.contains(error)) {
                        token = tokens.refreshAfterRejected(token);
                        continue;
                    }
                    throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
                }
                var image = MiniappCodeImages.decode(bytes);
                if (image.getWidth() != CODE_SIZE || image.getHeight() != CODE_SIZE) throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
                log.info("微信小程序码成功 bytes={} elapsedMs={}", bytes.length, (System.nanoTime() - started) / 1_000_000);
                return bytes;
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                // 不记录带 access_token 的请求异常与完整响应。
                log.warn("微信小程序码请求异常 type={}", exception.getClass().getSimpleName());
                throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
            }
        }
        throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
    }
}
