package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDetailResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;

/** 高级配置外层元数据适配；普通内容 DTO、字体计划和原始请求均不承载版本状态。 */
public final class AdvancedPortfolioVersionSupport {
    /** 完整配置根的唯一高级版本字段。 */
    public static final String FIELD = "advancedPortfolioVersion";
    /** 与高级保存入口一致的配置资源上限。 */
    private static final int CONFIG_MAX_BYTES = 2 * 1024 * 1024;
    /** 与既有高级配置树一致的容器深度。 */
    private static final int CONFIG_MAX_DEPTH = 20;
    /** 既有详情中的完整配置位置。 */
    private static final String CONFIG = "config";

    /** 缺省与显式版本分别保留，避免历史基准被补字段而改变摘要。 */
    public record Metadata(boolean present, int version) { }

    /** 工具类不实例化。 */
    private AdvancedPortfolioVersionSupport() { }

    /** 严格校验客户端回传的文档元数据，不能借此扩大请求能力。 */
    public static Metadata extract(JsonNode config, int clientVersion) {
        AdvancedVersionRegistry.resolve(clientVersion);
        Metadata metadata = stored(config);
        if (metadata.present()) {
            AdvancedVersionRegistry.resolve(metadata.version());
            if (metadata.version() > clientVersion) { throw invalid(); }
        }
        return metadata;
    }

    /** 读取可信快照的原表示；未知更高版本仍能用于拒绝旧端覆盖。 */
    public static Metadata stored(JsonNode config) {
        if (config == null || config.isNull() || !config.has(FIELD)) {
            return new Metadata(false, AdvancedVersionRegistry.LEGACY_VERSION);
        }
        JsonNode value = config.get(FIELD);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) { throw invalid(); }
        return new Metadata(true, value.intValue());
    }

    /** 从已有 JSON 读取元数据，空配置的原业务行为由调用方继续决定。 */
    public static Metadata stored(String configJson) { return stored(tree(configJson)); }

    /** 普通校验只接收内容副本，原请求和幂等指纹保持不变。 */
    public static ObjectNode content(JsonNode config) {
        if (!(config instanceof ObjectNode object)) { throw invalid(); }
        ObjectNode result = object.deepCopy();
        result.remove(FIELD);
        return result;
    }

    /** 普通规范化完成后恢复可信元数据的原表示，再进行完整保真校验。 */
    public static ObjectNode restore(JsonNode config, Metadata metadata) {
        ObjectNode result = content(config);
        if (metadata.present()) { result.put(FIELD, metadata.version()); }
        return result;
    }

    /** 成功保存的版本始终取本次请求，不读取服务端最高版本。 */
    public static ObjectNode saved(JsonNode config, int clientVersion) {
        AdvancedVersionRegistry.resolve(clientVersion);
        return restore(config, new Metadata(true, clientVersion));
    }

    /** 必须在当前权限、既有幂等和修订检查之后，以事务内草稿判断。 */
    public static void requireCanWrite(int clientVersion, JsonNode storedConfig) {
        AdvancedVersionRegistry.resolve(clientVersion);
        if (clientVersion < stored(storedConfig).version()) {
            throw new BusinessException(AdvancedPortfolioMessage.CLIENT_UPGRADE_REQUIRED);
        }
    }

    /** 数据库 JSON 的事务内写入门槛。 */
    public static void requireCanWrite(int clientVersion, String storedConfig) {
        requireCanWrite(clientVersion, tree(storedConfig));
    }

    /** 将普通规范化结果和本次保存版本一次序列化，标准入口保持原字节语义。 */
    public static String serializeSaved(Object config, String templateType, int clientVersion) {
        String json = JSON.toJSONString(config);
        return PortfolioTemplateTypeDict.ADVANCED.getCode().equals(templateType)
                ? saved(tree(json), clientVersion).toString() : json;
    }

    /** 发布、历史和只读响应只传递已有版本，不触发升级。 */
    public static String serializePreserved(Object config, String templateType, String sourceJson) {
        String json = JSON.toJSONString(config);
        return PortfolioTemplateTypeDict.ADVANCED.getCode().equals(templateType)
                ? restore(tree(json), stored(sourceJson)).toString() : json;
    }

    /** 在高级 HTTP 适配边界恢复结果快照的元数据，避免事务后重读混入另一修订。 */
    public static JSONObject response(Object detail) {
        Metadata metadata = detail instanceof MinePortfolioDetailResponse personal ? personal.getAdvancedConfigMetadata()
                : detail instanceof TeamPortfolioDetailResponse team ? team.getAdvancedConfigMetadata() : null;
        JSONObject result = JSON.parseObject(JSON.toJSONString(detail));
        JSONObject config = result.getJSONObject(CONFIG);
        if (config != null && metadata != null) {
            config.remove(FIELD);
            if (metadata.present()) { config.put(FIELD, metadata.version()); }
        }
        return result;
    }

    /** 统一解析完整配置；不会把空配置写回数据库。 */
    public static ObjectNode tree(String configJson) {
        return configJson == null || configJson.isBlank() ? JsonNodeFactory.instance.objectNode()
                : StrictAiJson.parseObject(configJson, CONFIG_MAX_BYTES, CONFIG_MAX_DEPTH);
    }

    /** 版本元数据损坏不得由规范化静默修复。 */
    private static BusinessException invalid() { return new BusinessException(AdvancedPortfolioMessage.INVALID_CONFIG); }
}
