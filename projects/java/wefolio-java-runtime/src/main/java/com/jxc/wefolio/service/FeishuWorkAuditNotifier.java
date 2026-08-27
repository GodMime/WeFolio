package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.AdminPointProperties;
import com.jxc.wefolio.config.WorkManualAuditConfiguration;
import com.jxc.wefolio.config.WorkManualAuditProperties;
import com.jxc.wefolio.constant.WorkManualAuditConstants;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import com.jxc.wefolio.message.WorkManualAuditMessage;
import com.jxc.wefolio.model.WorkManualAuditSubmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jxc.wefolio.common.ShellCommandEscaper.singleQuote;
import static com.jxc.wefolio.constant.PointConstants.ADMIN_POINT_SECRET_HEADER;

/** 作品最终轮人工审核飞书通知器，负责签名、卡片和单次同步调用。 */
@Service
public class FeishuWorkAuditNotifier {

    /** 飞书签名算法 */
    private static final String HMAC_SHA_256_ALGORITHM = "HmacSHA256";

    /** 飞书交互卡片消息类型 */
    private static final String INTERACTIVE_MESSAGE_TYPE = "interactive";

    /** 飞书卡片头部模板 */
    private static final String CARD_HEADER_TEMPLATE = "blue";

    /** 飞书普通文本标签 */
    private static final String PLAIN_TEXT_TAG = "plain_text";

    /** 飞书 Markdown 标签 */
    private static final String LARK_MARKDOWN_TAG = "lark_md";

    /** 飞书内容块标签 */
    private static final String DIV_TAG = "div";

    /** 飞书分隔线标签 */
    private static final String HORIZONTAL_RULE_TAG = "hr";

    /** 飞书提示块标签 */
    private static final String NOTE_TAG = "note";

    /** 人工审核卡片标题 */
    private static final String CARD_TITLE = "作品最终轮待人工审核";

    /** 作品标题块标题 */
    private static final String TITLE_SECTION_TITLE = "作品标题";

    /** 上一轮原因块标题 */
    private static final String PREVIOUS_REASONS_SECTION_TITLE = "上一轮审核原因";

    /** 作品媒体块标题 */
    private static final String MEDIA_SECTION_TITLE = "作品媒体";

    /** 无上一轮原因提示 */
    private static final String NO_PREVIOUS_REASON_TEXT = "无";

    /** curl JSON 内容类型请求头 */
    private static final String JSON_CONTENT_TYPE_HEADER = "Content-Type: application/json";

    /** 审核通过 curl 标题 */
    private static final String PASSED_CURL_TITLE = "审核通过：";

    /** 审核拒绝 curl 标题 */
    private static final String REJECTED_CURL_TITLE = "审核拒绝（请修改原因）：";

    /** 审核拒绝原因占位文案 */
    private static final String REJECT_REASON_PLACEHOLDER = "请填写拒绝原因";

    /** JSON 编辑安全提示 */
    private static final String JSON_EDITING_NOTICE =
            "拒绝原因含单引号时，请改用双引号包裹请求体并转义 JSON 双引号；"
                    + "双引号和反斜杠需按 JSON 规则转义";

    /** 人工审核提交时间格式 */
    private static final DateTimeFormatter SUBMITTED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    /** 作品人工审核配置 */
    private final WorkManualAuditProperties properties;

    /** 内部回传密钥配置 */
    private final AdminPointProperties adminPointProperties;

    /** 用户 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** COS 服务 */
    private final CosService cosService;

    /** 作品审核飞书 REST 客户端 */
    private final RestClient restClient;

    /** 秒级签名时间来源 */
    private final Clock clock;

    /**
     * 创建生产环境作品人工审核通知器。
     *
     * @param properties 作品人工审核配置
     * @param adminPointProperties 内部回传密钥配置
     * @param userEntityMapper 用户 Mapper
     * @param cosService COS 服务
     * @param restClient 作品审核飞书 REST 客户端
     */
    @Autowired
    public FeishuWorkAuditNotifier(
            WorkManualAuditProperties properties,
            AdminPointProperties adminPointProperties,
            UserEntityMapper userEntityMapper,
            CosService cosService,
            @Qualifier(WorkManualAuditConfiguration.WORK_AUDIT_REST_CLIENT_BEAN_NAME)
            RestClient restClient
    ) {
        this(properties, adminPointProperties, userEntityMapper, cosService,
                restClient, Clock.systemUTC());
    }

