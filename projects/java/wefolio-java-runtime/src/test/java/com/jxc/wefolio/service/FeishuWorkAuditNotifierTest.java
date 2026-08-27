package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.AdminPointProperties;
import com.jxc.wefolio.config.WorkManualAuditProperties;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.WorkManualAuditMessage;
import com.jxc.wefolio.model.WorkManualAuditSubmission;
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
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 作品最终轮人工审核飞书通知器测试。 */
@ExtendWith(MockitoExtension.class)
class FeishuWorkAuditNotifierTest {

    /** 固定飞书签名时间戳。 */
    private static final long TIMESTAMP_SECONDS = 1_700_000_000L;

    /** 测试签名密钥。 */
    private static final String WEBHOOK_SECRET = "test-secret";

    /** 测试 Webhook 地址。 */
    private static final String WEBHOOK_URL =
            "https://open.feishu.cn/open-apis/bot/v2/hook/test-work-audit-token";

    /** 固定人工审核编号。 */
    private static final String MANUAL_AUDIT_NO =
            "WA20260827153042A7K2Q9";

    /** 固定时间戳和密钥对应的精确签名。 */
    private static final String EXPECTED_SIGN =
            "mbm4Y4oluIPQ00qlBIhX8vAZ0EKv3nw0LuTb91jPL84=";

    /** 回传接口使用的完整内部密钥。 */
    private static final String ADMIN_POINT_SECRET = "admin-point-secret";

    /** 模拟飞书 HTTP 服务。 */
    private MockRestServiceServer server;

    /** 用户 Mapper 模拟。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** COS 服务模拟。 */
    @Mock
    private CosService cosService;

    /** 作品人工审核配置。 */
    private WorkManualAuditProperties properties;

    /** 内部回传密钥配置。 */
    private AdminPointProperties adminPointProperties;

    /** 绑定模拟服务的 REST 客户端。 */
    private RestClient restClient;

