package com.jxc.wefolio.service.teamportfolio.component.schedulequery;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队档期查询组件配置校验器。
 */
@Component
public class TeamScheduleQueryComponentValidator {

    /** 标题配置键。 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 描述配置键。 */
    private static final String CONFIG_KEY_DESCRIPTION = "description";

    /** 展示方式配置键。 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 查询范围配置键。 */
    private static final String CONFIG_KEY_QUERY_RANGE = "queryRange";

    /** 查询范围类型配置键。 */
    private static final String CONFIG_KEY_TYPE = "type";

    /** 未来天数配置键。 */
    private static final String CONFIG_KEY_FUTURE_DAYS = "futureDays";

    /** 开始日期配置键。 */
    private static final String CONFIG_KEY_START_DATE = "startDate";

    /** 结束日期配置键。 */
    private static final String CONFIG_KEY_END_DATE = "endDate";

    /** 弹层日历展示方式。 */
    private static final String DISPLAY_MODE_MODAL_CALENDAR = "MODAL_CALENDAR";

    /** 内联日历展示方式。 */
    private static final String DISPLAY_MODE_INLINE_CALENDAR = "INLINE_CALENDAR";

    /** 不限制查询范围。 */
    private static final String QUERY_RANGE_UNLIMITED = "UNLIMITED";

    /** 限制未来天数查询范围。 */
    private static final String QUERY_RANGE_FUTURE_DAYS = "FUTURE_DAYS";

    /** 限制固定日期查询范围。 */
    private static final String QUERY_RANGE_DATE_RANGE = "DATE_RANGE";

    /** 展示方式不支持提示。 */
    private static final String DISPLAY_MODE_INVALID_MESSAGE = "档期查询展示方式不支持";

    /** 查询范围不支持提示。 */
    private static final String QUERY_RANGE_INVALID_MESSAGE = "档期查询范围不支持";

    /** 未来天数不合法提示。 */
    private static final String FUTURE_DAYS_INVALID_MESSAGE = "档期查询未来天数必须大于0";

    /** 日期范围不合法提示。 */
    private static final String DATE_RANGE_INVALID_MESSAGE = "档期查询日期范围不合法";

    /**
     * 校验并规范化团队档期查询组件配置。
     *
     * @param config 原始配置
     * @param context 团队组件上下文
     * @return 规范化后的组件配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamScheduleQueryComponentConfig componentConfig = toComponentConfig(config);
        String displayMode = defaultString(componentConfig.getDisplayMode(), DISPLAY_MODE_MODAL_CALENDAR);
        if (!DISPLAY_MODE_MODAL_CALENDAR.equals(displayMode) && !DISPLAY_MODE_INLINE_CALENDAR.equals(displayMode)) {
            throw new BusinessException(DISPLAY_MODE_INVALID_MESSAGE);
        }
        TeamScheduleQueryComponentConfig.QueryRange range = componentConfig.getQueryRange();
        String rangeType = defaultString(range == null ? null : range.getType(), QUERY_RANGE_UNLIMITED);
        TeamScheduleQueryComponentConfig.QueryRange normalizedRange = new TeamScheduleQueryComponentConfig.QueryRange();
        normalizedRange.setType(rangeType);
        switch (rangeType) {
            case QUERY_RANGE_UNLIMITED -> setUnlimitedRange(normalizedRange);
            case QUERY_RANGE_FUTURE_DAYS -> setFutureDaysRange(range, normalizedRange);
            case QUERY_RANGE_DATE_RANGE -> setDateRange(range, normalizedRange);
            default -> throw new BusinessException(QUERY_RANGE_INVALID_MESSAGE);
        }
        componentConfig.setDisplayMode(displayMode);
        componentConfig.setQueryRange(normalizedRange);
        return toJson(componentConfig);
    }

    /**
     * 转换并严格校验客户端配置结构。
     *
     * @param config 原始配置
     * @return 组件配置模型
     */
    private TeamScheduleQueryComponentConfig toComponentConfig(JSONObject config) {
        JSONObject source = config == null ? new JSONObject() : config;
        TeamScheduleQueryComponentConfig componentConfig = new TeamScheduleQueryComponentConfig();
        componentConfig.setTitle(defaultString(asString(source.get(CONFIG_KEY_TITLE))));
        componentConfig.setDescription(defaultString(asString(source.get(CONFIG_KEY_DESCRIPTION))));
        componentConfig.setDisplayMode(asString(source.get(CONFIG_KEY_DISPLAY_MODE)));
        Map<String, Object> range = asObjectMap(source.get(CONFIG_KEY_QUERY_RANGE));
        TeamScheduleQueryComponentConfig.QueryRange queryRange = new TeamScheduleQueryComponentConfig.QueryRange();
        queryRange.setType(asString(range.get(CONFIG_KEY_TYPE)));
        queryRange.setFutureDays(asInteger(range.get(CONFIG_KEY_FUTURE_DAYS)));
        queryRange.setStartDate(asString(range.get(CONFIG_KEY_START_DATE)));
        queryRange.setEndDate(asString(range.get(CONFIG_KEY_END_DATE)));
        componentConfig.setQueryRange(queryRange);
        return componentConfig;
    }