    /**
     * 创建使用指定时钟的通知器，供测试固定签名时间。
     *
     * @param properties 作品人工审核配置
     * @param adminPointProperties 内部回传密钥配置
     * @param userEntityMapper 用户 Mapper
     * @param cosService COS 服务
     * @param restClient 作品审核飞书 REST 客户端
     * @param clock 秒级签名时间来源
     */
    FeishuWorkAuditNotifier(
            WorkManualAuditProperties properties,
            AdminPointProperties adminPointProperties,
            UserEntityMapper userEntityMapper,
            CosService cosService,
            RestClient restClient,
            Clock clock
    ) {
        this.properties = properties;
        this.adminPointProperties = adminPointProperties;
        this.userEntityMapper = userEntityMapper;
        this.cosService = cosService;
        this.restClient = restClient;
        this.clock = clock;
    }

    /** 发送一次最终轮人工审核提交通知。 */
    public void notifySubmitted(WorkManualAuditSubmission submission) {
        String adminPointSecret = requireAdminPointSecret();
        NotificationContext context = buildContext(submission);
        restClient.post()
                .uri(properties.getWebhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildPayload(context, adminPointSecret))
                .retrieve()
                .toBodilessEntity();
    }

    /** 读取事务提交后通知所需的用户和媒体上下文。 */
    private NotificationContext buildContext(WorkManualAuditSubmission submission) {
        WorkManualAuditSubmission source = Objects.requireNonNull(
                submission, "人工审核通知快照不能为空");
        UserEntity user = userEntityMapper.selectById(source.userId());
        if (user == null
                || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())
                || (user.getDeleted() != null && user.getDeleted() != 0L)
                || user.getUniqueCode() == null
                || user.getUniqueCode().isBlank()) {
            // 此处已在作品事务提交之后，仅按通知失败处理，不回滚作品或执行额外补偿。
            throw new IllegalStateException(WorkManualAuditMessage.NOTIFICATION_USER_INVALID_MESSAGE);
        }
        MediaTypeDict mediaType = MediaTypeDict.fromCode(source.mediaType());
        if (mediaType == null) {
            throw new IllegalStateException(WorkManualAuditMessage.NOTIFICATION_MEDIA_TYPE_INVALID_MESSAGE);
        }
        return new NotificationContext(
                source,
                user.getUniqueCode(),
                mediaType.getDisplayName(),
                cosService.publicUrl(source.mediaObjectKey()));
    }

    /** 构建符合飞书机器人协议的 interactive 消息体。 */
    private String buildPayload(NotificationContext context, String adminPointSecret) {
        long timestamp = clock.instant().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Long.toString(timestamp));
        payload.put("sign", generateSign(timestamp, properties.getWebhookSecret()));
        payload.put("msg_type", INTERACTIVE_MESSAGE_TYPE);
        payload.put("card", buildCard(context, adminPointSecret));
        return JSON.toJSONString(payload);
    }

    /** 构建作品人工审核飞书卡片。 */
    private Map<String, Object> buildCard(
            NotificationContext context,
            String adminPointSecret
    ) {
        WorkManualAuditSubmission submission = context.submission();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", Map.of(
                "template", CARD_HEADER_TEMPLATE,
                "title", text(PLAIN_TEXT_TAG, CARD_TITLE)));

        List<Object> elements = new ArrayList<>();
        elements.add(Map.of(
                "tag", DIV_TAG,
                "fields", List.of(
                        shortField("人工审核编号", submission.manualAuditNo()),
                        shortField("用户唯一码", context.uniqueCode()),
                        shortField("媒体类型", context.mediaTypeText()),
                        shortField("审核轮次", "第 " + submission.auditRound()
                                + "/" + submission.maxAuditRounds() + " 轮"),
                        shortField("提交时间", submission.submittedAt()
                                .format(SUBMITTED_AT_FORMATTER)))));
        elements.add(Map.of(
                "tag", DIV_TAG,
                "text", text(PLAIN_TEXT_TAG,
                        TITLE_SECTION_TITLE + "\n" + Objects.toString(submission.title(), ""))));
        elements.add(Map.of(
                "tag", DIV_TAG,
                "text", text(PLAIN_TEXT_TAG,
                        PREVIOUS_REASONS_SECTION_TITLE + "\n"
                                + formatPreviousReasons(submission.previousReasons()))));
        elements.add(Map.of(
                "tag", DIV_TAG,
                "text", text(LARK_MARKDOWN_TAG,
                        "**" + MEDIA_SECTION_TITLE + "**\n[查看作品](" + context.mediaUrl() + ")")));
        elements.add(Map.of("tag", HORIZONTAL_RULE_TAG));
        elements.add(Map.of(
                "tag", NOTE_TAG,
                "elements", List.of(text(PLAIN_TEXT_TAG,
                        buildReviewCurlCommands(
                                submission.manualAuditNo(), adminPointSecret)))));
        card.put("elements", elements);
        return card;
    }

    /** 将上一轮多条用户可读原因格式化为纯文本。 */
    private String formatPreviousReasons(List<WorkAuditUserReasonResolver.AuditReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return NO_PREVIOUS_REASON_TEXT;
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < reasons.size(); index++) {
            lines.add((index + 1) + ". " + Objects.toString(reasons.get(index).message(), ""));
        }
        return String.join("\n", lines);
    }

    /** 构建供审核员复制执行的通过和拒绝命令。 */
    private String buildReviewCurlCommands(String manualAuditNo, String adminPointSecret) {
        String baseUrl = Objects.requireNonNull(properties.getReviewApiBaseUrl()).strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = baseUrl + WorkManualAuditConstants.REVIEW_API_PATH_PREFIX + manualAuditNo;
        String passedBody = JSON.toJSONString(Map.of(
                "status", WorkAuditStatusDict.PASSED.getCode()));
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("status", WorkAuditStatusDict.REJECTED.getCode());
        rejected.put("auditRejectReason", REJECT_REASON_PLACEHOLDER);
        String rejectedBody = JSON.toJSONString(rejected);
        return PASSED_CURL_TITLE + "\n" + buildCurl(endpoint, passedBody, adminPointSecret)
                + "\n\n" + REJECTED_CURL_TITLE + "\n"
                + buildCurl(endpoint, rejectedBody, adminPointSecret)
                + "\n\n" + JSON_EDITING_NOTICE;
    }

    /** 构建与问题反馈卡片一致的单行 curl。 */
    private String buildCurl(String endpoint, String body, String adminPointSecret) {
        return "curl -X PUT '" + endpoint
                + "' -H '" + JSON_CONTENT_TYPE_HEADER
                + "' -H " + singleQuote(ADMIN_POINT_SECRET_HEADER + ": " + adminPointSecret)
                + " -d '" + body + "'";
    }

    /** 读取用于飞书回传命令的完整内部密钥。 */
    private String requireAdminPointSecret() {
        String secret = adminPointProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(PointMessage.ADMIN_SECRET_MISSING_MESSAGE);
        }
        return secret;
    }

    /** 构建卡片短字段。 */
    private Map<String, Object> shortField(String label, String value) {
        return Map.of(
                "is_short", true,
                "text", text(LARK_MARKDOWN_TAG, "**" + label + "**\n" + value));
    }

    /** 构建卡片文本节点。 */
    private Map<String, Object> text(String tag, String content) {
        return Map.of("tag", tag, "content", content);
    }

    /** 按飞书协议生成秒级时间戳签名。 */
    static String generateSign(long timestampSeconds, String secret) {
        try {
            String stringToSign = timestampSeconds + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_SHA_256_ALGORITHM);
            mac.init(new SecretKeySpec(
                    stringToSign.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(WorkManualAuditMessage.SIGNATURE_FAILURE_MESSAGE, exception);
        }
    }

    /** 飞书通知最小上下文。 */
    private record NotificationContext(
            WorkManualAuditSubmission submission,
            String uniqueCode,
            String mediaTypeText,
            String mediaUrl
    ) {
    }
}