    /** 初始化模拟 HTTP 服务和专用通知配置。 */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        properties = new WorkManualAuditProperties();
        properties.setWebhookUrl(WEBHOOK_URL);
        properties.setWebhookSecret(WEBHOOK_SECRET);
        properties.setReviewApiBaseUrl("https://api.test.wefolio.example");
        adminPointProperties = new AdminPointProperties();
        adminPointProperties.setSecret(ADMIN_POINT_SECRET);
    }

    /** 固定时间戳与密钥必须生成精确飞书签名。 */
    @Test
    void signMatchesFixedFeishuVector() {
        assertThat(FeishuWorkAuditNotifier.generateSign(TIMESTAMP_SECONDS, WEBHOOK_SECRET))
                .isEqualTo(EXPECTED_SIGN);
    }

    /** 提交通知必须只发送一次完整交互卡片。 */
    @Test
    void submittedNotificationSendsInteractiveCardOnce() {
        stubActiveUser();
        when(cosService.publicUrl("WFA3B1E7A2/work/video/demo.mp4"))
                .thenReturn("https://cdn.example.com/work.mp4");
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    String requestBody = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(requestBody)
                            .contains("作品最终轮待人工审核")
                            .contains(MANUAL_AUDIT_NO)
                            .contains("WFA3B1E7A2")
                            .contains("草坪婚礼快剪")
                            .contains("第 3/3 轮")
                            .contains("上一轮审核原因")
                            .contains("https://cdn.example.com/work.mp4")
                            .contains("-H 'X-Admin-Point-Secret: " + ADMIN_POINT_SECRET + "'")
                            .contains("\\\"status\\\":\\\"PASSED\\\"")
                            .contains("\\\"status\\\":\\\"REJECTED\\\"")
                            .doesNotContain("<<'JSON'", "--data-binary @-", "\\\"tag\\\":\\\"button\\\"");
                    assertThat(StringUtils.countOccurrencesOf(
                            requestBody,
                            "-H 'X-Admin-Point-Secret: " + ADMIN_POINT_SECRET + "'"))
                            .isEqualTo(2);
                    JSONObject payload = JSONObject.parseObject(requestBody);
                    assertThat(payload.getString("timestamp")).isEqualTo("1700000000");
                    assertThat(payload.getString("sign")).isEqualTo(EXPECTED_SIGN);
                    assertThat(payload.getString("msg_type")).isEqualTo("interactive");
                    assertTextTags(payload.getJSONObject("card").getJSONArray("elements"));
                })
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        notifier().notifySubmitted(submission());

        verify(userEntityMapper).selectById(7L);
        verify(cosService).publicUrl("WFA3B1E7A2/work/video/demo.mp4");
        server.verify();
    }

    /** Webhook 已启用但内部密钥为空时不得构造或发送不可执行的回传命令。 */
    @Test
    void blankAdminPointSecretFailsBeforeNotificationRequest() {
        adminPointProperties.setSecret(" ");

        assertThatThrownBy(() -> notifier().notifySubmitted(submission()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("后台积分密钥未配置");

        verifyNoInteractions(userEntityMapper, cosService);
        server.verify();
    }

    /** 审核命令必须使用可直接复制执行的单行 JSON 请求体。 */
    @Test
    void reviewCurlCommandsUseSingleLineJsonBody() {
        adminPointProperties.setSecret("admin'point-secret");
        stubActiveUser();
        when(cosService.publicUrl("WFA3B1E7A2/work/video/demo.mp4"))
                .thenReturn("https://cdn.example.com/work.mp4");
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(request -> {
                    JSONObject payload = JSONObject.parseObject(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    JSONArray elements = payload.getJSONObject("card").getJSONArray("elements");
                    String commands = elements.getJSONObject(5)
                            .getJSONArray("elements")
                            .getJSONObject(0)
                            .getString("content");
                    assertThat(commands)
                            .contains("-H 'X-Admin-Point-Secret: admin'\\''point-secret'")
                            .contains("-d '{\"status\":\"PASSED\"}'")
                            .contains("-d '{\"status\":\"REJECTED\","
                                    + "\"auditRejectReason\":\"请填写拒绝原因\"}'")
                            .doesNotContain("<<'JSON'", "--data-binary @-");
                    assertThat(StringUtils.countOccurrencesOf(
                            commands,
                            "-H 'X-Admin-Point-Secret: admin'\\''point-secret'"))
                            .isEqualTo(2);
                })
                .andRespond(withSuccess());

        notifier().notifySubmitted(submission());

        server.verify();
    }

    /** 回传基础地址尾部斜杠必须归一化，避免接口路径出现双斜杠。 */
    @Test
    void trailingSlashesDoNotCreateDoubleSlashInReviewEndpoint() {
        properties.setReviewApiBaseUrl("https://api.test.wefolio.example///");
        stubActiveUser();
        when(cosService.publicUrl("WFA3B1E7A2/work/video/demo.mp4"))
                .thenReturn("https://cdn.example.com/work.mp4");
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(content().string(allOf(
                        containsString(
                                "https://api.test.wefolio.example/api/internal/work-audits/"),
                        not(containsString("example//api")))))
                .andRespond(withSuccess());

        notifier().notifySubmitted(submission());

        server.verify();
    }

    /** 用户缺失或停用时必须在读取媒体和调用飞书前失败。 */
    @Test
    void missingOrDisabledUserFailsBeforeMediaLookupAndHttpCall() {
        when(userEntityMapper.selectById(7L)).thenReturn(null);
        assertThatThrownBy(() -> notifier().notifySubmitted(submission()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(WorkManualAuditMessage.NOTIFICATION_USER_INVALID_MESSAGE);
        verifyNoInteractions(cosService);
        server.verify();

        UserEntity disabled = activeUser();
        disabled.setStatus(UserStatusDict.DISABLED.getCode());
        when(userEntityMapper.selectById(7L)).thenReturn(disabled);
        assertThatThrownBy(() -> notifier().notifySubmitted(submission()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(WorkManualAuditMessage.NOTIFICATION_USER_INVALID_MESSAGE);
        verify(cosService, never()).publicUrl("WFA3B1E7A2/work/video/demo.mp4");
        server.verify();
    }

    /** 飞书 HTTP 错误必须在单次请求后向上抛出。 */
    @Test
    void httpFailurePropagatesAfterOneRequest() {
        stubActiveUser();
        when(cosService.publicUrl("WFA3B1E7A2/work/video/demo.mp4"))
                .thenReturn("https://cdn.example.com/work.mp4");
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> notifier().notifySubmitted(submission()))
                .isInstanceOf(RestClientResponseException.class);

        server.verify();
    }

    /** 创建使用固定时钟的被测通知器。 */
    private FeishuWorkAuditNotifier notifier() {
        return new FeishuWorkAuditNotifier(
                properties,
                adminPointProperties,
                userEntityMapper,
                cosService,
                restClient,
                Clock.fixed(Instant.ofEpochSecond(TIMESTAMP_SECONDS), ZoneOffset.UTC));
    }

    /** 配置有效用户查询结果。 */
    private void stubActiveUser() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
    }

    /** 构建有效通知用户。 */
    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUniqueCode("WFA3B1E7A2");
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        user.setDeleted(0L);
        return user;
    }

    /** 构建最终轮人工审核通知快照。 */
    private WorkManualAuditSubmission submission() {
        return new WorkManualAuditSubmission(
                91L,
                7L,
                "草坪婚礼快剪",
                "VIDEO",
                "WFA3B1E7A2/work/video/demo.mp4",
                MANUAL_AUDIT_NO,
                3,
                3,
                LocalDateTime.of(2026, 8, 25, 10, 42, 0, 123_000_000),
                List.of(new WorkAuditUserReasonResolver.AuditReason(
                        "AUDIT_SERVICE_ERROR", "审核服务暂时未能完成检测")));
    }

    /** 断言标题和原因使用纯文本，只有媒体链接使用飞书 Markdown。 */
    private void assertTextTags(JSONArray elements) {
        JSONObject titleText = elements.getJSONObject(1).getJSONObject("text");
        JSONObject reasonsText = elements.getJSONObject(2).getJSONObject("text");
        JSONObject mediaText = elements.getJSONObject(3).getJSONObject("text");
        assertThat(titleText.getString("tag")).isEqualTo("plain_text");
        assertThat(titleText.getString("content")).contains("草坪婚礼快剪");
        assertThat(reasonsText.getString("tag")).isEqualTo("plain_text");
        assertThat(reasonsText.getString("content")).contains("上一轮审核原因");
        assertThat(mediaText.getString("tag")).isEqualTo("lark_md");
    }
}
