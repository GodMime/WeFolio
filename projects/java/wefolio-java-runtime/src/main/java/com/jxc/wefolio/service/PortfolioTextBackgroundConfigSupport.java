package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioTextBackgroundTreatmentDict;
import com.jxc.wefolio.dict.PortfolioTextVerticalAlignmentDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTextMessage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 两端文字背景纯配置规范化，不接收展示资源信息。 */
public final class PortfolioTextBackgroundConfigSupport {
    /** 背景开关。 */
    public static final String ENABLED = "backgroundEnabled";
    /** 背景作品标识。 */
    public static final String WORK_ID = "backgroundWorkId";
    /** 团队成员标识。 */
    public static final String MEMBER_ID = "backgroundMemberUserId";
    /** 背景处理。 */
    public static final String TREATMENT = "backgroundTreatment";
    /** 垂直对齐。 */
    public static final String VERTICAL_ALIGNMENT = "verticalAlignment";
    /** 资源展示字段。 */
    public static final String WORK = "backgroundWork";
    /** 资源失效标识。 */
    public static final String INVALID = "backgroundInvalid";
    /** 引用配置路径。 */
    public static final String REFERENCE_PATH = ".config.backgroundWorkId";
    /** 工具类不允许实例化。 */
    private PortfolioTextBackgroundConfigSupport() { }

    /** 严格规范化背景字段；关闭背景后删除资源标识，保留偏好。 */
    public static Map<String,Object> normalize(Map<String,Object> source, boolean team, boolean vertical) {
        Map<String,Object> result = new LinkedHashMap<>();
        Object rawEnabled = source.get(ENABLED);
        if (source.containsKey(ENABLED) && !(rawEnabled instanceof Boolean)) { invalid(); }
        boolean enabled = Boolean.TRUE.equals(rawEnabled);
        result.put(ENABLED, enabled);
        Object treatment = source.containsKey(TREATMENT) ? source.get(TREATMENT)
                : PortfolioTextBackgroundTreatmentDict.GRADIENT.getCode();
        if (PortfolioTextBackgroundTreatmentDict.fromCode(treatment) == null) { invalid(); }
        result.put(TREATMENT, treatment);
        if (vertical) {
            Object alignment = source.containsKey(VERTICAL_ALIGNMENT) ? source.get(VERTICAL_ALIGNMENT)
                    : PortfolioTextVerticalAlignmentDict.CENTER.getCode();
            if (PortfolioTextVerticalAlignmentDict.fromCode(alignment) == null) { invalid(); }
            result.put(VERTICAL_ALIGNMENT, alignment);
        }
        if (enabled) {
            if (source.get(WORK_ID) == null) { throw new BusinessException(PortfolioTextMessage.BACKGROUND_REQUIRED); }
            result.put(WORK_ID, positiveLong(source.get(WORK_ID)));
            if (team) { result.put(MEMBER_ID, positiveLong(source.get(MEMBER_ID))); }
        }
        return result;
    }

    /** 删除资源与展示残留后应用已规范化的背景字段。 */
    public static void apply(Map<String,Object> target, boolean team, boolean vertical) {
        Map<String,Object> normalized = normalize(target, team, vertical);
        target.remove(WORK_ID); target.remove(MEMBER_ID); target.remove(WORK); target.remove(INVALID);
        if (!vertical) { target.remove(VERTICAL_ALIGNMENT); }
        target.putAll(normalized);
    }

    /** 展示时允许背景标识失效，保留开关和视觉偏好，交由资源层标记失效。 */
    public static Map<String,Object> forRender(Map<String,Object> source, boolean team, boolean vertical) {
        Map<String,Object> copy = new LinkedHashMap<>(source);
        copy.put(ENABLED,false);
        Map<String,Object> result = normalize(copy,team,vertical);
        boolean enabled = Boolean.TRUE.equals(source.get(ENABLED));
        result.put(ENABLED,enabled);
        if (enabled) {
            for (String key : team ? new String[]{WORK_ID,MEMBER_ID} : new String[]{WORK_ID}) {
                try { result.put(key,positiveLong(source.get(key))); }
                catch (BusinessException exception) { result.put(key,null); }
            }
        }
        return result;
    }

    /** 精确读取正整数资源标识，不接受字符串、小数或溢出。 */
    public static Long positiveLong(Object raw) {
        if (!(raw instanceof Number)) { invalid(); }
        try {
            long value = new BigDecimal(raw.toString()).longValueExact();
            if (value <= 0) { invalid(); }
            return value;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new BusinessException(PortfolioTextMessage.BACKGROUND_CONFIG_INVALID);
        }
    }

    /** 背景仅允许图片和动图。 */
    public static boolean supportsMedia(String mediaType) {
        return MediaTypeDict.IMAGE.getCode().equals(mediaType) || MediaTypeDict.ANIMATION.getCode().equals(mediaType);
    }

    /** 拒绝非法背景配置。 */
    private static void invalid() { throw new BusinessException(PortfolioTextMessage.BACKGROUND_CONFIG_INVALID); }
}
