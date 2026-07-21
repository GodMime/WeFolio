package com.jxc.wefolio.service.teamportfolio.component.divider;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

/**
 * 分割线组件渲染器。
 */
@Component
public class TeamDividerComponentRenderer {

    /** 颜色不支持提示。 */
    private static final String COLOR_UNSUPPORTED_MESSAGE = "分割线颜色不支持";

    /** 高度非法提示。 */
    private static final String HEIGHT_INVALID_MESSAGE = "分割线高度必须大于0";

    /** 颜色配置键。 */
    private static final String CONFIG_KEY_COLOR = "color";

    /** 高度配置键。 */
    private static final String CONFIG_KEY_HEIGHT_PX = "heightPx";

    /**
     * 返回与输入脱离的分割线展示数据。
     *
     * @param normalizedConfig 规范化配置
     * @param context 组件上下文
     * @return 分割线展示数据
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        if (normalizedConfig != null) {
            Object color = normalizedConfig.get(CONFIG_KEY_COLOR);
            if (color != null && !(color instanceof String)) {
                throw new BusinessException(COLOR_UNSUPPORTED_MESSAGE);
            }
            Object heightPx = normalizedConfig.get(CONFIG_KEY_HEIGHT_PX);
            if (heightPx != null && !(heightPx instanceof Integer)) {
                throw new BusinessException(HEIGHT_INVALID_MESSAGE);
            }
        }
        try {
            TeamDividerComponentConfig componentConfig = normalizedConfig == null
                    ? new TeamDividerComponentConfig()
                    : normalizedConfig.toJavaObject(TeamDividerComponentConfig.class);
            return JSON.parseObject(JSON.toJSONString(componentConfig));
        } catch (RuntimeException exception) {
            throw new BusinessException(HEIGHT_INVALID_MESSAGE);
        }
    }
}
