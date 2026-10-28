package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import java.util.Map;

/** 高级能力的累计版本定义；请求只能选择已部署的固定版本。 */
public final class AdvancedVersionRegistry {
    /** 请求中的唯一版本来源。 */
    public static final String CLIENT_VERSION = "clientAdvancedVersion";
    /** 历史请求与配置的缺省版本。 */
    public static final int LEGACY_VERSION = 1;
    /** 时间轴及纯文字能力的引入版本。 */
    public static final int TIMELINE_VERSION = 2;
    /** 已部署版本不随默认配置或发布构建号改变。 */
    private static final Map<Integer, Definition> DEFINITIONS = Map.of(
            LEGACY_VERSION, new Definition(LEGACY_VERSION, "ai-portfolio-v1", 14, 10),
            TIMELINE_VERSION, new Definition(TIMELINE_VERSION, "ai-portfolio-v2", 15, 11));

    /** 不可变能力映射；V1 的历史请求不强制补齐当前编辑器修订。 */
    public record Definition(int version, String aiContractVersion,
                             int personalEditorRevision, int teamEditorRevision) { }

    /** 工具类不实例化。 */
    private AdvancedVersionRegistry() { }

    /** 仅接受明确登记的正整数，不推测最高版本或自动回退。 */
    public static Definition resolve(int version) {
        Definition definition = DEFINITIONS.get(version);
        if (definition == null) { throw invalidVersion(); }
        return definition;
    }

    /** 保留旧请求缺省表示；本方法只解释请求，不修改其内容。 */
    public static int requestVersion(JsonNode request) {
        if (request == null || !request.has(CLIENT_VERSION)) { return LEGACY_VERSION; }
        JsonNode value = request.get(CLIENT_VERSION);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) { throw invalidVersion(); }
        return resolve(value.intValue()).version();
    }

    /** 手动高级入口在原始类型检查后使用同一注册表。 */
    public static int requestVersion(JSONObject request) {
        if (!request.containsKey(CLIENT_VERSION)) { return LEGACY_VERSION; }
        Object value = request.get(CLIENT_VERSION);
        if (!(value instanceof Integer number)) { throw invalidVersion(); }
        return resolve(number).version();
    }

    /** 查询参数只接受整数文本，空串、空值字样和小数不作缺省。 */
    public static int queryVersion(String value) {
        if (value == null) { return LEGACY_VERSION; }
        if (!value.matches("[1-9][0-9]*")) { throw invalidVersion(); }
        try { return resolve(Integer.parseInt(value)).version(); }
        catch (NumberFormatException exception) { throw invalidVersion(); }
    }

    /** 新能力还需满足普通编辑器结构门槛，高修订不能反向提高高级版本。 */
    public static void requireEditor(int version, boolean team, Integer revision) {
        Definition definition = resolve(version);
        if (version >= TIMELINE_VERSION && (revision == null
                || revision < (team ? definition.teamEditorRevision() : definition.personalEditorRevision()))) {
            throw new BusinessException(AdvancedPortfolioMessage.INVALID_VERSION_CAPABILITY);
        }
    }

    /** 非法声明使用统一业务错误。 */
    private static BusinessException invalidVersion() {
        return new BusinessException(AdvancedPortfolioMessage.INVALID_VERSION);
    }
}
