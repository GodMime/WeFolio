package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import lombok.extern.slf4j.Slf4j;

/** 冻结供应商契约的共享入口；旧任务不得悄然转发给新供应商。 */
@Slf4j
final class PortfolioAiModelContract {
    /** 冻结模型配置所在字段。 */
    private static final String MODEL_CONFIG = "modelConfig";
    /** 冻结供应商身份字段。 */
    private static final String PROVIDER = "provider";
    /** 冻结模型名称字段。 */
    private static final String MODEL = "model";
    /** 冻结模型根地址字段。 */
    private static final String BASE_URL = "baseUrl";
    /** 新任务冻结的媒体能力开关，旧任务可无此字段。 */
    private static final String MEDIA_ENABLED = "mediaEnabled";
    /** 服务端冻结事实与媒体计划字段。 */
    private static final String FACTS = "facts";
    private static final String MEDIA_PLAN = "mediaPlan";
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

    /** 开关差异仅影响部署诊断；冻结媒体任务始终执行原契约，旧文字任务保持原行为。 */
    static void warnMediaSwitchMismatch(AiGenerationTaskEntity task, ObjectNode request, PortfolioAiProperties properties) {
        JsonNode frozen = request.path(MODEL_CONFIG).path(MEDIA_ENABLED);
        boolean hasPlan = request.path(FACTS).has(MEDIA_PLAN);
        if ((frozen.isBoolean() || hasPlan)
                && (frozen.isBoolean() ? frozen.booleanValue() : hasPlan) != properties.isMediaEnabled()) {
            log.warn("高级 AI 媒体开关配置不一致: taskId={}, requestedByUniqueCode={}, runtimeMediaEnabled={}, jobMediaEnabled={}, 配置项=WEFOLIO_AI_MEDIA_ENABLED",
                    task.getId(), task.getRequestedByUniqueCode(), frozen.isBoolean() ? frozen.booleanValue() : hasPlan, properties.isMediaEnabled());
        }
    }
}
