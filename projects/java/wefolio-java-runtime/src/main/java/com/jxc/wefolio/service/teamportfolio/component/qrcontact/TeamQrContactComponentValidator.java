package com.jxc.wefolio.service.teamportfolio.component.qrcontact;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 团队二维码联系组件配置校验器。
 */
@Component
@RequiredArgsConstructor
public class TeamQrContactComponentValidator {

    /** 二维码来源配置键。 */
    private static final String CONFIG_KEY_QR_URL_SOURCE = "qrUrlSource";

    /** 二维码地址配置键。 */
    private static final String CONFIG_KEY_QR_URL = "qrUrl";

    /** 自定义二维码来源编码。 */
    private static final String CUSTOM = "CUSTOM";

    /** 配置不能为空提示。 */
    private static final String CONFIG_REQUIRED_MESSAGE = "二维码联系配置不能为空";

    /** 上下文无效提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "二维码联系上下文无效";

    /** 二维码来源不合法提示。 */
    private static final String QR_URL_SOURCE_INVALID_MESSAGE = "二维码联系仅支持自定义二维码";

    /** 二维码地址不能为空提示。 */
    private static final String QR_URL_REQUIRED_MESSAGE = "二维码联系二维码不能为空";

    /** 合法业务标识的最小值。 */
    private static final long MINIMUM_VALID_ID = 0L;

    /** 合法版本号的最小值。 */
    private static final int MINIMUM_VALID_REVISION = 0;

    /** 团队作品集素材基础服务。 */
    private final TeamPortfolioAssetService teamPortfolioAssetService;

    /**
     * 归一化并校验团队二维码联系配置。
     *
     * @param config 原始配置
     * @param context 团队作品集组件上下文
     * @return 脱离原始输入的规范化配置
     */
    public JSONObject normalizeAndValidate(JSONObject config, TeamPortfolioComponentContext context) {
        JSONObject normalized = normalizeStructure(config, context);
        teamPortfolioAssetService.validateUploadedImageUrl(
                context.teamId(), context.portfolioId(), normalized.getString(CONFIG_KEY_QR_URL));
        return normalized;
    }

    /**
     * 仅执行二维码配置结构归一化，不访问素材服务或 COS。
     *
     * @param config 原始配置
     * @param context 团队作品集组件上下文
     * @return 稳定二维码配置
     */
    JSONObject normalizeStructure(JSONObject config, TeamPortfolioComponentContext context) {
        validateContext(context);
        if (config == null) {
            throw new BusinessException(CONFIG_REQUIRED_MESSAGE);
        }
        TeamQrContactComponentConfig normalizedConfig = new TeamQrContactComponentConfig();
        normalizedConfig.setQrUrlSource(normalizeQrUrlSource(config));
        String qrUrl = normalizeQrUrl(config);
        normalizedConfig.setQrUrl(qrUrl);
        return toJson(normalizedConfig);
    }

    /**
     * 校验并归一化二维码来源。
     *
     * @param config 原始配置
     * @return 固定的自定义来源
     */
    private String normalizeQrUrlSource(JSONObject config) {
        Object sourceValue = config.get(CONFIG_KEY_QR_URL_SOURCE);
        if (!config.containsKey(CONFIG_KEY_QR_URL_SOURCE)
                || !(sourceValue instanceof String source)
                || !CUSTOM.equals(source.strip())) {
            throw new BusinessException(QR_URL_SOURCE_INVALID_MESSAGE);
        }
        return CUSTOM;
    }

    /**
     * 校验团队作品集组件上下文。
     *
     * @param context 团队作品集组件上下文
     */
    private void validateContext(TeamPortfolioComponentContext context) {
        if (context == null
                || context.teamId() <= MINIMUM_VALID_ID
                || context.portfolioId() <= MINIMUM_VALID_ID
                || context.revision() < MINIMUM_VALID_REVISION) {
            throw new BusinessException(CONTEXT_INVALID_MESSAGE);
        }
    }

    /**
     * 校验并归一化二维码地址。
     *
     * @param config 原始配置
     * @return 清理后的二维码地址
     */
    private String normalizeQrUrl(JSONObject config) {
        Object qrUrlValue = config.get(CONFIG_KEY_QR_URL);
        if (!config.containsKey(CONFIG_KEY_QR_URL) || !(qrUrlValue instanceof String qrUrl)) {
            throw new BusinessException(QR_URL_REQUIRED_MESSAGE);
        }
        String normalizedQrUrl = qrUrl.strip();
        if (normalizedQrUrl.isEmpty()) {
            throw new BusinessException(QR_URL_REQUIRED_MESSAGE);
        }
        return normalizedQrUrl;
    }

    /**
     * 将配置模型按稳定字段顺序转换为 JSON。
     *
     * @param config 配置模型
     * @return 配置 JSON
     */
    private JSONObject toJson(TeamQrContactComponentConfig config) {
        JSONObject result = new JSONObject();
        result.put(CONFIG_KEY_QR_URL_SOURCE, config.getQrUrlSource());
        result.put(CONFIG_KEY_QR_URL, config.getQrUrl());
        return result;
    }
}
