package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceService;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 高级保存专有元数据；在普通最终保存短事务内校验并与配置一致写入。 */
public record AdvancedPortfolioSaveMetadata(JSONObject context, boolean contextPresent,
                                            String prompt, boolean promptPresent, String fingerprint,
                                            ArrayNode referenceAttachments, boolean referencesPresent, Integer referenceNextNo,
                                            int clientAdvancedVersion,
                                            AdvancedPortfolioVersionSupport.Metadata configVersion) {
    /** 旧内部调用继续按 V1 解释，不改写既有指纹。 */
    public AdvancedPortfolioSaveMetadata(JSONObject context, boolean contextPresent, String prompt,
            boolean promptPresent, String fingerprint, ArrayNode referenceAttachments,
            boolean referencesPresent, Integer referenceNextNo) {
        this(context, contextPresent, prompt, promptPresent, fingerprint, referenceAttachments,
                referencesPresent, referenceNextNo, AdvancedVersionRegistry.LEGACY_VERSION,
                new AdvancedPortfolioVersionSupport.Metadata(false, AdvancedVersionRegistry.LEGACY_VERSION));
    }
    /** 兼容不携带附件的既有保存调用。 */
    public AdvancedPortfolioSaveMetadata(JSONObject context, boolean contextPresent, String prompt,
            boolean promptPresent, String fingerprint) {
        this(context, contextPresent, prompt, promptPresent, fingerprint, null, false, null);
    }
    /** 上下文结构版本。 */
    private static final int CONTEXT_VERSION = 1;
    /** 来源上下文大小上限。 */
    private static final int CONTEXT_MAX_BYTES = 128 * 1024;
    /** 上下文顶层允许字段。 */
    private static final Set<String> CONTEXT_KEYS = Set.of("version", "nextSourceNo", "sources");
    /** 来源条目允许字段。 */
    private static final Set<String> SOURCE_KEYS = Set.of("sourceNo", "kind", "sourceId");
    /** 支持的来源类型。 */
    private static final Set<String> SOURCE_KINDS = Set.of("WORK", "MEMBER_PORTFOLIO");

    /** 最终事务以已锁定的数据库草稿判断，客户端元数据无法绕过此门槛。 */
    public void requireWritable(PortfolioEntity portfolio) {
        AdvancedPortfolioVersionSupport.requireCanWrite(clientAdvancedVersion, portfolio.getDraftConfigJson());
    }

    /** 幂等、修订与版本门槛之后校验原始内容，DTO 的宽松转换不能掩盖非法输入。 */
    public void validateConfig(boolean team) {
        AdvancedPortfolioConfigGuard.validate(
                StrictAiJson.parseObject(fingerprint, 2 * 1024 * 1024, 20).withObject("config"), team, clientAdvancedVersion);
    }

    /** 保真预期和规范化结果同时恢复本次保存版本，不能先写版本后另存内容。 */
    public void assertNormalized(Object normalized, boolean team) {
        ObjectNode incoming = StrictAiJson.parseObject(fingerprint, 2 * 1024 * 1024, 20).withObject("config");
        AdvancedPortfolioVersionSupport.extract(incoming, clientAdvancedVersion);
        AdvancedPortfolioConfigGuard.assertNormalized(
                AdvancedPortfolioVersionSupport.saved(incoming, clientAdvancedVersion),
                AdvancedPortfolioVersionSupport.saved(AdvancedPortfolioVersionSupport.tree(JSON.toJSONString(normalized)),
                        clientAdvancedVersion), team);
    }

    /** 校验高水位和旧映射，在锁内重新读取的实体上应用明确提交的可选字段。 */
    public void apply(PortfolioEntity portfolio) {
        apply(portfolio, null);
    }

    /** 最终事务内解析回执并核对当前清单；省略附件字段不解析旧列。 */
    public void apply(PortfolioEntity portfolio, PortfolioAiReferenceService references) {
        if (referencesPresent) {
            portfolio.setAiReferenceAttachmentsJson(references.resolveForSave(portfolio.getOwnerType(),
                    portfolio.getOwnerId(), referenceAttachments, portfolio.getAiReferenceAttachmentsJson(), referenceNextNo));
        }
        if (contextPresent) {
            validateContext(context, portfolio.getAiContextJson());
            portfolio.setAiContextJson(context.toJSONString());
        }
        if (promptPresent) {
            portfolio.setAiPrompt(prompt);
        }
    }

    /** 高级来源号只能保留既有映射或使用不低于旧高水位的新号。 */
    public static void validateContext(JSONObject context, String previousJson) {
        if (context == null || !context.keySet().equals(CONTEXT_KEYS)
                || context.toJSONString().getBytes(StandardCharsets.UTF_8).length > CONTEXT_MAX_BYTES
                || integer(context.get("version")) != CONTEXT_VERSION) {
            throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
        }
        long next = integer(context.get("nextSourceNo"));
        if (next < 1 || next > Integer.MAX_VALUE) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        Map<Long,String> old = new HashMap<>();
        long oldNext = previousWatermark(previousJson, old);
        if (next < oldNext || !(context.get("sources") instanceof List<?> sources)) {
            throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
        }
        Set<Long> numbers = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (Object value : sources) {
            if (!(value instanceof JSONObject source) || !source.keySet().equals(SOURCE_KEYS)
                    || !(source.get("kind") instanceof String kind) || !SOURCE_KINDS.contains(kind)
                    || !(source.get("sourceId") instanceof String id) || !isSourceId(id)) {
                throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
            }
            long no = integer(source.get("sourceNo"));
            String key = identity(source);
            if (no < 1 || no >= next || !numbers.add(no) || !identities.add(key)
                    || no < oldNext && !key.equals(old.get(no))) {
                throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
            }
        }
    }

    /** 历史缺失字段按空列表和初始高水位恢复，已有映射及编号只保留、不回收。 */
    private static long previousWatermark(String previousJson, Map<Long,String> old) {
        if (previousJson == null || previousJson.isBlank()) { return 1; }
        JSONObject previous;
        try { previous = JSON.parseObject(previousJson); }
        catch (JSONException exception) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        if (previous == null) { return 1; }
        long next = previous.get("nextSourceNo") == null ? 1 : integer(previous.get("nextSourceNo"));
        if (next < 1 || next > Integer.MAX_VALUE) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        Object values = previous.get("sources");
        if (values == null) { return next; }
        if (!(values instanceof List<?> sources)) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        for (Object value : sources) {
            if (!(value instanceof JSONObject source)
                    || !(source.get("kind") instanceof String kind) || !SOURCE_KINDS.contains(kind)
                    || !(source.get("sourceId") instanceof String id) || !isSourceId(id)) {
                throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
            }
            long no = integer(source.get("sourceNo"));
            if (no < 1 || no >= Integer.MAX_VALUE || old.putIfAbsent(no, identity(source)) != null) {
                throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT);
            }
            next = Math.max(next, no + 1);
        }
        return next;
    }

    /** 仅协议整数类型可用作来源号。 */
    private static long integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) { throw new BusinessException(AdvancedPortfolioMessage.INVALID_CONTEXT); }
        return ((Number) value).longValue();
    }

    /** 来源身份使用数据库正 Long 范围，不能用未引用的上下文条目保存溢出号码。 */
    private static boolean isSourceId(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) { return false; }
        try { return Long.parseLong(value) > 0; }
        catch (NumberFormatException exception) { return false; }
    }

    /** 生成无歧义来源身份。 */
    private static String identity(JSONObject source) {
        return source.getString("kind") + ":" + source.getString("sourceId");
    }
}
