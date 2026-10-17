package com.jxc.wefolio.service;

import com.jxc.wefolio.service.portfoliofont.PortfolioFontConfigSupport;
import com.jxc.wefolio.common.PortfolioTextLineHeightSupport;
import com.jxc.wefolio.constant.PortfolioTextTypographyConstants;
import com.jxc.wefolio.dict.PortfolioTextAlignmentDict;
import com.jxc.wefolio.dict.PortfolioTextVerticalAlignmentDict;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.dict.PortfolioTextFontWeightDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 两类作品集共用的网格纯规则：完整覆盖、稳定标识和混合片段样式。 */
public final class PortfolioTextGridConfigNormalizer {
    /** rows 配置键。 */
    public static final String ROWS = "rows";
    /** columns 配置键。 */
    public static final String COLUMNS = "columns";
    /** columnWeights 配置键。 */
    public static final String COLUMN_WEIGHTS = "columnWeights";
    /** rowMinHeightsRpx 配置键。 */
    public static final String ROW_MIN_HEIGHTS_RPX = "rowMinHeightsRpx";
    /** gapRpx 配置键。 */
    public static final String GAP_RPX = "gapRpx";
    /** 网格左右外侧留白配置键，单位 rpx。 */
    public static final String HORIZONTAL_MARGIN_RPX = "horizontalMarginRpx";
    /** 网格上下外侧留白配置键，单位 rpx。 */
    public static final String VERTICAL_MARGIN_RPX = "verticalMarginRpx";
    /** cellPaddingRpx 配置键。 */
    public static final String CELL_PADDING_RPX = "cellPaddingRpx";
    /** cellRadiusRpx 配置键。 */
    public static final String CELL_RADIUS_RPX = "cellRadiusRpx";
    /** cellBorder 配置键。 */
    public static final String CELL_BORDER = "cellBorder";
    /** 格子边框宽度配置键，单位 rpx。 */
    public static final String CELL_BORDER_WIDTH_RPX = "cellBorderWidthRpx";
    /** 格子边框颜色配置键。 */
    public static final String CELL_BORDER_COLOR = "cellBorderColor";
    /** cellBackground 配置键。 */
    public static final String CELL_BACKGROUND = "cellBackground";
    /** cells 配置键。 */
    public static final String CELLS = "cells";
    /** cellKey 配置键。 */
    public static final String CELL_KEY = "cellKey";
    /** row 配置键。 */
    public static final String ROW = "row";
    /** column 配置键。 */
    public static final String COLUMN = "column";
    /** rowSpan 配置键。 */
    public static final String ROW_SPAN = "rowSpan";
    /** columnSpan 配置键。 */
    public static final String COLUMN_SPAN = "columnSpan";
    /** verticalAlignment 配置键。 */
    public static final String VERTICAL_ALIGNMENT = "verticalAlignment";
    /** blocks 配置键。 */
    public static final String BLOCKS = "blocks";
    /** blockKey 配置键。 */
    public static final String BLOCK_KEY = "blockKey";
    /** alignment 配置键。 */
    public static final String ALIGNMENT = "alignment";
    /** marginTopRpx 配置键。 */
    public static final String MARGIN_TOP_RPX = "marginTopRpx";
    /** marginBottomRpx 配置键。 */
    public static final String MARGIN_BOTTOM_RPX = "marginBottomRpx";
    /** runs 配置键。 */
    public static final String RUNS = "runs";
    /** runKey 配置键。 */
    public static final String RUN_KEY = "runKey";
    /** text 配置键。 */
    public static final String TEXT = "text";
    /** fontFamily 配置键。 */
    public static final String FONT_FAMILY = "fontFamily";
    /** fontSizeRpx 配置键。 */
    public static final String FONT_SIZE_RPX = "fontSizeRpx";
    /** fontWeight 配置键。 */
    public static final String FONT_WEIGHT = "fontWeight";
    /** color 配置键。 */
    public static final String COLOR = "color";
    /** 自动主题色。 */
    private static final String AUTO = "AUTO";
    /** 稳定标识格式，整个组件内不得重复。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** 六位自定义颜色。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 最大行数。 */
    private static final int MAX_ROWS = 8;
    /** 最大列数。 */
    private static final int MAX_COLUMNS = 5;
    /** 每格最多段数、每段最多片段数。 */
    private static final int MAX_PARTS = 8;
    /** 组件文字总码点数。 */
    private static final int MAX_TEXT = 2000;
    /** 默认行高，首次和新增行一致。 */
    private static final int DEFAULT_ROW_HEIGHT = 180;
    /** 外侧留白上限，单位 rpx。 */
    private static final int MAX_MARGIN_RPX = 96;
    /** 历史细边框宽度与可调边框宽度上限。 */
    private static final int DEFAULT_BORDER_WIDTH_RPX = 1, MAX_BORDER_WIDTH_RPX = 12;
    /** 默认键用途前缀。 */
    private static final String CELL_PREFIX = "cell_", BLOCK_PREFIX = "block_", RUN_PREFIX = "run_";
    /** 不实例化工具类。 */
    private PortfolioTextGridConfigNormalizer() { }

