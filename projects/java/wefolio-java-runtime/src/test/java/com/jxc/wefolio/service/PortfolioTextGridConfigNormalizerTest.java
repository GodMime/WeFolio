package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自由网格黄金样例、完整覆盖、混合片段与 Unicode 边界。 */
class PortfolioTextGridConfigNormalizerTest {
    /** 新增留白缺省为零，两个方向独立保存并接受上下限，不改写来源配置。 */
    @Test void normalizesOuterMarginsWithoutChangingGridOrSource() {
        Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(rectangularGrid(8, 5));
        assertThat(grid).containsEntry("horizontalMarginRpx", 0).containsEntry("verticalMarginRpx", 0);
        grid.put("horizontalMarginRpx", 96); grid.put("verticalMarginRpx", 24);
        Map<String, Object> normalized = PortfolioTextGridConfigNormalizer.normalize(grid);
        assertThat(normalized).containsEntry("horizontalMarginRpx", 96).containsEntry("verticalMarginRpx", 24)
                .containsEntry("rows", 8).containsEntry("columns", 5);
        assertThat(cells(normalized)).hasSize(40);
        normalized.put("horizontalMarginRpx", 0);
        assertThat(grid).containsEntry("horizontalMarginRpx", 96);
        grid.put("horizontalMarginRpx", 0); grid.put("verticalMarginRpx", 96);
        assertThat(PortfolioTextGridConfigNormalizer.normalize(grid)).containsEntry("horizontalMarginRpx", 0)
                .containsEntry("verticalMarginRpx", 96);
    }

