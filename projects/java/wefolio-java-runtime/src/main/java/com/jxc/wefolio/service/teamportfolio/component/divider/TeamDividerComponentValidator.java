package com.jxc.wefolio.service.teamportfolio.component.divider;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 分割线组件配置校验器。
 */
@Component
public class TeamDividerComponentValidator {

    /** 颜色配置键。 */
    private static final String CONFIG_KEY_COLOR = "color";

    /** 高度配置键。 */
    private static final String CONFIG_KEY_HEIGHT_PX = "heightPx";

    /** 默认分割线颜色。 */
    private static final String DEFAULT_COLOR = "GRAY";

    /** 默认分割线高度。 */
    private static final int DEFAULT_HEIGHT_PX = 16;

    /** 支持的分割线颜色。 */
    private static final Set<String> SUPPORTED_COLORS = Set.of("BLACK", "WHITE", "GRAY", "TRANSPARENT");

    /** 颜色不支持提示。 */
    private static final String COLOR_UNSUPPORTED_MESSAGE = "分割线颜色不支持";

    /** 高度非法提示。 */
    private static final String HEIGHT_INVALID_MESSAGE = "分割线高度必须大于0";

    /**
     * 校验并规范化分割线组件配置。
     *
     * @param config 原始配置
     * @param context 组件上下文
     * @return 规范化分割线配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        TeamDividerComponentConfig componentConfig = toComponentConfig(config);
        String color = componentConfig.getColor() == null ? DEFAULT_COLOR : componentConfig.getColor();
        if (!SUPPORTED_COLORS.contains(color)) {
            throw new BusinessException(COLOR_UNSUPPORTED_MESSAGE);
        }
        Integer heightPx = componentConfig.getHeightPx() == null ? DEFAULT_HEIGHT_PX : componentConfig.getHeightPx();
        if (heightPx == null || heightPx <= 0) {
            throw new BusinessException(HEIGHT_INVALID_MESSAGE);
        }
        componentConfig.setColor(color);
        componentConfig.setHeightPx(heightPx);
        return JSON.parseObject(JSON.toJSONString(componentConfig));
    }

    /**
     * 将客户端 JSON 转换为分割线配置模型。
     *
     * @param config 原始配置
     * @return 分割线配置模型
     */
    private TeamDividerComponentConfig toComponentConfig(JSONObject config) {
        if (config == null) {
            return new TeamDividerComponentConfig();
        }
        Object color = config.get(CONFIG_KEY_COLOR);
        if (color != null && !(color instanceof String)) {
            throw new BusinessException(COLOR_UNSUPPORTED_MESSAGE);
        }
        Object heightPx = config.get(CONFIG_KEY_HEIGHT_PX);
        if (heightPx != null && !(heightPx instanceof Integer)) {
            throw new BusinessException(HEIGHT_INVALID_MESSAGE);
        }
        try {
            return config.toJavaObject(TeamDividerComponentConfig.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(HEIGHT_INVALID_MESSAGE);
        }
    }
}