    /** 返回只包含受支持字段的独立深拷贝，保留作者的空格与换行。 */
    public static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> safe = source == null || source.isEmpty() ? defaultGrid() : source;
        int rows = integer(safe, ROWS, null, 1, MAX_ROWS);
        int columns = integer(safe, COLUMNS, null, 1, MAX_COLUMNS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ROWS, rows); result.put(COLUMNS, columns);
        result.put(COLUMN_WEIGHTS, integerArray(safe.get(COLUMN_WEIGHTS), columns, 1, 4));
        result.put(ROW_MIN_HEIGHTS_RPX, integerArray(safe.get(ROW_MIN_HEIGHTS_RPX), rows, 80, 600));
        result.put(GAP_RPX, integer(safe, GAP_RPX, 16, 0, 48));
        result.put(HORIZONTAL_MARGIN_RPX, integer(safe, HORIZONTAL_MARGIN_RPX, 0, 0, MAX_MARGIN_RPX));
        result.put(VERTICAL_MARGIN_RPX, integer(safe, VERTICAL_MARGIN_RPX, 0, 0, MAX_MARGIN_RPX));
        result.put(CELL_PADDING_RPX, integer(safe, CELL_PADDING_RPX, 24, 0, 48));
        result.put(CELL_RADIUS_RPX, integer(safe, CELL_RADIUS_RPX, 24, 0, 48));
        Object border = safe.getOrDefault(CELL_BORDER, false);
        if (!(border instanceof Boolean)) { throw invalid(); }
        result.put(CELL_BORDER, border);
        result.put(CELL_BORDER_WIDTH_RPX,
                integer(safe, CELL_BORDER_WIDTH_RPX, DEFAULT_BORDER_WIDTH_RPX, DEFAULT_BORDER_WIDTH_RPX, MAX_BORDER_WIDTH_RPX));
        // 缺省自动色保持历史深浅主题边框；关闭开关仍保存作者的宽度和颜色。
        result.put(CELL_BORDER_COLOR, color(safe.getOrDefault(CELL_BORDER_COLOR, AUTO)));
        result.put(CELL_BACKGROUND, color(safe.getOrDefault(CELL_BACKGROUND, AUTO)));
        List<?> cells = list(safe.get(CELLS), MAX_ROWS * MAX_COLUMNS);
        boolean[][] covered = new boolean[rows][columns];
        Set<String> keys = new HashSet<>();
        List<Map<String, Object>> normalizedCells = new ArrayList<>();
        int count = 0;
        for (Object raw : cells) {
            Map<?, ?> cell = object(raw);
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put(CELL_KEY, key(cell.get(CELL_KEY), keys));
            int row = integer(cell, ROW, null, 0, rows - 1), column = integer(cell, COLUMN, null, 0, columns - 1);
            int rowSpan = integer(cell, ROW_SPAN, null, 1, rows - row);
            int columnSpan = integer(cell, COLUMN_SPAN, null, 1, columns - column);
            normalized.put(ROW, row); normalized.put(COLUMN, column);
            normalized.put(ROW_SPAN, rowSpan); normalized.put(COLUMN_SPAN, columnSpan);
            for (int r = row; r < row + rowSpan; r++) {
                for (int c = column; c < column + columnSpan; c++) {
                    if (covered[r][c]) { throw invalid(); }
                    covered[r][c] = true;
                }
            }
            Object vertical = fallback(cell, VERTICAL_ALIGNMENT, PortfolioTextVerticalAlignmentDict.CENTER.getCode());
            if (PortfolioTextVerticalAlignmentDict.fromCode(vertical) == null) { throw invalid(); }
            normalized.put(VERTICAL_ALIGNMENT, vertical);
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Object rawBlock : list(cell.get(BLOCKS), MAX_PARTS)) {
                Map<?, ?> block = object(rawBlock);
                Map<String, Object> normalizedBlock = new LinkedHashMap<>();
                normalizedBlock.put(BLOCK_KEY, key(block.get(BLOCK_KEY), keys));
                Object alignment = fallback(block, ALIGNMENT, PortfolioTextAlignmentDict.CENTER.getCode());
                if (PortfolioTextAlignmentDict.fromCode(alignment) == null) { throw invalid(); }
                normalizedBlock.put(ALIGNMENT, alignment);
                normalizedBlock.put(MARGIN_TOP_RPX, integer(block, MARGIN_TOP_RPX, 0, 0, 128));
                normalizedBlock.put(MARGIN_BOTTOM_RPX, integer(block, MARGIN_BOTTOM_RPX, 12, 0, 128));
                PortfolioTextLineHeightSupport.normalizeOptional(block, normalizedBlock);
                List<Map<String, Object>> runs = new ArrayList<>();
                for (Object rawRun : list(block.get(RUNS), MAX_PARTS)) {
                    Map<?, ?> run = object(rawRun);
                    Map<String, Object> normalizedRun = new LinkedHashMap<>();
                    normalizedRun.put(RUN_KEY, key(run.get(RUN_KEY), keys));
                    if (!(run.get(TEXT) instanceof String text)) { throw invalid(); }
                    count += text.codePointCount(0, text.length());
                    if (count > MAX_TEXT) { throw invalid(); }
                    normalizedRun.put(TEXT, text);
                    Object font = fallback(run, FONT_FAMILY, PortfolioTextFontFamilyDict.SYSTEM.getCode());
                    if (!(font instanceof String fontName) || PortfolioTextFontFamilyDict.fromCode(fontName) == null) { throw invalid(); }
                    normalizedRun.put(FONT_FAMILY, font);
                    if (run.get(PortfolioFontConfigSupport.FONT_ID) instanceof String fontId) {
                        normalizedRun.put(PortfolioFontConfigSupport.FONT_ID, fontId);
                    }
                    normalizedRun.put(FONT_SIZE_RPX, integer(run, FONT_SIZE_RPX, 28,
                            PortfolioTextTypographyConstants.FONT_SIZE_MIN_RPX,
                            PortfolioTextTypographyConstants.FONT_SIZE_MAX_RPX));
                    Object weight = fallback(run, FONT_WEIGHT, PortfolioTextFontWeightDict.NORMAL.getCode());
                    if (PortfolioTextFontWeightDict.fromCode(weight) == null) { throw invalid(); }
                    normalizedRun.put(FONT_WEIGHT, weight);
                    normalizedRun.put(COLOR, color(fallback(run, COLOR, AUTO)));
                    runs.add(normalizedRun);
                }
                normalizedBlock.put(RUNS, runs); blocks.add(normalizedBlock);
            }
            normalized.put(BLOCKS, blocks); normalizedCells.add(normalized);
        }
        for (boolean[] row : covered) { for (boolean cell : row) { if (!cell) { throw invalid(); } } }
        normalizedCells.sort(Comparator.comparingInt((Map<String, Object> cell) -> (Integer) cell.get(ROW))
                .thenComparingInt(cell -> (Integer) cell.get(COLUMN)));
        result.put(CELLS, normalizedCells);
        return result;
    }

    /** 启用发布要求至少一处非空文字，空白草稿仍可保存。 */
    public static void validateForPublish(Map<String, Object> source) {
        Map<String, Object> normalized = normalize(source);
        for (Object cell : (List<?>) normalized.get(CELLS)) {
            for (Object block : (List<?>) object(cell).get(BLOCKS)) {
                for (Object run : (List<?>) object(block).get(RUNS)) {
                    if (!((String) object(run).get(TEXT)).codePoints()
                            .allMatch(PortfolioTextGridConfigNormalizer::isEditorWhitespace)) { return; }
                }
            }
        }
        throw new BusinessException(PortfolioMessage.TEXT_GRID_CONTENT_REQUIRED);
    }

    /** 与 JavaScript trim 的空白集合一致；仅判断发布内容，不改写用户原文。 */
    private static boolean isEditorWhitespace(int codePoint) {
        return Character.isSpaceChar(codePoint) || codePoint >= 0x0009 && codePoint <= 0x000D
                || codePoint == 0xFEFF;
    }

    /** 构建空白二乘二网格，稳定键在后续保存和拆分时保留。 */
    private static Map<String, Object> defaultGrid() {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int row = 0; row < 2; row++) { for (int column = 0; column < 2; column++) {
            String suffix = row + "_" + column;
            cells.add(Map.of(CELL_KEY, CELL_PREFIX + suffix, ROW, row, COLUMN, column, ROW_SPAN, 1, COLUMN_SPAN, 1,
                    BLOCKS, List.of(Map.of(BLOCK_KEY, BLOCK_PREFIX + suffix,
                            RUNS, List.of(Map.of(RUN_KEY, RUN_PREFIX + suffix, TEXT, ""))))));
        } }
        return Map.of(ROWS, 2, COLUMNS, 2, COLUMN_WEIGHTS, List.of(1, 1),
                ROW_MIN_HEIGHTS_RPX, List.of(DEFAULT_ROW_HEIGHT, DEFAULT_ROW_HEIGHT), CELLS, cells);
    }
    /** 空值保留为非法显式输入，只有缺省才赋默认。 */
    private static Object fallback(Map<?, ?> source, String key, Object fallback) {
        return source.containsKey(key) ? source.get(key) : fallback;
    }
    /** 精确解析有限整数，不允许字符串或小数截断。 */
    private static int integer(Map<?, ?> source, String key, Integer fallback, int min, int max) {
        Object raw = source.containsKey(key) ? source.get(key) : fallback;
        if (!(raw instanceof Number number)) { throw invalid(); }
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value < min || value > max) { throw invalid(); }
            return value;
        } catch (NumberFormatException | ArithmeticException exception) { throw invalid(); }
    }
    /** 数组长度与行列一一对应。 */
    private static List<Integer> integerArray(Object raw, int length, int min, int max) {
        if (!(raw instanceof List<?> values) || values.size() != length) { throw invalid(); }
        List<Integer> result = new ArrayList<>();
        for (Object value : values) {
            if (value == null) { throw invalid(); }
            result.add(integer(Map.of(TEXT, value), TEXT, null, min, max));
        }
        return result;
    }
    /** 严格对象解析。 */
    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) { throw invalid(); } return map;
    }
    /** 结构数组至少包含一个空占位项。 */
    private static List<?> list(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > limit) { throw invalid(); } return list;
    }
    /** 稳定键全组件唯一。 */
    private static String key(Object value, Set<String> keys) {
        if (!(value instanceof String text) || !KEY_PATTERN.matcher(text).matches() || !keys.add(text)) { throw invalid(); }
        return text;
    }
    /** 自定义色统一大写六位，自动色独立保留。 */
    private static String color(Object value) {
        if (!(value instanceof String text) || (!AUTO.equals(text) && !COLOR_PATTERN.matcher(text).matches())) { throw invalid(); }
        return text.toUpperCase(Locale.ROOT);
    }
    /** 统一受控校验异常。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioMessage.TEXT_GRID_INVALID); }
}
