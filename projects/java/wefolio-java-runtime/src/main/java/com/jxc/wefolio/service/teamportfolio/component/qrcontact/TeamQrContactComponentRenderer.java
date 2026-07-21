package com.jxc.wefolio.service.teamportfolio.component.qrcontact;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import org.springframework.stereotype.Component;

/**
 * 团队二维码联系组件渲染器。
 */
@Component
public class TeamQrContactComponentRenderer {

    /** 二维码来源配置键。 */
    private static final String CONFIG_KEY_QR_URL_SOURCE = "qrUrlSource";

    /** 二维码地址配置键。 */
    private static final String CONFIG_KEY_QR_URL = "qrUrl";

    /** 二维码联系配置校验器。 */
    private final TeamQrContactComponentValidator validator;

    /**
     * 创建二维码联系渲染器。
     *
     * @param validator 二维码联系配置校验器
     */
    public TeamQrContactComponentRenderer(TeamQrContactComponentValidator validator) {
        this.validator = validator;
    }

    /**
     * 重新校验配置并生成访客与维护端共用的展示快照。
     *
     * @param normalizedConfig 原始或归一化配置
     * @param context 团队作品集组件上下文
     * @return 脱离输入的渲染快照
     */
    public JSONObject render(JSONObject normalizedConfig, TeamPortfolioComponentContext context) {
        JSONObject validatedConfig = validator.normalizeStructure(normalizedConfig, context);
        TeamQrContactComponentConfig config = validatedConfig.toJavaObject(TeamQrContactComponentConfig.class);
        return toJson(config);
    }

    /**
     * 将配置模型按稳定字段顺序转换为 JSON。
     *
     * @param config 配置模型
     * @return 渲染 JSON
     */
    private JSONObject toJson(TeamQrContactComponentConfig config) {
        JSONObject result = new JSONObject();
        result.put(CONFIG_KEY_QR_URL_SOURCE, config.getQrUrlSource());
        result.put(CONFIG_KEY_QR_URL, config.getQrUrl());
        return result;
    }
}
