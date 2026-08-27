package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.FeedbackConfiguration;
import com.jxc.wefolio.config.FeedbackProperties;
import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 飞书问题反馈通知器，负责签名、卡片构建和同步单次调用。 */
@Service
public class FeishuFeedbackNotifier {

    /** 飞书签名算法。 */
    private static final String HMAC_SHA_256_ALGORITHM = "HmacSHA256";

    /** 飞书消息类型。 */
    private static final String INTERACTIVE_MESSAGE_TYPE = "interactive";

    /** 飞书卡片头部模板。 */
    private static final String CARD_HEADER_TEMPLATE = "blue";

    /** 飞书卡片普通文本标签。 */
    private static final String PLAIN_TEXT_TAG = "plain_text";

    /** 飞书卡片 Markdown 标签。 */
    private static final String LARK_MARKDOWN_TAG = "lark_md";

    /** 飞书卡片内容块标签。 */
    private static final String DIV_TAG = "div";

    /** 飞书卡片分隔线标签。 */
    private static final String HORIZONTAL_RULE_TAG = "hr";

    /** 飞书卡片提示标签。 */
    private static final String NOTE_TAG = "note";

    /** 新问题卡片标题。 */
    private static final String CREATED_TITLE = "新问题反馈";

    /** 补充问题卡片标题。 */
    private static final String APPENDED_TITLE = "问题反馈有补充";

    /** 状态更新接口路径格式。 */
    private static final String INTERNAL_STATUS_PATH_FORMAT =
            "/api/internal/feedbacks/%s";

    /** 待再次反馈 curl 标题。 */
    private static final String WAITING_CURL_TITLE = "待再次反馈：";

    /** 已处理 curl 标题。 */
    private static final String RESOLVED_CURL_TITLE = "已处理：";

    /** curl 内容类型请求头。 */
    private static final String JSON_CONTENT_TYPE_HEADER = "Content-Type: application/json";

    /** 待再次反馈默认结果。 */
    private static final String DEFAULT_WAITING_RESULT = "请补充更多信息";

    /** 已处理默认结果。 */
    private static final String DEFAULT_RESOLVED_RESULT =
            "问题已修复，请更新小程序（重新进入小程序后会自动更新）";

    /** 无附件提示。 */
    private static final String NO_ATTACHMENT_TEXT = "无";

    /** 用户描述块纯文本标题。 */
    private static final String DESCRIPTION_SECTION_TITLE = "本轮描述";

    /** 事务结果缺失异常文案。 */
    private static final String NULL_MUTATION_RESULT_MESSAGE = "事务结果不能为空";

    /** 提交轮次缺失异常文案。 */
    private static final String NULL_SUBMITTED_ROUND_MESSAGE = "提交轮次不能为空";

    /** 通知用户不存在异常文案。 */
    private static final String NOTIFICATION_USER_NOT_FOUND_MESSAGE = "反馈通知用户不存在";

    /** 通知状态无效异常文案。 */
    private static final String INVALID_NOTIFICATION_STATUS_MESSAGE = "反馈通知状态无效";

    /** 附件媒体类型无效异常文案。 */
    private static final String INVALID_ATTACHMENT_MEDIA_TYPE_MESSAGE = "反馈通知附件类型无效";

    /** 提交时间缺失异常文案。 */
    private static final String NULL_SUBMITTED_AT_MESSAGE = "提交时间不能为空";

    /** 通知签名生成失败异常文案。 */
    private static final String SIGNATURE_FAILURE_MESSAGE = "飞书通知签名生成失败";

    /** 提交时间格式。 */
    private static final DateTimeFormatter SUBMITTED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    /** 问题反馈配置。 */
    private final FeedbackProperties properties;

    /** 用户 Mapper。 */
    private final UserEntityMapper userEntityMapper;

    /** COS 服务。 */
    private final CosService cosService;

    /** 飞书 REST 客户端。 */
    private final RestClient restClient;

    /** 秒级签名时间来源。 */
    private final Clock clock;

