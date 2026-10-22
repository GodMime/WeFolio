package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.service.portfolioai.AiPortfolioProtocol;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;

import java.util.List;

/** 新生成名称的提示契约与硬上限，不改变 Runtime 对历史候选和已有名称的采用规则。 */
final class PortfolioAiTitlePolicy {
    /** 自动总结名称的默认长度，用户明确指定名称或长度时可覆盖。 */
    static final int DEFAULT_MAX_CODE_POINTS = 12;
    /** 新生成或主动改名的绝对上限，与编辑器名称长度一致。 */
    static final int MAX_CODE_POINTS = 50;
    /** 新生成和页面修改分别提供分享标题的字段契约。 */
    private static final List<String> TITLE_SCHEMA_PATHS = List.of(
            "/createSettings/share/params/title", "/globalFields/share/params/title");
    /** 模型名称字段的语义与计数口径。 */
    private static final String TITLE_DESCRIPTION = "作品集名称，用于列表与微信分享，不是正文标题。自动总结默认不超过%d个字符；用户指定名称或长度时优先遵从，但最多%d个字符。按Unicode码点计数，汉字、字母、数字、标点和空格均计入。";
    /** 契约长度与说明字段。 */
    private static final String MAX_LENGTH_FIELD = "maxCodePoints";
    /** 名称规则写入模型可读的字段说明。 */
    private static final String DESCRIPTION_FIELD = "description";
    /** 只有生成候选才需要校验名称，澄清没有分享配置。 */
    private static final String STATUS_FIELD = "status";
    /** 可采用候选状态。 */
    private static final String CANDIDATE_STATUS = "CANDIDATE";
    /** 首次生成必须主动输出名称。 */
    private static final String MODE_FIELD = "mode";
    /** 首次生成模式值。 */
    private static final String CREATE_MODE = "CREATE";
    /** 首次生成的名称字段位置。 */
    private static final String CREATE_TITLE_PATH = "/page/settings/share/params/title";
    /** 修改模式只检查实际输出的分享名称，不校验或改写保留的历史名称。 */
    private static final String OPERATIONS_FIELD = "operations";
    /** 页面修改的语义操作字段。 */
    private static final String OP_FIELD = "op";
    /** 修改全局分享设置的操作值。 */
    private static final String UPDATE_GLOBAL_OP = "UPDATE_GLOBAL";
    /** 全局操作目标分组位置。 */
    private static final String SECTION_PATH = "/payload/section";
    /** 分享配置分组。 */
    private static final String SHARE_SECTION = "share";
    /** 主动改名的新名称位置。 */
    private static final String UPDATED_TITLE_PATH = "/payload/params/title";
    /** 固定安全纠错信息沿用现有有界重试，不回显用户名称。 */
    private static final String INVALID_OUTPUT_CODE = "INVALID_OUTPUT";
    /** 缺失名称时要求模型重新按原始意图拟名。 */
    private static final String MISSING_TITLE_MESSAGE = "首次生成必须在 page.settings.share.params.title 输出非空作品集名称：优先遵从用户命名要求，无要求时自动总结不超过12个字符的名称，最多50个字符，供微信分享使用";
    /** 超长名称应重新总结，不静默截断。 */
    private static final String TITLE_TOO_LONG_MESSAGE = "分享名称 share.title 不能超过50个字符（按Unicode码点计数）；自动总结默认不超过12个字符，用户有长度要求时遵从但最多50个字符，请压缩名称后重新输出完整候选";

    /** 工具类不可实例化。 */
    private PortfolioAiTitlePolicy() { }

    /** 仅收紧发给模型的新生成契约，共享协议仍可读取部署前保存的长名称候选。 */
    static ObjectNode contract(ObjectNode request) {
        ObjectNode contract = AiPortfolioProtocol.contract(request);
        for (String path : TITLE_SCHEMA_PATHS) {
            if (contract.at(path) instanceof ObjectNode title) {
                title.put(MAX_LENGTH_FIELD, MAX_CODE_POINTS)
                        .put(DESCRIPTION_FIELD, TITLE_DESCRIPTION.formatted(DEFAULT_MAX_CODE_POINTS, MAX_CODE_POINTS));
            }
        }
        return contract;
    }

    /** 在普通协议校验之后检查新产出，缺失或超长名称均不能成为成功候选。 */
    static void validate(ObjectNode output) {
        if (!CANDIDATE_STATUS.equals(output.path(STATUS_FIELD).asText())) { return; }
        if (CREATE_MODE.equals(output.path(MODE_FIELD).asText())) {
            JsonNode title = output.at(CREATE_TITLE_PATH);
            if (title.asText("").isBlank()) { throw new AiProtocolException(INVALID_OUTPUT_CODE, MISSING_TITLE_MESSAGE); }
            validateLength(title);
        } else {
            for (JsonNode operation : output.path(OPERATIONS_FIELD)) {
                if (UPDATE_GLOBAL_OP.equals(operation.path(OP_FIELD).asText())
                        && SHARE_SECTION.equals(operation.at(SECTION_PATH).asText())) {
                    validateLength(operation.at(UPDATED_TITLE_PATH));
                }
            }
        }
    }

    /** 按 Unicode 码点计数，不能把补充平面字符误算成两个字符或直接截断用户名称。 */
    private static void validateLength(JsonNode title) {
        if (title.isTextual() && title.textValue().codePointCount(0, title.textValue().length()) > MAX_CODE_POINTS) {
            throw new AiProtocolException(INVALID_OUTPUT_CODE, TITLE_TOO_LONG_MESSAGE);
        }
    }
}
