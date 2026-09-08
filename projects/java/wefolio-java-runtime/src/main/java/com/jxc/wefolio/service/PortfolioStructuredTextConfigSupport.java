package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioTextBlockTypeDict;
import com.jxc.wefolio.dict.PortfolioTextFontWeightDict;
import com.jxc.wefolio.dict.PortfolioTextAlignmentDict;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTextMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 个人与团队结构化文字的共享纯规则，严格保留独立样式与作者内容。 */
public final class PortfolioStructuredTextConfigSupport {
    /** 区块集合键。 */
    public static final String BLOCKS = "blocks";
    /** 稳定区块标识键。 */
    private static final String KEY = "blockKey";
    /** 区块类型键。 */
    private static final String TYPE = "type";
    /** 普通内容键。 */
    private static final String CONTENT = "content";
    /** 列表内容键。 */
    private static final String ITEMS = "items";
    /** 留白高度键。 */
    private static final String HEIGHT = "heightRpx";
    /** 上间距键。 */
    private static final String TOP = "marginTopRpx";
    /** 下间距键。 */
    private static final String BOTTOM = "marginBottomRpx";
    /** 字体键。 */
    private static final String FONT = "fontFamily";
    /** 字号键。 */
    private static final String SIZE = "fontSizeRpx";
    /** 字重键。 */
    private static final String WEIGHT = "fontWeight";
    /** 颜色键。 */
    private static final String COLOR = "color";
    /** 水平对齐键。 */
    private static final String ALIGNMENT = "alignment";
    /** 自动颜色语义。 */
    private static final String AUTO = "AUTO";
    /** 六位颜色格式。 */
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 最大区块数。 */
    private static final int MAX_BLOCKS = 20;
    /** 最大列表条数。 */
    private static final int MAX_ITEMS = 10;
    /** 最大文字码点数。 */
    private static final int MAX_CONTENT = 2000;
    /** 工具类不允许实例化。 */
    private PortfolioStructuredTextConfigSupport() { }

    /** 规范化有序区块及背景，所有非法显式适用字段均拒绝。 */
    public static Map<String,Object> normalize(Map<String,Object> source, boolean team) {
        if (source == null || !(source.get(BLOCKS) instanceof List<?> blocks)
                || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new BusinessException(PortfolioTextMessage.BLOCK_COUNT_INVALID);
        }
        List<Map<String,Object>> normalizedBlocks = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int count = 0;
        boolean hasContent = false;
        for (Object raw : blocks) {
            if (!(raw instanceof Map<?,?> block)) { throw invalid(); }
            String key = text(block.get(KEY));
            if (!keys.add(key)) { throw invalid(); }
            PortfolioTextBlockTypeDict type = PortfolioTextBlockTypeDict.fromCode(block.get(TYPE));
            if (type == null) { throw invalid(); }
            Map<String,Object> result = new LinkedHashMap<>();
            result.put(KEY,key); result.put(TYPE,type.getCode());
            result.put(TOP,integer(block,TOP,defaultTop(type),0,128,4));
            result.put(BOTTOM,integer(block,BOTTOM,type == PortfolioTextBlockTypeDict.HINT
                    || type == PortfolioTextBlockTypeDict.SPACER ? 0 : 16,0,128,4));
            if (type == PortfolioTextBlockTypeDict.SPACER) {
                result.put(HEIGHT,integer(block,HEIGHT,32,0,128,4));
            } else {
                hasContent = true;
                if (type == PortfolioTextBlockTypeDict.LIST) {
                    if (!(block.get(ITEMS) instanceof List<?> items) || items.isEmpty() || items.size() > MAX_ITEMS) {
                        throw invalid();
                    }
                    List<String> normalizedItems = new ArrayList<>();
                    for (Object item : items) {
                        String value = text(item); count += value.codePointCount(0,value.length());
                        normalizedItems.add(value);
                    }
                    result.put(ITEMS,normalizedItems);
                } else {
                    String content = text(block.get(CONTENT));
                    count += content.codePointCount(0,content.length()); result.put(CONTENT,content);
                }
                Object font = value(block,FONT,PortfolioTextFontFamilyDict.SYSTEM.getCode());
                if (!(font instanceof String name) || PortfolioTextFontFamilyDict.fromCode(name) == null) { throw invalid(); }
                result.put(FONT,font);
                result.put(SIZE,integer(block,SIZE,defaultSize(type),20,96,1));
                Object weight = value(block,WEIGHT,(type == PortfolioTextBlockTypeDict.TITLE
                        ? PortfolioTextFontWeightDict.BOLD : PortfolioTextFontWeightDict.NORMAL).getCode());
                if (PortfolioTextFontWeightDict.fromCode(weight) == null) { throw invalid(); }
                result.put(WEIGHT,weight);
                Object color = value(block,COLOR,AUTO);
                if (!(color instanceof String colorText) || (!AUTO.equals(colorText) && !HEX_COLOR.matcher(colorText).matches())) {
                    throw invalid();
                }
                result.put(COLOR,color);
                Object alignment = value(block,ALIGNMENT,PortfolioTextAlignmentDict.LEFT.getCode());
                if (PortfolioTextAlignmentDict.fromCode(alignment) == null) { throw invalid(); }
                result.put(ALIGNMENT,alignment);
            }
            normalizedBlocks.add(result);
        }
        if (!hasContent) { throw new BusinessException(PortfolioTextMessage.BLOCK_COUNT_INVALID); }
        if (count > MAX_CONTENT) { throw new BusinessException(PortfolioTextMessage.CONTENT_TOO_LONG); }
        Map<String,Object> result = new LinkedHashMap<>(PortfolioTextBackgroundConfigSupport.normalize(source,team,false));
        result.put(BLOCKS,normalizedBlocks);
        return result;
    }

    /** 展示校验文字结构，背景资源失效不能阻止展示正文。 */
    public static Map<String,Object> forRender(Map<String,Object> source, boolean team) {
        Map<String,Object> copy = new LinkedHashMap<>(source);
        copy.put(PortfolioTextBackgroundConfigSupport.ENABLED,false);
        Map<String,Object> result = normalize(copy,team);
        result.putAll(PortfolioTextBackgroundConfigSupport.forRender(source,team,false));
        return result;
    }

    /** 缺省使用预设，显式空值不回填。 */
    private static Object value(Map<?,?> source,String key,Object fallback) { return source.containsKey(key) ? source.get(key) : fallback; }
    /** 原样保留非空白字符串。 */
    private static String text(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) { throw invalid(); }
        return value;
    }
    /** 精确整数范围与步进校验。 */
    private static int integer(Map<?,?> source,String key,int fallback,int min,int max,int step) {
        Object raw = value(source,key,fallback);
        if (!(raw instanceof Number)) { throw invalid(); }
        try {
            int number = new BigDecimal(raw.toString()).intValueExact();
            if (number < min || number > max || number % step != 0) { throw invalid(); }
            return number;
        } catch (NumberFormatException | ArithmeticException exception) { throw invalid(); }
    }
    /** 区块默认上间距。 */
    private static int defaultTop(PortfolioTextBlockTypeDict type) {
        return switch(type) { case TITLE -> 24; case HINT -> 32; default -> 0; };
    }
    /** 区块默认字号。 */
    private static int defaultSize(PortfolioTextBlockTypeDict type) {
        return switch(type) { case TITLE -> 44; case PARAGRAPH -> 28; case LIST -> 26; default -> 24; };
    }
    /** 生成结构化字段校验异常。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioTextMessage.CONFIG_INVALID); }
}
