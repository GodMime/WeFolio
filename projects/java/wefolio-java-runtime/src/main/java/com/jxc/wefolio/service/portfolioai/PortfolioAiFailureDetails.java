package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.message.PortfolioAiFailureMessage;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.message.PortfolioFontMessage;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.PortfolioTextMessage;
import com.jxc.wefolio.message.TeamPortfolioMessage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 将不可信失败记录转为固定分类及显式白名单说明，原始错误文案不直接进入响应。 */
final class PortfolioAiFailureDetails {
    /** 查询候选时的处理阶段，用于无法公开内部原因时说明失败位置。 */
    enum Stage {
        /** 读取冻结请求。 */ REQUEST("REQUEST_INVALID", PortfolioAiFailureMessage.REQUEST_INVALID),
        /** 校验草稿基准。 */ REVISION("STALE_DRAFT", PortfolioAiFailureMessage.REVISION_INVALID),
        /** 严格验证中间协议。 */ PROTOCOL("INVALID_OUTPUT", PortfolioAiFailureMessage.INVALID_OUTPUT),
        /** 复核真实来源。 */ SOURCES("SOURCE_VALIDATION_FAILED", PortfolioAiFailureMessage.SOURCE_VALIDATION_FAILED),
        /** 编译完整配置。 */ COMPILATION("COMPILATION_FAILED", PortfolioAiFailureMessage.COMPILATION_FAILED),
        /** 普通组件业务校验。 */ CONFIG("CONFIG_INVALID", PortfolioAiFailureMessage.CONFIG_INVALID),
        /** 确认显式值仍然保留。 */ NORMALIZATION("NORMALIZATION_CHANGED", PortfolioAiFailureMessage.NORMALIZATION_CHANGED),
        /** 生成普通展示数据。 */ RENDER("RENDER_FAILED", PortfolioAiFailureMessage.RENDER_FAILED);
        /** 固定阶段错误码。 */ private final String code;
        /** 固定阶段中文说明。 */ private final String message;
        /** 保存该阶段的固定诊断。 */
        Stage(String code, String message) { this.code = code; this.message = message; }
    }

    /** 任务错误码只通过此固定目录解释，不读取存库原文或异常摘要。 */
    private enum Failure {
        /** 总期限。 */ TIMEOUT("TIMEOUT", PortfolioAiFailureMessage.TASK_TIMEOUT),
        /** 单次期限。 */ REMOTE_TIMEOUT("REMOTE_TIMEOUT", PortfolioAiFailureMessage.REMOTE_TIMEOUT),
        /** 供应商繁忙。 */ REMOTE_BUSY("REMOTE_BUSY", PortfolioAiFailureMessage.REMOTE_BUSY),
        /** 网络失败。 */ REMOTE_ERROR("REMOTE_ERROR", PortfolioAiFailureMessage.REMOTE_ERROR),
        /** 服务账户配置。 */ AI_UNAVAILABLE("AI_UNAVAILABLE", PortfolioAiFailureMessage.AI_UNAVAILABLE),
        /** 供应商接口配置。 */ REMOTE_CONFIGURATION("REMOTE_CONFIGURATION", PortfolioAiFailureMessage.REMOTE_CONFIGURATION),
        /** 输出长度。 */ OUTPUT_TRUNCATED("OUTPUT_TRUNCATED", PortfolioAiFailureMessage.OUTPUT_TRUNCATED),
        /** 输出未完成。 */ OUTPUT_BLOCKED("OUTPUT_BLOCKED", PortfolioAiFailureMessage.OUTPUT_BLOCKED),
        /** 非法输出。 */ INVALID_OUTPUT("INVALID_OUTPUT", PortfolioAiFailureMessage.INVALID_OUTPUT),
        /** 响应规模。 */ RESPONSE_TOO_LARGE("RESPONSE_TOO_LARGE", PortfolioAiFailureMessage.RESPONSE_TOO_LARGE),
        /** 次数耗尽。 */ ATTEMPTS_EXHAUSTED("ATTEMPTS_EXHAUSTED", PortfolioAiFailureMessage.ATTEMPTS_EXHAUSTED),
        /** 执行中断。 */ EXECUTION_INTERRUPTED("EXECUTION_INTERRUPTED", PortfolioAiFailureMessage.EXECUTION_INTERRUPTED),
        /** 内部异常。 */ INTERNAL_ERROR("INTERNAL_ERROR", PortfolioAiFailureMessage.INTERNAL_ERROR),
        /** 契约版本。 */ CONTRACT_UNAVAILABLE(ERROR_CONTRACT_UNAVAILABLE, PortfolioAiMessage.CONTRACT_UNAVAILABLE),
        /** 丢失字段。 */ FIELD_DROPPED(ERROR_NORMALIZATION_FIELD_DROPPED, PortfolioAiFailureMessage.NORMALIZATION_FIELD_DROPPED),
        /** 改变类型。 */ TYPE_CHANGED(ERROR_NORMALIZATION_TYPE_CHANGED, PortfolioAiFailureMessage.NORMALIZATION_TYPE_CHANGED),
        /** 改变值。 */ VALUE_CHANGED(ERROR_NORMALIZATION_VALUE_CHANGED, PortfolioAiFailureMessage.NORMALIZATION_VALUE_CHANGED),
        /** 改变数量。 */ ARRAY_CHANGED(ERROR_NORMALIZATION_ARRAY_CHANGED, PortfolioAiFailureMessage.NORMALIZATION_ARRAY_CHANGED),
        /** 未知分类。 */ GENERATION_FAILED("GENERATION_FAILED", PortfolioAiFailureMessage.GENERATION_FAILED);
        /** 固定错误分类。 */ private final String code;
        /** 安全用户说明。 */ private final String message;
        /** 保存固定错误分类，不接收运行时文字。 */
        Failure(String code, String message) { this.code = code; this.message = message; }
    }

