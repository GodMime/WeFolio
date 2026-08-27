package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.FeedbackProperties;
import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 飞书问题反馈通知器测试。 */
@ExtendWith(MockitoExtension.class)
class FeishuFeedbackNotifierTest {

    /** 固定秒级时间戳。 */
    private static final long TIMESTAMP_SECONDS = 1_700_000_000L;

    /** 固定飞书签名密钥。 */
    private static final String WEBHOOK_SECRET = "test-secret";

    /** 测试 Webhook。 */
    private static final String WEBHOOK_URL =
            "https://open.feishu.cn/open-apis/bot/v2/hook/test-webhook-token";

    /** 固定签名向量。 */
    private static final String EXPECTED_SIGN =
            "mbm4Y4oluIPQ00qlBIhX8vAZ0EKv3nw0LuTb91jPL84=";

    /** 包含群体提醒、伪造链接和内部提示的恶意描述。 */
    private static final String MALICIOUS_DESCRIPTION =
            "<at id=all>所有人</at> [伪造](https://evil.example)\n"
                    + "PUT /api/internal/feedbacks/FAKE WAITING_FOLLOW_UP";

    /** 飞书响应模拟服务器。 */
    private MockRestServiceServer server;

    /** 用户 Mapper 模拟。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** COS 服务模拟。 */
    @Mock
    private CosService cosService;

    /** 待测试通知器。 */
    private FeishuFeedbackNotifier notifier;