    /** 外侧留白严格校验范围、整数类型和显式空值。 */
    @Test void rejectsInvalidOuterMargins() {
        for (String field : List.of("horizontalMarginRpx", "verticalMarginRpx")) {
            for (Object value : new Object[]{-1, 97, 1.5, "2", false, Double.NaN, Double.POSITIVE_INFINITY, null}) {
                Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
                grid.put(field, value);
                assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid))
                        .as("非法网格 %s = %s", field, value).isInstanceOf(BusinessException.class);
            }
        }
    }

    /** 八行五列的四十格均可保存，第八行文字可发布，整列合并允许跨八行。 */
    @Test void acceptsEightRowsWithFortyCellsAndMergedColumn() {
        Map<String, Object> grid = rectangularGrid(8, 5);
        Map<String, Object> normalized = PortfolioTextGridConfigNormalizer.normalize(grid);
        assertThat(normalized).containsEntry("rows", 8).containsEntry("columns", 5);
        assertThat(cells(normalized)).hasSize(40);
        assertThat(cells(normalized).getLast()).containsEntry("row", 7).containsEntry("column", 4);
        PortfolioTextGridConfigNormalizer.validateForPublish(normalized);
        cells(grid).getFirst().put("rowSpan", 8);
        cells(grid).removeIf(cell -> (Integer) cell.get("column") == 0 && (Integer) cell.get("row") > 0);
        assertThat(cells(PortfolioTextGridConfigNormalizer.normalize(grid))).hasSize(33);
    }

    /** 行数最多八行，列数仍最多五列，九行和六列使用原有受控异常拒绝。 */
    @Test void rejectsNineRowsAndSixColumns() {
        for (Map<String, Object> grid : List.of(rectangularGrid(9, 5), rectangularGrid(8, 6))) {
            assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid))
                    .isInstanceOf(BusinessException.class);
        }
    }

    /** Java 与两个小程序分包直接读取同一完整配置样例，结构和发布空白边界不能各自漂移。 */
    @Test void matchesSharedGoldenFixture() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        Path fixture = null;
        while (directory != null) {
            Path candidate = directory.resolve("tests/fixtures/portfolio-text-grid-golden.json");
            if (Files.isRegularFile(candidate)) { fixture = candidate; break; }
            directory = directory.getParent();
        }
        assertThat(fixture).as("仓库共用文字网格黄金样例").isNotNull();
        var cases = JSON.parseObject(Files.readString(fixture)).getJSONArray("cases");
        assertThat(cases).isNotEmpty();
        for (int index = 0; index < cases.size(); index++) {
            var item = cases.getJSONObject(index);
            Map<String, Object> grid = item.getJSONObject("grid");
            String name = item.getString("name");
            if (!item.getBooleanValue("valid")) {
                assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid))
                        .as(name).isInstanceOf(BusinessException.class);
                continue;
            }
            Map<String, Object> normalized = PortfolioTextGridConfigNormalizer.normalize(grid);
            if (item.getBooleanValue("publishable")) { PortfolioTextGridConfigNormalizer.validateForPublish(normalized); }
            else {
                assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.validateForPublish(normalized))
                        .as(name).isInstanceOf(BusinessException.class);
            }
        }
    }
    /** 空白草稿规范化完整，发布仍需作者文字。 */
    @Test void normalizesBlankDraftWithExplicitDefaults() {
        Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
        assertThat(grid).containsEntry("rows", 2).containsEntry("columns", 2)
                .containsEntry("rowMinHeightsRpx", List.of(180, 180))
                .containsEntry("cellBorderWidthRpx", 1).containsEntry("cellBorderColor", "AUTO");
        assertThat(cells(grid)).hasSize(4);
        assertThat(run(grid)).containsEntry("fontSizeRpx", 28).containsEntry("fontWeight", "NORMAL");
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.validateForPublish(grid)).isInstanceOf(BusinessException.class);
    }
    /** 文字片段支持扩大后的字号边界和相邻整数，越界、类型错误与小数仍拒绝。 */
    @Test void acceptsExpandedIntegerFontSizesAndRejectsInvalidValues() {
        for (int size : List.of(10,11,19,20,48,49,95,96)) {
            Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
            run(grid).put("fontSizeRpx",size);
            run(grid).put("text","文字");
            Map<String, Object> normalized = PortfolioTextGridConfigNormalizer.normalize(grid);
            assertThat(run(normalized)).containsEntry("fontSizeRpx",size);
            PortfolioTextGridConfigNormalizer.validateForPublish(normalized);
        }
        for (Object size : new Object[]{9,97,10.5,"10",null,false,Double.NaN,Double.POSITIVE_INFINITY}) {
            Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
            run(grid).put("fontSizeRpx",size);
            assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid))
                    .as("非法文字片段字号 %s",size).isInstanceOf(BusinessException.class);
        }
    }

    /** 关闭边框仍保留宽度和自定义色，历史配置缺省保持原主题色与一 rpx。 */
    @Test void normalizesBorderOptionsWithoutDependingOnToggle() {
        for (boolean enabled : List.of(false, true)) {
            Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
            grid.put("cellBorder", enabled);
            grid.put("cellBorderWidthRpx", 12);
            grid.put("cellBorderColor", "#a1b2c3");
            assertThat(PortfolioTextGridConfigNormalizer.normalize(grid)).containsEntry("cellBorder", enabled)
                    .containsEntry("cellBorderWidthRpx", 12).containsEntry("cellBorderColor", "#A1B2C3");
            assertThat(grid).containsEntry("cellBorderColor", "#a1b2c3");
            grid.remove("cellBorderWidthRpx"); grid.remove("cellBorderColor");
            assertThat(PortfolioTextGridConfigNormalizer.normalize(grid))
                    .containsEntry("cellBorderWidthRpx", 1).containsEntry("cellBorderColor", "AUTO");
        }
    }

    /** 宽度不允许越界、类型转换或显式空值，颜色仅允许六位 HEX 和兼容自动色。 */
    @Test void rejectsInvalidBorderValuesEvenWhenBorderIsDisabled() {
        for (Object width : new Object[]{0, 13, -1, 1.5, "2", null, false, Double.NaN}) {
            Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
            grid.put("cellBorderWidthRpx", width);
            assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid))
                    .as("非法边框宽度 %s", width).isInstanceOf(BusinessException.class);
        }
        for (Object color : new Object[]{"", "#FFF", "#12345678", "#GGGGGG", "black", null, 0, false}) {
            Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
            grid.put("cellBorderColor", color);
            assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid))
                    .as("非法边框颜色 %s", color).isInstanceOf(BusinessException.class);
        }
    }
    /** 同格保留空格、换行、emoji 和所有独立样式。 */
    @Test void preservesMixedRunsAndCountsCodePoints() {
        Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
        run(grid).put("text", "😀".repeat(1997) + " a\n");
        run(grid).put("fontSizeRpx", 64); run(grid).put("fontWeight", "BOLD");
        run(grid).put("fontFamily", "WECHAT_SANS_SS"); run(grid).put("color", "#aabbcc");
        Map<String, Object> saved = PortfolioTextGridConfigNormalizer.normalize(grid);
        assertThat(run(saved)).containsEntry("text", run(grid).get("text")).containsEntry("color", "#AABBCC");
        PortfolioTextGridConfigNormalizer.validateForPublish(saved);
        run(grid).put("text", "😀".repeat(2001));
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
    }
    /** 左侧跨两行与右侧上下两格完整覆盖。 */
    @Test void acceptsThreeCellMergedExampleAndRejectsOverlapOrGap() {
        Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
        cells(grid).getFirst().put("rowSpan", 2); cells(grid).remove(2);
        assertThat(cells(PortfolioTextGridConfigNormalizer.normalize(grid))).hasSize(3);
        cells(grid).getFirst().put("columnSpan", 2);
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
        cells(grid).getFirst().put("columnSpan", 1); cells(grid).getFirst().put("rowSpan", 1);
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
    }
    /** 稳定键全局唯一，行列数组、跨度和整数值严格检查。 */
    @Test void rejectsMalformedStructureAndKeys() {
        for (String field : List.of("rows", "columns", "gapRpx", "cellPaddingRpx", "cellRadiusRpx")) {
            Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of()); grid.put(field, 1.5);
            assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
        }
        Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
        run(grid).put("runKey", cells(grid).getFirst().get("cellKey"));
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
        run(grid).put("runKey", "bad key");
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
    }
    /** 每格段落及每段片段上限同时生效。 */
    @Test void rejectsNineBlocksAndNineRuns() {
        Map<String, Object> grid = PortfolioTextGridConfigNormalizer.normalize(Map.of());
        Map<String, Object> block = blocks(grid).getFirst();
        for (int i = 1; i < 9; i++) {
            Map<String, Object> next = JSON.parseObject(JSON.toJSONString(block));
            next.put("blockKey", "extra_block_" + i);
            ((Map<String, Object>) ((List<?>) next.get("runs")).getFirst()).put("runKey", "extra_run_" + i);
            blocks(grid).add(next);
        }
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(grid)).isInstanceOf(BusinessException.class);
        Map<String, Object> second = PortfolioTextGridConfigNormalizer.normalize(Map.of());
        List<Map<String, Object>> runs = (List<Map<String, Object>>) blocks(second).getFirst().get("runs");
        for (int i = 1; i < 9; i++) {
            Map<String, Object> next = JSON.parseObject(JSON.toJSONString(run(second))); next.put("runKey", "extra_run_" + i); runs.add(next);
        }
        assertThatThrownBy(() -> PortfolioTextGridConfigNormalizer.normalize(second)).isInstanceOf(BusinessException.class);
    }
    /** 构建完整独立网格，仅末格保留文字以覆盖新增行的发布内容判断。 */
    private Map<String, Object> rectangularGrid(int rows, int columns) {
        List<Map<String, Object>> gridCells = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                String suffix = row + "_" + column;
                String text = row == rows - 1 && column == columns - 1 ? "第八行内容" : "";
                gridCells.add(new LinkedHashMap<>(Map.of("cellKey", "cell_" + suffix,
                        "row", row, "column", column, "rowSpan", 1, "columnSpan", 1,
                        "blocks", List.of(Map.of("blockKey", "block_" + suffix,
                                "runs", List.of(Map.of("runKey", "run_" + suffix, "text", text)))))));
            }
        }
        return Map.of("rows", rows, "columns", columns, "columnWeights", Collections.nCopies(columns, 1),
                "rowMinHeightsRpx", Collections.nCopies(rows, 180), "cells", gridCells);
    }
    /** 测试读取规范化单元格。 */
    private List<Map<String, Object>> cells(Map<String, Object> grid) { return (List<Map<String, Object>>) grid.get("cells"); }
    /** 测试读取首格段落。 */
    private List<Map<String, Object>> blocks(Map<String, Object> grid) { return (List<Map<String, Object>>) cells(grid).getFirst().get("blocks"); }
    /** 测试读取首片段。 */
    private Map<String, Object> run(Map<String, Object> grid) { return ((List<Map<String, Object>>) blocks(grid).getFirst().get("runs")).getFirst(); }
}