    /** 分类目录由固定枚举构建，未知码使用兜底，避免引入不可信值。 */
    private static final Map<String, Failure> FAILURES = Arrays.stream(Failure.values())
            .collect(Collectors.toUnmodifiableMap(value -> value.code, value -> value));
    /** 只允许精确匹配的固定业务提示；不接受格式化模板、菜单名称、组件名或动态原文。 */
    private static final Set<String> TRUSTED_REASONS = new HashSet<>(List.of(
            PortfolioAiMessage.STALE_DRAFT, PortfolioAiMessage.SOURCE_UNAVAILABLE, PortfolioAiMessage.CONFIG_LIMIT,
            AdvancedPortfolioMessage.INVALID_CONFIG, AdvancedPortfolioMessage.MEMBER_PORTFOLIO_UNAVAILABLE,
            PortfolioFontMessage.INVALID_CONFIG,
            PortfolioTextMessage.LINE_HEIGHT_INVALID, PortfolioTextMessage.COLOR_INVALID, PortfolioTextMessage.CONFIG_INVALID,
            PortfolioTextMessage.BLOCK_COUNT_INVALID, PortfolioTextMessage.CONTENT_TOO_LONG,
            PortfolioTextMessage.BACKGROUND_CONFIG_INVALID, PortfolioTextMessage.BACKGROUND_REQUIRED, PortfolioTextMessage.BACKGROUND_UNAVAILABLE,
            PortfolioMessage.TEXT_GRID_INVALID, PortfolioMessage.TEXT_GRID_CONTENT_REQUIRED, PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID,
            PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE, PortfolioMessage.PORTFOLIO_CONFIG_REQUIRED_MESSAGE,
            PortfolioMessage.PORTFOLIO_CONFIG_VERSION_UNSUPPORTED_MESSAGE, PortfolioMessage.EDITOR_SCHEMA_REVISION_UNSUPPORTED_MESSAGE,
            PortfolioMessage.ENABLED_COMPONENT_REQUIRED_MESSAGE, PortfolioMessage.BOTTOM_NAV_ITEM_COUNT_INVALID_MESSAGE,
            PortfolioMessage.BOTTOM_NAV_TITLE_REQUIRED_MESSAGE, PortfolioMessage.BOTTOM_NAV_TITLE_TOO_LONG_MESSAGE,
            PortfolioMessage.BOTTOM_NAV_TITLE_DUPLICATE_MESSAGE, PortfolioMessage.BOTTOM_NAV_KEY_INVALID_MESSAGE,
            PortfolioMessage.BOTTOM_NAV_KEY_DUPLICATE_MESSAGE, PortfolioMessage.FIRST_BOTTOM_NAV_COMPONENTS_DUPLICATE_MESSAGE,
            PortfolioMessage.COMPONENT_KEY_CROSS_MENU_DUPLICATE_MESSAGE, PortfolioMessage.MENU_PROFILE_COMPONENT_LIMIT_MESSAGE,
            PortfolioMessage.PROFILE_COMPONENT_LIMIT_MESSAGE, PortfolioMessage.COMPONENT_KEY_DUPLICATE_MESSAGE,
            PortfolioMessage.SINGLE_WORK_REQUIRED_MESSAGE, PortfolioMessage.WORK_REFERENCE_ID_INVALID_MESSAGE,
            PortfolioMessage.WORK_REFERENCE_NOT_FOUND_MESSAGE, PortfolioMessage.WORK_REFERENCE_NOT_OWNED_MESSAGE,
            PortfolioMessage.WORK_REFERENCE_PROCESSING_MESSAGE, PortfolioMessage.WORK_REFERENCE_PROCESSING_FAILED_MESSAGE,
            PortfolioMessage.WORK_REFERENCE_STATUS_INVALID_MESSAGE, PortfolioMessage.WORK_REFERENCE_AUDIT_NOT_PASSED_MESSAGE,
            PortfolioMessage.VIDEO_CAROUSEL_VIDEO_ONLY_MESSAGE, PortfolioMessage.WORK_LIST_MEDIA_TYPE_UNSUPPORTED_MESSAGE,
            PortfolioMessage.SINGLE_WORK_MEDIA_TYPE_UNSUPPORTED_MESSAGE, PortfolioMessage.HYPERLINK_DISPLAY_WORK_REQUIRED_MESSAGE,
            PortfolioMessage.HYPERLINK_DISPLAY_WORK_INVALID_MESSAGE, PortfolioMessage.HYPERLINK_ACTION_REQUIRED_MESSAGE,
            PortfolioMessage.HYPERLINK_TARGET_REQUIRED_MESSAGE, PortfolioMessage.HYPERLINK_EXTERNAL_CONTENT_LENGTH_MESSAGE,
            PortfolioMessage.HYPERLINK_PROMPT_TEXT_LENGTH_MESSAGE, PortfolioMessage.HYPERLINK_ICON_POSITION_UNSUPPORTED_MESSAGE,
            PortfolioMessage.HYPERLINK_SELF_TARGET_MESSAGE, PortfolioMessage.HYPERLINK_CYCLE_MESSAGE, PortfolioMessage.PORTFOLIO_GRAPH_BUSY_MESSAGE,
            PortfolioMessage.CAROUSEL_IMAGE_ONLY_MESSAGE, PortfolioMessage.VIDEO_CAROUSEL_TITLE_LENGTH_MESSAGE,
            PortfolioMessage.VIDEO_CAROUSEL_WORK_COUNT_MESSAGE, PortfolioMessage.VIDEO_CAROUSEL_WORK_DUPLICATE_MESSAGE,
            PortfolioMessage.QR_SOURCE_UNSUPPORTED_MESSAGE, PortfolioMessage.CUSTOM_QR_REQUIRED_MESSAGE,
            PortfolioMessage.CONTACT_FORM_NAME_FIELD_REQUIRED_MESSAGE, PortfolioMessage.CONTACT_FORM_CONTACT_FIELD_REQUIRED_MESSAGE,
            PortfolioMessage.CONTACT_FORM_DISPLAY_MODE_UNSUPPORTED_MESSAGE, PortfolioMessage.CONTACT_INFO_REQUIRED,
            PortfolioMessage.TEXT_SECTION_CONTENT_REQUIRED_MESSAGE, PortfolioMessage.TEXT_SECTION_CONTENT_LENGTH_MESSAGE,
            PortfolioMessage.TEXT_SECTION_ALIGNMENT_UNSUPPORTED_MESSAGE, PortfolioMessage.TEXT_SECTION_FONT_UNSUPPORTED_MESSAGE,
            PortfolioMessage.TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE, PortfolioMessage.DIVIDER_COLOR_UNSUPPORTED_MESSAGE,
            PortfolioMessage.DIVIDER_HEIGHT_INVALID_MESSAGE, PortfolioMessage.DISPLAY_TAG_NAME_REQUIRED_MESSAGE,
            PortfolioMessage.DISPLAY_TAG_NAME_DUPLICATE_MESSAGE, PortfolioMessage.DISPLAY_TAG_KEY_DUPLICATE_MESSAGE,
            PortfolioMessage.SCHEDULE_QUERY_RANGE_UNSUPPORTED_MESSAGE, PortfolioMessage.SCHEDULE_QUERY_DISPLAY_MODE_UNSUPPORTED_MESSAGE,
            PortfolioMessage.SCHEDULE_QUERY_FUTURE_DAYS_INVALID_MESSAGE, PortfolioMessage.SCHEDULE_QUERY_DATE_INVALID_MESSAGE,
            PortfolioMessage.SCHEDULE_QUERY_DATE_RANGE_INVALID_MESSAGE, PortfolioMessage.DISPLAY_WORK_REQUIRED_MESSAGE,
            PortfolioMessage.DISPLAY_WORK_COUNT_LIMIT_MESSAGE, PortfolioMessage.BACKGROUND_AUDIO_CONFIG_INVALID,
            PortfolioMessage.BACKGROUND_AUDIO_REQUIRED, PortfolioMessage.BACKGROUND_AUDIO_UNAVAILABLE, PortfolioMessage.PORTFOLIO_COMPONENT_EMPTY_MESSAGE,
            TeamPortfolioMessage.DRAFT_CONFIG_REQUIRED_MESSAGE, TeamPortfolioMessage.EDITOR_SCHEMA_REVISION_UNSUPPORTED_MESSAGE,
            TeamPortfolioMessage.COMPONENT_KEY_CROSS_MENU_DUPLICATE_MESSAGE, TeamPortfolioMessage.TEAM_PROFILE_COMPONENT_LIMIT_MESSAGE,
            TeamPortfolioMessage.SINGLE_WORK_CONFIG_INVALID, TeamPortfolioMessage.SINGLE_WORK_UNAVAILABLE,
            TeamPortfolioMessage.SINGLE_WORK_MEMBER_UNAVAILABLE, TeamPortfolioMessage.VIDEO_CAROUSEL_CONFIG_INVALID,
            TeamPortfolioMessage.VIDEO_CAROUSEL_TITLE_TOO_LONG, TeamPortfolioMessage.VIDEO_CAROUSEL_ITEM_COUNT_INVALID,
            TeamPortfolioMessage.VIDEO_CAROUSEL_WORK_DUPLICATE, TeamPortfolioMessage.VIDEO_CAROUSEL_WORK_UNAVAILABLE));

