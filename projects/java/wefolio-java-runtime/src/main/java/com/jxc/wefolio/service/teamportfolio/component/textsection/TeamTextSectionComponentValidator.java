package com.jxc.wefolio.service.teamportfolio.component.textsection;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.common.PortfolioTextTypographySupport;
import com.jxc.wefolio.constant.PortfolioTextTypographyConstants;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
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
        JSONObject source = config == null ? new JSONObject() : config;
        Object contentSource = source.get(CONFIG_KEY_CONTENT);
        if (contentSource != null && !(contentSource instanceof String)) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
        String content = (String) contentSource;
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
        if (content.codePointCount(0, content.length()) > CONTENT_MAX_CODE_POINT_COUNT) {
            throw new BusinessException(CONTENT_TOO_LONG_MESSAGE);
        }
        Object alignmentSource = source.get(CONFIG_KEY_ALIGNMENT);
        if (alignmentSource != null && !(alignmentSource instanceof String)) {
            throw new BusinessException(ALIGNMENT_UNSUPPORTED_MESSAGE);
        }
        String alignment = alignmentSource == null ? DEFAULT_ALIGNMENT : (String) alignmentSource;
        if (!SUPPORTED_ALIGNMENTS.contains(alignment)) {
            throw new BusinessException(ALIGNMENT_UNSUPPORTED_MESSAGE);
        }
        Object fontFamilySource = source.get(PortfolioTextTypographySupport.FONT_FAMILY_CONFIG_KEY);
        if (fontFamilySource != null && !(fontFamilySource instanceof String)) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_FONT_UNSUPPORTED_MESSAGE);
        }
        String fontFamily = fontFamilySource == null
                ? PortfolioTextFontFamilyDict.SYSTEM.getCode()
                : PortfolioTextTypographySupport.asSupportedFontFamily(fontFamilySource);
        if (fontFamily == null) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_FONT_UNSUPPORTED_MESSAGE);
        }
        Object fontSizeSource = source.get(PortfolioTextTypographySupport.FONT_SIZE_RPX_CONFIG_KEY);
        if (fontSizeSource != null && !(fontSizeSource instanceof Number)) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE);
        }
        Integer fontSizeRpx = fontSizeSource == null
                ? Integer.valueOf(PortfolioTextTypographyConstants.LEGACY_TEAM_FONT_SIZE_RPX)
                : PortfolioTextTypographySupport.asExactFontSizeRpx(fontSizeSource);
        if (!PortfolioTextTypographySupport.isValidFontSizeRpx(fontSizeRpx)) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE);
        }

        TeamTextSectionComponentConfig componentConfig = new TeamTextSectionComponentConfig();
        componentConfig.setContent(content);
        componentConfig.setAlignment(alignment);
        componentConfig.setFontFamily(fontFamily);
        componentConfig.setFontSizeRpx(fontSizeRpx);
        return JSON.parseObject(JSON.toJSONString(componentConfig));
    }

}
