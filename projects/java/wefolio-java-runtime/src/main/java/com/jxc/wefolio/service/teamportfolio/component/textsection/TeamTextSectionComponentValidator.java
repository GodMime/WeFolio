package com.jxc.wefolio.service.teamportfolio.component.textsection;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 文字说明组件配置校验器。
 */
@Component
public class TeamTextSectionComponentValidator {

    /** 内容配置键。 */
    private static final String CONFIG_KEY_CONTENT = "content";

    /** 对齐方式配置键。 */
    private static final String CONFIG_KEY_ALIGNMENT = "alignment";

    /** 默认对齐方式。 */
    private static final String DEFAULT_ALIGNMENT = "LEFT";

    /** 文字最大 Unicode 码点数。 */
    private static final int CONTENT_MAX_CODE_POINT_COUNT = 200;

    /** 支持的文字对齐方式。 */
    private static final Set<String> SUPPORTED_ALIGNMENTS = Set.of("LEFT", "CENTER", "RIGHT");

    /** 内容为空提示。 */
    private static final String CONTENT_REQUIRED_MESSAGE = "文字说明内容不能为空";

    /** 内容过长提示。 */
    private static final String CONTENT_TOO_LONG_MESSAGE = "文字说明最多200个字符";

    /** 对齐方式不支持提示。 */
    private static final String ALIGNMENT_UNSUPPORTED_MESSAGE = "文字说明对齐方式不支持";

    /**
     * 校验并规范化文字说明组件配置。
     *
     * @param config 原始配置
     * @param context 组件上下文
     * @return 规范化文字说明配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamTextSectionComponentConfig componentConfig = toComponentConfig(config);
        String content = componentConfig.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
        if (content.codePointCount(0, content.length()) > CONTENT_MAX_CODE_POINT_COUNT) {
            throw new BusinessException(CONTENT_TOO_LONG_MESSAGE);
        }
        String alignment = componentConfig.getAlignment() == null ? DEFAULT_ALIGNMENT : componentConfig.getAlignment();
        if (!SUPPORTED_ALIGNMENTS.contains(alignment)) {
            throw new BusinessException(ALIGNMENT_UNSUPPORTED_MESSAGE);
        }
        componentConfig.setAlignment(alignment);
        return JSON.parseObject(JSON.toJSONString(componentConfig));
    }

    /**
     * 将客户端 JSON 转换为文字说明配置模型。
     *
     * @param config 原始配置
     * @return 文字说明配置模型
     */
    private TeamTextSectionComponentConfig toComponentConfig(JSONObject config) {
        if (config == null) {
            return new TeamTextSectionComponentConfig();
        }
        Object content = config.get(CONFIG_KEY_CONTENT);
        if (content != null && !(content instanceof String)) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
        Object alignment = config.get(CONFIG_KEY_ALIGNMENT);
        if (alignment != null && !(alignment instanceof String)) {
            throw new BusinessException(ALIGNMENT_UNSUPPORTED_MESSAGE);
        }
        try {
            return config.toJavaObject(TeamTextSectionComponentConfig.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
    }
}
