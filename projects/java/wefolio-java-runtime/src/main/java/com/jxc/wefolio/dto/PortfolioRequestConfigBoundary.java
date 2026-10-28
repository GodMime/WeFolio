package com.jxc.wefolio.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import java.util.Map;

/** 普通请求在配置 DTO 转换前拒绝高级元数据，其余字段继续沿用原宽松转换规则。 */
public final class PortfolioRequestConfigBoundary {
    /** 高级入口先提取的唯一配置根字段，普通请求不得静默吞掉。 */
    private static final String ADVANCED_VERSION_FIELD = "advancedPortfolioVersion";

    /** 工具类不实例化。 */
    private PortfolioRequestConfigBoundary() { }

    /** 保留空值、直接 DTO 赋值和既有类型转换，仅检查配置根的高级版本字段。 */
    public static <T> T convert(Object value, Class<T> type) {
        if (value == null || type.isInstance(value)) { return type.cast(value); }
        if (value instanceof Map<?, ?> fields) {
            if (fields.containsKey(ADVANCED_VERSION_FIELD)) {
                throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE);
            }
            // JSON.to 会在 LinkedHashMap 转换时省略 null，显式清除字体必须保留到普通 DTO。
            return JSON.parseObject(JSON.toJSONString(value, JSONWriter.Feature.WriteMapNullValue), type);
        }
        // 原对象读取只接受对象及既有空值表示，不能因 Map 转换扩大到数组或字符串内嵌 JSON。
        return JSON.parseObject(JSON.toJSONString(value), type);
    }
}
