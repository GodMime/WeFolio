package com.jxc.wefolio.service.teamportfolio.component.contactform;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 团队预留联系信息组件配置校验器。
 */
@Component
public class TeamContactFormComponentValidator {

    /** 标题配置键。 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 描述配置键。 */
    private static final String CONFIG_KEY_DESCRIPTION = "description";

    /** 展示方式配置键。 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 表单字段配置键。 */
    private static final String CONFIG_KEY_FIELDS = "fields";

    /** 联系人字段。 */
    private static final String FIELD_CONTACT_NAME = "contactName";

    /** 手机号字段。 */
    private static final String FIELD_PHONE = "phone";

    /** 微信号字段。 */
    private static final String FIELD_WECHAT = "wechat";

    /** 需求字段。 */
    private static final String FIELD_NEEDS = "needs";

    /** 弹层表单展示方式。 */
    private static final String DISPLAY_MODE_MODAL_FORM = "MODAL_FORM";

    /** 内联表单展示方式。 */
    private static final String DISPLAY_MODE_INLINE_FORM = "INLINE_FORM";

    /** 默认表单字段。 */
    private static final List<String> DEFAULT_FIELDS = List.of(
            FIELD_CONTACT_NAME, FIELD_PHONE, FIELD_WECHAT, FIELD_NEEDS);

    /** 支持的展示方式。 */
    private static final Set<String> SUPPORTED_DISPLAY_MODES = Set.of(
            DISPLAY_MODE_MODAL_FORM, DISPLAY_MODE_INLINE_FORM);

    /** 展示方式非法提示。 */
    private static final String DISPLAY_MODE_INVALID_MESSAGE = "预留联系信息展示方式不支持";

    /** 联系人字段缺失提示。 */
    private static final String CONTACT_NAME_FIELD_REQUIRED_MESSAGE = "预留联系信息必须包含联系人字段";

    /** 联系方式字段缺失提示。 */
    private static final String CONTACT_METHOD_FIELD_REQUIRED_MESSAGE = "预留联系信息必须包含手机号或微信号字段";

    /** 组件上下文非法提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "预留联系信息组件上下文不正确";

    /**
     * 校验并规范化团队预留联系信息组件配置。
     *
     * @param config 原始配置
     * @param context 团队组件上下文
     * @return 规范化后的独立 JSON 配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        validateContext(context);
        TeamContactFormComponentConfig componentConfig = toComponentConfig(config);
        String displayMode = defaultDisplayMode(componentConfig.getDisplayMode());
        if (!SUPPORTED_DISPLAY_MODES.contains(displayMode)) {
            throw new BusinessException(DISPLAY_MODE_INVALID_MESSAGE);
        }
        List<String> fields = normalizeFields(config == null ? null : config.get(CONFIG_KEY_FIELDS));
        validateFields(fields);
        componentConfig.setTitle(normalizeString(componentConfig.getTitle()));
        componentConfig.setDescription(normalizeString(componentConfig.getDescription()));
        componentConfig.setDisplayMode(displayMode);
        componentConfig.setFields(fields);
        return JSON.parseObject(JSON.toJSONString(componentConfig));
    }

    /**
     * 将原始 JSON 转换为组件私有配置模型。
     *
     * @param config 原始配置
     * @return 配置模型
     */
    private TeamContactFormComponentConfig toComponentConfig(JSONObject config) {
        JSONObject source = config == null ? new JSONObject() : config;
        TeamContactFormComponentConfig result = new TeamContactFormComponentConfig();
        result.setTitle(asString(source.get(CONFIG_KEY_TITLE)));
        result.setDescription(asString(source.get(CONFIG_KEY_DESCRIPTION)));
        result.setDisplayMode(asString(source.get(CONFIG_KEY_DISPLAY_MODE)));
        return result;
    }

    /**
     * 规范化字段集合并保留首次出现的顺序。
     *
     * @param value 原始字段值
     * @return 规范化字段列表
     */
    private List<String> normalizeFields(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return DEFAULT_FIELDS;
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (Object field : collection) {
            String normalized = asString(field);
            if (!normalized.isBlank()) {
                fields.add(normalized);
            }
        }
        return fields.isEmpty() ? new ArrayList<>(DEFAULT_FIELDS) : new ArrayList<>(fields);
    }

    /**
     * 校验团队组件上下文。
     *
     * @param context 团队组件上下文
     */
    private void validateContext(TeamPortfolioComponentContext context) {
        if (context == null || context.teamId() <= 0 || context.portfolioId() <= 0 || context.revision() < 0) {
            throw new BusinessException(CONTEXT_INVALID_MESSAGE);
        }
    }

    /**
     * 校验字段组合满足访客留资要求。
     *
     * @param fields 规范化字段列表
     */
    private void validateFields(List<String> fields) {
        if (!fields.contains(FIELD_CONTACT_NAME)) {
            throw new BusinessException(CONTACT_NAME_FIELD_REQUIRED_MESSAGE);
        }
        if (!fields.contains(FIELD_PHONE) && !fields.contains(FIELD_WECHAT)) {
            throw new BusinessException(CONTACT_METHOD_FIELD_REQUIRED_MESSAGE);
        }
    }

    /**
     * 规范化展示方式并补充默认值。
     *
     * @param value 原始展示方式
     * @return 规范化展示方式
     */
    private String defaultDisplayMode(String value) {
        String normalized = normalizeString(value);
        return normalized.isBlank() ? DISPLAY_MODE_MODAL_FORM : normalized;
    }

    /**
     * 按个人配置兼容语义转换字符串。
     *
     * @param value 原始值
     * @return 去除首尾空白后的字符串
     */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 规范化可空字符串。
     *
     * @param value 原始字符串
     * @return 非空字符串
     */
    private String normalizeString(String value) {
        return value == null ? "" : value.strip();
    }
}
