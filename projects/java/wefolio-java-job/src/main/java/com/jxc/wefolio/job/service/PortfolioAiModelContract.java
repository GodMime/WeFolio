package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;

/** 冻结供应商契约的共享入口；旧任务不得悄然转发给新供应商。 */
final class PortfolioAiModelContract {
    /** 冻结模型配置所在字段。 */
    private static final String MODEL_CONFIG = "modelConfig";
    /** 冻结供应商身份字段。 */
    private static final String PROVIDER = "provider";
    /** 冻结模型名称字段。 */
    private static final String MODEL = "model";
    /** 冻结模型根地址字段。 */
    private static final String BASE_URL = "baseUrl";
    /** 配置缺失或迁移后的统一安全终态。 */
    private static final String CONTRACT_UNAVAILABLE = "CONTRACT_UNAVAILABLE";
    /** 提示重新生成以获得当前供应商的完整冻结配置。 */
    private static final String UNAVAILABLE_MESSAGE = "当前模型契约不可用，请重新生成";

    private PortfolioAiModelContract() { }

    /** 领取前和实际传输前均校验，不能用当前配置补齐历史任务。 */
    static void validate(ObjectNode request, PortfolioAiProperties properties) {
        JsonNode config = request.path(MODEL_CONFIG);
        String currentBaseUrl = PortfolioAiProperties.canonicalBaseUrl(properties.getBaseUrl());
        boolean valid = config.isObject() && config.path(PROVIDER).isTextual() && config.path(MODEL).isTextual()
                && config.path(BASE_URL).isTextual()
                && PortfolioAiProperties.TOKENHUB_PROVIDER.equals(config.path(PROVIDER).textValue())
                && PortfolioAiProperties.TOKENHUB_MODEL.equals(properties.getModel())
                && properties.getModel().equals(config.path(MODEL).textValue())
                && PortfolioAiProperties.TOKENHUB_BASE_URL.equals(currentBaseUrl)
                && currentBaseUrl.equals(PortfolioAiProperties.canonicalBaseUrl(config.path(BASE_URL).textValue()));
        if (!valid) { throw new AiProtocolException(CONTRACT_UNAVAILABLE, UNAVAILABLE_MESSAGE); }
    }
}