    /**
     * 创建生产环境飞书通知器。
     *
     * @param properties 问题反馈配置
     * @param userEntityMapper 用户 Mapper
     * @param cosService COS 服务
     * @param restClient 飞书 REST 客户端
     */
    @Autowired
    public FeishuFeedbackNotifier(
            FeedbackProperties properties,
            UserEntityMapper userEntityMapper,
            CosService cosService,
            @Qualifier(FeedbackConfiguration.FEEDBACK_REST_CLIENT_BEAN_NAME) RestClient restClient
    ) {
        this(properties, userEntityMapper, cosService, restClient, Clock.systemUTC());
    }

    /**
     * 创建使用指定时钟的飞书通知器，供测试固定签名时间。
     *
     * @param properties 问题反馈配置
     * @param userEntityMapper 用户 Mapper
     * @param cosService COS 服务
     * @param restClient 飞书 REST 客户端
     * @param clock 签名时间来源
     */
    FeishuFeedbackNotifier(
            FeedbackProperties properties,
            UserEntityMapper userEntityMapper,
            CosService cosService,
            RestClient restClient,
            Clock clock
    ) {
        this.properties = properties;
        this.userEntityMapper = userEntityMapper;
        this.cosService = cosService;
        this.restClient = restClient;
        this.clock = clock;
    }

    /**
     * 发送新问题通知。
     *
     * @param result 新建问题事务结果
     */
    public void notifyCreated(FeedbackTransactionService.MutationResult result) {
        notify(CREATED_TITLE, result);
    }

    /**
     * 发送问题补充通知。
     *
     * @param result 追加轮次事务结果
     */
    public void notifyAppended(FeedbackTransactionService.MutationResult result) {
        notify(APPENDED_TITLE, result);
    }

    /** 执行一次同步飞书通知，响应体不参与业务判断。 */
    private void notify(
            String title,
            FeedbackTransactionService.MutationResult result
    ) {
        NotificationContext context = buildContext(result);
        restClient.post()
                .uri(properties.getWebhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildPayload(title, context))
                .retrieve()
                .toBodilessEntity();
    }

    /** 读取通知所需的最小业务上下文。 */
    private NotificationContext buildContext(FeedbackTransactionService.MutationResult result) {
        FeedbackEntity feedback = Objects.requireNonNull(
                result, NULL_MUTATION_RESULT_MESSAGE).feedback();
        FeedbackRoundSnapshot round = Objects.requireNonNull(
                result.submittedRound(), NULL_SUBMITTED_ROUND_MESSAGE);
        UserEntity user = userEntityMapper.selectById(feedback.getUserId());
        if (user == null || user.getUniqueCode() == null || user.getUniqueCode().isBlank()) {
            throw new IllegalStateException(NOTIFICATION_USER_NOT_FOUND_MESSAGE);
        }
        FeedbackStatusDict status = FeedbackStatusDict.fromCode(feedback.getStatus());
        if (status == null) {
            throw new IllegalStateException(INVALID_NOTIFICATION_STATUS_MESSAGE);
        }
        List<NotificationAttachment> attachments = new ArrayList<>();
        List<FeedbackRoundSnapshot.Attachment> sources = round.getAttachments() == null
                ? List.of() : round.getAttachments();
        for (FeedbackRoundSnapshot.Attachment source : sources) {
            FeedbackMediaTypeDict mediaType = FeedbackMediaTypeDict.fromCode(source.getMediaType());
            if (mediaType == null) {
                throw new IllegalStateException(INVALID_ATTACHMENT_MEDIA_TYPE_MESSAGE);
            }
            attachments.add(new NotificationAttachment(
                    mediaType.getDisplayName(), cosService.publicUrl(source.getObjectKey())));
        }
        return new NotificationContext(
                feedback.getFeedbackNo(),
                user.getUniqueCode(),
                status.getDisplayName(),
                round.getRoundNo(),
                round.getSubmittedAt(),
                round.getDescription(),
                attachments);
    }