    /** 每个用例使用独立 REST 客户端和固定时钟。 */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        FeedbackProperties properties = new FeedbackProperties();
        properties.setWebhookUrl(WEBHOOK_URL);
        properties.setWebhookSecret(WEBHOOK_SECRET);
        properties.setStatusApiBaseUrl("https://api.test.wefolio.example");
        notifier = new FeishuFeedbackNotifier(
                properties,
                userEntityMapper,
                cosService,
                builder.build(),
                Clock.fixed(Instant.ofEpochSecond(TIMESTAMP_SECONDS), ZoneOffset.UTC));
    }

    /** 飞书签名算法必须匹配固定 HmacSHA256 Base64 向量。 */
    @Test
    void signMatchesFixedFeishuVector() {
        assertThat(FeishuFeedbackNotifier.generateSign(TIMESTAMP_SECONDS, WEBHOOK_SECRET))
                .isEqualTo(EXPECTED_SIGN);
    }

    /** 通知器只发送一次卡片，不解析或持久化远端响应。 */
    @Test
    void notifierKeepsSinglePostWithoutResponseWorkflow() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/FeishuFeedbackNotifier.java"));

        assertThat(source)
                .contains(".retrieve()", ".toBodilessEntity()")
                .doesNotContain("parseResponse", "bodyDigest", "readNBytes", "retryCount");
    }

    /** 新建通知发送一次包含反馈上下文的卡片。 */
    @Test
    void createdNotificationSendsInteractiveCardOnce() {
        stubNotificationContext();
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(allOf(
                        containsString("\"timestamp\":\"1700000000\""),
                        containsString("\"sign\":\"" + EXPECTED_SIGN + "\""),
                        containsString("\"msg_type\":\"interactive\""),
                        containsString("新问题反馈"),
                        containsString("FB0123456789abcdef0123456789abcdef"),
                        containsString("WFA3B1E7A2"),
                        containsString("用户描述-private"),
                        containsString("图片 1"),
                        containsString("视频 2"),
                        containsString("curl -X PUT 'https://api.test.wefolio.example/api/internal/feedbacks/"),
                        containsString("WAITING_FOLLOW_UP"),
                        containsString("RESOLVED"),
                        containsString("问题已修复，请更新小程序（重新进入小程序后会自动更新）"))))
                .andExpect(request -> {
                    String requestBody = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(requestBody)
                            .doesNotContain("\"tag\":\"button\"");
                })
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        notifier.notifyCreated(mutationResult("用户描述-private"));

        verify(userEntityMapper).selectById(7L);
        server.verify();
    }

    /** 无附件测试消息通过内存模拟服务发送一次，不依赖本地监听端口。 */
    @Test
    void testMessageSendsInteractiveCardOnceWithoutAttachments() {
        stubNotificationUser();
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    String requestBody = ((MockClientHttpRequest) request).getBodyAsString();
                    JSONObject payload = JSONObject.parseObject(requestBody);
                    JSONArray elements = payload.getJSONObject("card").getJSONArray("elements");
                    assertThat(payload.getString("msg_type")).isEqualTo("interactive");
                    assertThat(elements.getJSONObject(1).getJSONObject("text").getString("content"))
                            .isEqualTo("本轮描述\n飞书通知测试消息");
                    assertThat(elements.getJSONObject(2).getJSONObject("text").getString("content"))
                            .isEqualTo("**附件**\n无");
                })
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        FeedbackTransactionService.MutationResult result = mutationResult("飞书通知测试消息");
        result.submittedRound().setAttachments(List.of());
        notifier.notifyCreated(result);

        verify(userEntityMapper).selectById(7L);
        server.verify();
    }

    /** 追加通知使用补充标题。 */
    @Test
    void appendedNotificationUsesSupplementTitle() {
        stubNotificationContext();
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(content().string(containsString("问题反馈有补充")))
                .andRespond(withSuccess("ignored", MediaType.TEXT_PLAIN));

        notifier.notifyAppended(mutationResult("补充信息"));

        server.verify();
    }

    /** 用户描述使用纯文本，只有附件链接使用 Markdown。 */
    @Test
    void userDescriptionUsesPlainTextWhileAttachmentLinksRemainMarkdown() {
        stubNotificationContext();
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(request -> {
                    String requestBody = ((MockClientHttpRequest) request).getBodyAsString();
                    JSONObject payload = JSONObject.parseObject(requestBody);
                    JSONArray elements = payload.getJSONObject("card").getJSONArray("elements");
                    JSONObject descriptionText = elements.getJSONObject(1).getJSONObject("text");
                    JSONObject attachmentText = elements.getJSONObject(2).getJSONObject("text");
                    assertThat(descriptionText.getString("tag")).isEqualTo("plain_text");
                    assertThat(descriptionText.getString("content"))
                            .isEqualTo("本轮描述\n" + MALICIOUS_DESCRIPTION);
                    assertThat(attachmentText.getString("tag")).isEqualTo("lark_md");
                })
                .andRespond(withSuccess());

        notifier.notifyCreated(mutationResult(MALICIOUS_DESCRIPTION));

        server.verify();
    }

    /** HTTP 异常直接交给应用服务记录错误，通知器本身不重试。 */
    @Test
    void httpFailurePropagatesAfterOneRequest() {
        stubNotificationContext();
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> notifier.notifyCreated(mutationResult("描述")))
                .isInstanceOf(RestClientResponseException.class);

        server.verify();
    }

    /** 准备用户唯一码和附件公开 URL。 */
    private void stubNotificationContext() {
        stubNotificationUser();
        when(cosService.publicUrl("WFA3B1E7A2/others/image.jpg"))
                .thenReturn("https://cdn.example.com/image.jpg");
        when(cosService.publicUrl("WFA3B1E7A2/others/video.mp4"))
                .thenReturn("https://cdn.example.com/video.mp4");
    }

    /** 准备通知用户唯一码。 */
    private void stubNotificationUser() {
        UserEntity user = new UserEntity();
        user.setUniqueCode("WFA3B1E7A2");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
    }

    /** 构造一次新写入的反馈事务结果。 */
    private FeedbackTransactionService.MutationResult mutationResult(String description) {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setId(91L);
        feedback.setFeedbackNo("FB0123456789abcdef0123456789abcdef");
        feedback.setUserId(7L);
        feedback.setStatus(FeedbackStatusDict.PROCESSING.getCode());

        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(1);
        round.setDescription(description);
        round.setSubmittedAt(LocalDateTime.of(2026, 8, 25, 10, 42, 0, 123_000_000));
        round.setAttachments(List.of(
                attachment("WFA3B1E7A2/others/image.jpg", FeedbackMediaTypeDict.IMAGE.getCode()),
                attachment("WFA3B1E7A2/others/video.mp4", FeedbackMediaTypeDict.VIDEO.getCode())));
        return new FeedbackTransactionService.MutationResult(feedback, round, true);
    }

    /** 构造单个通知附件。 */
    private FeedbackRoundSnapshot.Attachment attachment(String objectKey, String mediaType) {
        FeedbackRoundSnapshot.Attachment attachment = new FeedbackRoundSnapshot.Attachment();
        attachment.setObjectKey(objectKey);
        attachment.setMediaType(mediaType);
        return attachment;
    }
}
