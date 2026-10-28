package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 本工程独立拥有的不可变 AI 运行规格；历史任务只能按明确版本对定位。 */
public final class AiPortfolioRunSpecRegistry {
    /** 冻结规格、前端声明和来源模式字段。 */
    public static final String RUN_SPEC_ID = "runSpecId";
    public static final String CLIENT_VERSION = "clientAdvancedVersion";
    public static final String SOURCE_MODE = "sourceMode";
    public static final String SELECTED_SOURCES = "SELECTED_SOURCES";
    public static final String TEXT_ONLY = "TEXT_ONLY";
    /** 冻结规则摘要字段，提交和规格校验共用。 */
    public static final String RULES_HASH = "rulesHash";
    /** 冻结请求的契约版本字段。 */
    private static final String CONTRACT_VERSION_FIELD = "contractVersion";
    /** 冻结请求的提示词版本字段。 */
    private static final String PROMPT_VERSION_FIELD = "promptVersion";
    /** 冻结请求的业务输入字段。 */
    private static final String INPUT_FIELD = "input";
    /** 客户端声明的 AI 契约版本字段。 */
    private static final String AI_CONTRACT_VERSION_FIELD = "aiContractVersion";
    /** 规则快照中的限制配置字段。 */
    private static final String LIMITS_FIELD = "limits";
    /** V2 对外协议保持不变，自定义节点规则使用新的冻结规格和提示词身份。 */
    public static final String V2_CONTRACT = "ai-portfolio-v2";
    /** 包含自定义节点标记的提示词版本。 */
    public static final String V2_PROMPT = "portfolio-ai-2026-10-28.1";
    /** 包含自定义节点标记的不可变运行规格身份。 */
    public static final String V2_RUN_SPEC_ID = "portfolio-v2-2026-10-28.1";
    /** 所有保留规格，不按发布时间推断或自动替换。 */
    private static final List<Spec> SPECS = List.of(
            new Spec("portfolio-v1-2026-10-10.2", 1, AiPortfolioProtocol.CONTRACT_VERSION, AiPortfolioProtocol.PROMPT_VERSION,
                    "/portfolioai/contract-v1.json", "/portfolioai/"),
            new Spec(V2_RUN_SPEC_ID, 2, V2_CONTRACT, V2_PROMPT,
                    "/portfolioai/contract-v2.json", "/portfolioai/prompts/v2/"));
    /** 启动时加载的独立快照，只能通过副本访问。 */
    private static final Map<String, ObjectNode> RULES = loadRules();
    private AiPortfolioRunSpecRegistry() { }

    private static Map<String, ObjectNode> loadRules() {
        Map<String, ObjectNode> rules = new LinkedHashMap<>();
        for (Spec spec : SPECS) { rules.put(spec.id(), StrictAiJson.parseObject(resource(spec.rulesResource()), StrictAiJson.CONFIG_MAX_BYTES, StrictAiJson.MAX_DEPTH)); }
        return Map.copyOf(rules);
    }

    /** 固定规格的完整身份，规则和提示词路径仅由代码登记。 */
    public record Spec(String id, int advancedVersion, String contractVersion, String promptVersion,
                       String rulesResource, String promptRoot) {
        /** 返回独立规则副本，调用方不能修改注册表。 */
        public ObjectNode rules() {
            return RULES.get(id).deepCopy();
        }
        /** 不复制完整目录即可读取该冻结规格的限制。 */
        public int limit(String field) { return RULES.get(id).path(LIMITS_FIELD).path(field).intValue(); }
        /** 规则快照的确定性摘要，随新任务一起冻结。 */
        public String rulesHash() { return StrictAiJson.canonicalHash(RULES.get(id)); }
        /** 只接受已登记的模板名，不拼接任意输入路径。 */
        public String prompt(String template) {
            if (!List.of("system.txt", "CREATE.txt", "MODIFY_PAGE.txt", "retry.txt").contains(template)) { throw unavailable(); }
            return resource(promptRoot + template);
        }
    }

    /** 缺省是 V1；显式 null、类型强转、未知版本都拒绝。 */
    public static int clientVersion(JsonNode input) { return forVersion(frozenVersion(input)).advancedVersion(); }

    /** 查询门槛可以识别未来任务的正整数声明，不因此开放该规格的执行。 */
    public static int frozenVersion(JsonNode input) {
        JsonNode value = input.get(CLIENT_VERSION);
        if (value == null) { return 1; }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) { throw unavailable(); }
        return value.intValue();
    }

    /** 本期仅登记已实现的累计业务能力。 */
    public static Spec forVersion(int version) {
        return SPECS.stream().filter(spec -> spec.advancedVersion() == version).findFirst().orElseThrow(AiPortfolioRunSpecRegistry::unavailable);
    }

    /** 新冻结规格与原版本对双向核对，历史缺失规格仅按唯一版本对恢复。 */
    public static Spec resolve(ObjectNode request) {
        JsonNode id = request.get(RUN_SPEC_ID);
        String contract = request.path(CONTRACT_VERSION_FIELD).asText();
        String prompt = request.path(PROMPT_VERSION_FIELD).asText();
        List<Spec> matched = SPECS.stream().filter(spec -> spec.contractVersion().equals(contract)
                && spec.promptVersion().equals(prompt) && (id == null || id.isTextual() && spec.id().equals(id.textValue()))).toList();
        if (matched.size() != 1) { throw unavailable(); }
        Spec spec = matched.getFirst();
        if (id == null && spec.advancedVersion() != 1) { throw unavailable(); }
        JsonNode input = request.path(INPUT_FIELD);
        if (clientVersion(input) != spec.advancedVersion()) { throw unavailable(); }
        if (input.has(AI_CONTRACT_VERSION_FIELD) && (!input.path(AI_CONTRACT_VERSION_FIELD).isTextual()
                || !spec.contractVersion().equals(input.path(AI_CONTRACT_VERSION_FIELD).asText()))) { throw unavailable(); }
        if (request.has(RULES_HASH) && (!request.path(RULES_HASH).isTextual() || !spec.rulesHash().equals(request.path(RULES_HASH).textValue()))) { throw unavailable(); }
        return spec;
    }

    /** 固定 classpath 资源读取，不依赖远端目录或当前默认版本。 */
    private static String resource(String path) {
        try (var stream = AiPortfolioRunSpecRegistry.class.getResourceAsStream(path)) {
            if (stream == null) { throw unavailable(); }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw unavailable(); }
    }
    /** 保留历史不可用错误语义。 */
    private static AiProtocolException unavailable() {
        return new AiProtocolException("CONTRACT_UNAVAILABLE", "当前 AI 契约不可用，请重新生成");
    }
}