    /**
     * 将任意字符串键 Map 转换为稳定配置 Map。
     *
     * @param value 原值
     * @return 字符串键配置 Map
     */
    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 按个人配置语义转换整数。
     *
     * @param value 原值
     * @return 可转换整数，无法转换时返回 null
     */
    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /** 将任意值转换为去除首尾空白的字符串。 */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** 将空字符串规范为双引号空串。 */
    private String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    /** 将空白字符串替换为默认值。 */
    private String defaultString(String value, String fallback) {
        String normalized = defaultString(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    /**
     * 规范化无限制范围。
     *
     * @param range 目标范围
     */
    private void setUnlimitedRange(TeamScheduleQueryComponentConfig.QueryRange range) {
        range.setFutureDays(null);
        range.setStartDate(null);
        range.setEndDate(null);
    }

    /**
     * 规范化未来天数范围。
     *
     * @param source 原始范围
     * @param target 目标范围
     */
    private void setFutureDaysRange(
            TeamScheduleQueryComponentConfig.QueryRange source,
            TeamScheduleQueryComponentConfig.QueryRange target
    ) {
        Integer futureDays = source == null ? null : source.getFutureDays();
        if (futureDays == null || futureDays <= 0) {
            throw new BusinessException(FUTURE_DAYS_INVALID_MESSAGE);
        }
        target.setFutureDays(futureDays);
        target.setStartDate(null);
        target.setEndDate(null);
    }

    /**
     * 规范化固定日期范围。
     *
     * @param source 原始范围
     * @param target 目标范围
     */
    private void setDateRange(
            TeamScheduleQueryComponentConfig.QueryRange source,
            TeamScheduleQueryComponentConfig.QueryRange target
    ) {
        LocalDate startDate = parseDate(source == null ? null : source.getStartDate());
        LocalDate endDate = parseDate(source == null ? null : source.getEndDate());
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(DATE_RANGE_INVALID_MESSAGE);
        }
        target.setFutureDays(null);
        target.setStartDate(startDate.toString());
        target.setEndDate(endDate.toString());
    }

    /**
     * 解析严格的 ISO 日期。
     *
     * @param value 日期文本
     * @return 日期
     */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(DATE_RANGE_INVALID_MESSAGE);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(DATE_RANGE_INVALID_MESSAGE, exception);
        }
    }

    /**
     * 从配置模型构建字段完整的 JSON 快照。
     *
     * @param config 配置模型
     * @return JSON 快照
     */
    private JSONObject toJson(TeamScheduleQueryComponentConfig config) {
        JSONObject range = new JSONObject();
        range.put(CONFIG_KEY_TYPE, config.getQueryRange().getType());
        range.put(CONFIG_KEY_FUTURE_DAYS, config.getQueryRange().getFutureDays());
        range.put(CONFIG_KEY_START_DATE, config.getQueryRange().getStartDate());
        range.put(CONFIG_KEY_END_DATE, config.getQueryRange().getEndDate());
        JSONObject result = new JSONObject();
        result.put(CONFIG_KEY_TITLE, config.getTitle());
        result.put(CONFIG_KEY_DESCRIPTION, config.getDescription());
        result.put(CONFIG_KEY_DISPLAY_MODE, config.getDisplayMode());
        result.put(CONFIG_KEY_QUERY_RANGE, range);
        return result;
    }
}