    /** 工具类不可实例化。 */
    private PortfolioAiFailureDetails() { }

    /** 失败任务按已知分类说明原因，未知码和存库文案都不能影响新增详情。 */
    static Map<String, String> task(String code) {
        Failure failure = code == null ? Failure.GENERATION_FAILED : FAILURES.getOrDefault(code, Failure.GENERATION_FAILED);
        return detail(failure.code, failure.message);
    }

    /** 候选失败仅放行固定业务文案，协议异常按代码解释，其余按处理阶段降级。 */
    static Map<String, String> candidate(Stage stage, RuntimeException exception) {
        if (stage == Stage.REQUEST) { return detail(stage.code, stage.message); }
        if (exception instanceof PortfolioAiNormalizationException normalization) { return task(normalization.getCode()); }
        if (exception instanceof AiProtocolException protocol) {
            Failure failure = protocol.getCode() == null ? null : FAILURES.get(protocol.getCode());
            return failure == null ? detail(stage.code, stage.message) : detail(failure.code, failure.message);
        }
        if (exception instanceof BusinessException && TRUSTED_REASONS.contains(exception.getMessage())) {
            String message = exception.getMessage();
            if (PortfolioAiMessage.STALE_DRAFT.equals(message)) { return detail(Stage.REVISION.code, message); }
            String code = PortfolioAiMessage.SOURCE_UNAVAILABLE.equals(message) ? Stage.SOURCES.code : stage.code;
            return detail(code, stage == Stage.CONFIG ? PortfolioAiFailureMessage.CONFIG_REASON_PREFIX + message : message);
        }
        return detail(stage.code, stage.message);
    }

    /** 构造只包含固定分类和安全说明的 HTTP 详情。 */
    private static Map<String, String> detail(String code, String message) { return Map.of(KEY_CODE, code, KEY_MESSAGE, message); }
}