    /** 构建符合飞书 custom-bot 协议的 interactive 消息体。 */
    private String buildPayload(String title, NotificationContext context) {
        long timestamp = clock.instant().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Long.toString(timestamp));
        payload.put("sign", generateSign(timestamp, properties.getWebhookSecret()));
        payload.put("msg_type", INTERACTIVE_MESSAGE_TYPE);
        payload.put("card", buildCard(title, context));
        return JSON.toJSONString(payload);
    }

    /** 构建飞书消息卡片。 */
    private Map<String, Object> buildCard(String title, NotificationContext context) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", Map.of(
                "template", CARD_HEADER_TEMPLATE,
                "title", text(PLAIN_TEXT_TAG, title)));

        List<Object> elements = new ArrayList<>();
        elements.add(Map.of(
                "tag", DIV_TAG,
                "fields", List.of(
                        shortField("问题编号", context.feedbackNo()),
                        shortField("用户唯一码", context.uniqueCode()),
                        shortField("当前状态", context.statusText()),
                        shortField("反馈轮次", "第 " + context.roundNo() + " 轮"),
                        shortField("提交时间", formatSubmittedAt(context.submittedAt())))));
        elements.add(Map.of(
                "tag", DIV_TAG,
                "text", text(PLAIN_TEXT_TAG,
                        DESCRIPTION_SECTION_TITLE + "\n" + context.description())));
        elements.add(Map.of(
                "tag", DIV_TAG,
                "text", text(LARK_MARKDOWN_TAG, "**附件**\n" + formatAttachments(context.attachments()))));
        elements.add(Map.of("tag", HORIZONTAL_RULE_TAG));
        elements.add(Map.of(
                "tag", NOTE_TAG,
                "elements", List.of(text(
                        PLAIN_TEXT_TAG,
                        buildStatusCurlCommands(context.feedbackNo())))));
        card.put("elements", elements);
        return card;
    }

    /** 构建供飞书用户复制执行的两条无认证状态更新命令。 */
    private String buildStatusCurlCommands(String feedbackNo) {
        String baseUrl = Objects.requireNonNull(properties.getStatusApiBaseUrl()).strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = baseUrl + INTERNAL_STATUS_PATH_FORMAT.formatted(feedbackNo);
        return WAITING_CURL_TITLE + "\n"
                + buildStatusCurl(endpoint, FeedbackStatusDict.WAITING_FOLLOW_UP.getCode(),
                DEFAULT_WAITING_RESULT)
                + "\n\n" + RESOLVED_CURL_TITLE + "\n"
                + buildStatusCurl(endpoint, FeedbackStatusDict.RESOLVED.getCode(), DEFAULT_RESOLVED_RESULT);
    }

    /** 构建单条无认证状态更新 curl。 */
    private String buildStatusCurl(String endpoint, String status, String feedbackResult) {
        String body = JSON.toJSONString(Map.of(
                "status", status,
                "feedbackResult", feedbackResult));
        return "curl -X PUT '" + endpoint + "' -H '" + JSON_CONTENT_TYPE_HEADER
                + "' -d '" + body + "'";
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

    /** 格式化轮次提交时间。 */
    private String formatSubmittedAt(LocalDateTime submittedAt) {
        return Objects.requireNonNull(submittedAt, NULL_SUBMITTED_AT_MESSAGE)
                .format(SUBMITTED_AT_FORMATTER);
    }

    /** 格式化附件链接，并明确标记图片或视频。 */
    private String formatAttachments(List<NotificationAttachment> attachments) {
        if (attachments.isEmpty()) {
            return NO_ATTACHMENT_TEXT;
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < attachments.size(); index++) {
            NotificationAttachment attachment = attachments.get(index);
            lines.add("[" + attachment.mediaTypeText() + " " + (index + 1) + "](" + attachment.url() + ")");
        }
        return String.join("\n", lines);
    }

    /**
     * 按飞书协议生成秒级时间戳签名。
     *
     * @param timestampSeconds 秒级时间戳
     * @param secret 飞书机器人签名密钥
     * @return Base64 编码签名
     */
    static String generateSign(long timestampSeconds, String secret) {
        try {
            String stringToSign = timestampSeconds + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_SHA_256_ALGORITHM);
            mac.init(new SecretKeySpec(
                    stringToSign.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(SIGNATURE_FAILURE_MESSAGE, exception);
        }
    }

    /** 飞书通知上下文。 */
    private record NotificationContext(
            String feedbackNo,
            String uniqueCode,
            String statusText,
            Integer roundNo,
            LocalDateTime submittedAt,
            String description,
            List<NotificationAttachment> attachments
    ) {
    }

    /** 飞书通知附件。 */
    private record NotificationAttachment(String mediaTypeText, String url) {
    }

}
