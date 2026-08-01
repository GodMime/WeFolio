package com.jxc.wefolio.service.teamportfolio.component.textsection;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.PortfolioTextTypographySupport;
import com.jxc.wefolio.constant.PortfolioTextTypographyConstants;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

/**
 * 文字说明组件渲染器。
 */
@Component
public class TeamTextSectionComponentRenderer {

    /** 内容配置键。 */
    private static final String CONFIG_KEY_CONTENT = "content";

    /** 对齐方式配置键。 */
    private static final String CONFIG_KEY_ALIGNMENT = "alignment";

    /** 内容为空提示。 */
    private static final String CONTENT_REQUIRED_MESSAGE = "文字说明内容不能为空";

    /** 对齐方式不支持提示。 */
    private static final String ALIGNMENT_UNSUPPORTED_MESSAGE = "文字说明对齐方式不支持";

    /**
     * 返回与输入脱离的文字说明展示数据。
     *
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 文字说明展示数据
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        JSONObject source = normalizedConfig == null
                ? new JSONObject()
                : new JSONObject(normalizedConfig);
        Object content = source.get(CONFIG_KEY_CONTENT);
        if (content != null && !(content instanceof String)) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
        Object alignment = source.get(CONFIG_KEY_ALIGNMENT);
        if (alignment != null && !(alignment instanceof String)) {
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
        source.put(PortfolioTextTypographySupport.FONT_FAMILY_CONFIG_KEY, fontFamily);
        source.put(PortfolioTextTypographySupport.FONT_SIZE_RPX_CONFIG_KEY, fontSizeRpx);
        try {
            TeamTextSectionComponentConfig componentConfig =
                    source.toJavaObject(TeamTextSectionComponentConfig.class);
            return JSON.parseObject(JSON.toJSONString(componentConfig));
        } catch (RuntimeException exception) {
            throw new BusinessException(CONTENT_REQUIRED_MESSAGE);
        }
    }

}
