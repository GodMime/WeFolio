package com.jxc.wefolio.common;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioTextBlockTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTextMessage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 三类文字组件共享的可选行高规则与旧客户端字段保留。 */
public final class PortfolioTextLineHeightSupport {
    /** 字号倍数配置键，不含长度单位。 */
    public static final String LINE_HEIGHT = "lineHeight";
    /** 结构化区块类型键。 */
    private static final String TYPE = "type";
    /** 稳定区块标识键。 */
    private static final String BLOCK_KEY = "blockKey";
    /** 文字区块集合键。 */
    private static final String BLOCKS = "blocks";
    /** 网格单元格集合键。 */
    private static final String CELLS = "cells";
    /** 行高倍数下限。 */
    private static final BigDecimal MIN_LINE_HEIGHT = new BigDecimal("0.5");
    /** 行高倍数上限。 */
    private static final BigDecimal MAX_LINE_HEIGHT = new BigDecimal("3.0");

    /** 纯规则类不允许实例化。 */
    private PortfolioTextLineHeightSupport() { }

    /** 仅规范化显式配置，缺失字段维持旧客户端各组件的样式行高。 */
    public static void normalizeOptional(Map<?, ?> source, Map<String, Object> target) {
        if (source.containsKey(LINE_HEIGHT)) { target.put(LINE_HEIGHT, readOptional(source)); }
    }

    /** 精确读取数值型一位小数倍数；省略返回空，显式空值、字符串和非法数值拒绝。 */
    public static BigDecimal readOptional(Map<?, ?> source) {
        if (!source.containsKey(LINE_HEIGHT)) { return null; }
        Object raw = source.get(LINE_HEIGHT);
        if (!(raw instanceof Number number)) { throw invalid(); }
        try {
            BigDecimal value = new BigDecimal(number.toString()).setScale(1, RoundingMode.UNNECESSARY);
            if (value.compareTo(MIN_LINE_HEIGHT) < 0 || value.compareTo(MAX_LINE_HEIGHT) > 0) { throw invalid(); }
            return value;
        } catch (NumberFormatException | ArithmeticException exception) { throw invalid(); }
    }

    /** 历史普通文字非法行高不阻断展示，回退原样式行高。 */
    public static BigDecimal forRender(Map<?, ?> source) {
        try { return readOptional(source); }
        catch (BusinessException exception) { return null; }
    }

    /** 同类型组件仅恢复省略的行高，文字区块按稳定标识及类型匹配，不恢复已删除区块。 */
    public static void protectMissing(Map<String, Object> target, Map<String, Object> existing, String type) {
        if (target == null || existing == null) { return; }
        if (PortfolioComponentTypeDict.TEXT_SECTION.getCode().equals(type)) {
            copyMissing(target, existing);
            return;
        }
        Map<Object, Map<String, Object>> savedBlocks = new HashMap<>();
        for (Map<String, Object> block : textNodes(existing, type)) {
            if (PortfolioTextBlockTypeDict.SPACER.getCode().equals(block.get(TYPE))) { continue; }
            if (block.get(BLOCK_KEY) instanceof String key) { savedBlocks.put(key, block); }
        }
        for (Map<String, Object> block : textNodes(target, type)) {
            if (PortfolioTextBlockTypeDict.SPACER.getCode().equals(block.get(TYPE))) { continue; }
            Map<String, Object> saved = savedBlocks.get(block.get(BLOCK_KEY));
            if (saved != null && Objects.equals(block.get(TYPE), saved.get(TYPE))) { copyMissing(block, saved); }
        }
    }

    /** JSON 深拷贝后只恢复本字段的显式空值，确保团队保存仍交给校验器拒绝。 */
    public static void restoreExplicitNulls(Map<String, Object> target, Map<String, Object> source, String type) {
        if (target == null || source == null) { return; }
        List<Map<String, Object>> originals = textNodes(source, type);
        List<Map<String, Object>> copies = textNodes(target, type);
        for (int index = 0; index < Math.min(originals.size(), copies.size()); index++) {
            Map<String, Object> original = originals.get(index);
            if (original.containsKey(LINE_HEIGHT) && original.get(LINE_HEIGHT) == null) {
                copies.get(index).put(LINE_HEIGHT, null);
            }
        }
    }

    /** 仅复制不可变的合法数值，非法存量值不传播到新请求。 */
    private static void copyMissing(Map<String, Object> target, Map<String, Object> existing) {
        if (target.containsKey(LINE_HEIGHT) || !existing.containsKey(LINE_HEIGHT)) { return; }
        BigDecimal value = forRender(existing);
        if (value != null) { target.put(LINE_HEIGHT, value); }
    }

    /** 列举字段可能出现的节点，含留白以便拒绝其非法字段，不访问网格文字片段。 */
    private static List<Map<String, Object>> textNodes(Map<String, Object> config, String type) {
        if (PortfolioComponentTypeDict.TEXT_SECTION.getCode().equals(type)) { return List.of(config); }
        if (PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION.getCode().equals(type)) {
            return maps(config.get(BLOCKS));
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (PortfolioComponentTypeDict.TEXT_GRID.getCode().equals(type)) {
            for (Map<String, Object> cell : maps(config.get(CELLS))) {
                blocks.addAll(maps(cell.get(BLOCKS)));
            }
        }
        return blocks;
    }

    /** 兼容合并仅访问已有对象节点，结构错误留给组件校验器处理。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object source) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (source instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> map) { result.add((Map<String, Object>) map); }
            }
        }
        return result;
    }

    /** 三类组件统一的行高异常提示。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioTextMessage.LINE_HEIGHT_INVALID); }
}
