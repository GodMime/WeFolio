package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import static org.mockito.Mockito.doReturn;
import static org.assertj.core.api.Assertions.assertThat;

/** 版本表替代旧推导时，现存十二个请求字重的生成身份必须等价。 */
class PortfolioFontWeightCompatibilityTest {
    /** 真实执行使用独立资源目录。 */
    @TempDir Path temporary;
    /** 六个现存版本的旧物理字重基线。 */
    private static final Map<String, Integer> BOLD = Map.of("CORMORANT_GARAMOND",700,"MANROPE",700,"ALLURA",400,
            "SOURCE_HAN_SERIF_SC",700,"ZCOOL_XIAOWEI",400,"LXGW_WENKAI",400);
    /** 版本表和旧源 face 推导逐项一致；覆盖合成与真实粗体。 */
    @Test void matchesLegacyWeightsAndFacesForEveryExistingVersion() throws Exception {
        var fonts = JSON.parseObject(Files.readString(Path.of("src/main/resources/fonts/manifest.json"))).getJSONArray("fonts");
        var cases = goldens().getJSONArray("cases"); assertThat(cases).hasSize(12);
        for (Object entry : cases) {
            var expected = (JSONObject) entry;
            var font = fonts.stream().map(value -> (JSONObject) value)
                    .filter(value -> value.getString("fontId").equals(expected.getString("fontId"))).findFirst().orElseThrow();
            assertThat(font.getString("fontVersion")).isEqualTo(expected.getString("fontVersion"));
            int actual = PortfolioFontWeights.physical(expected.getString("fontId"), expected.getString("fontVersion"), expected.getIntValue("requested"));
            assertThat(actual).isEqualTo(expected.getIntValue("physical"));
            assertThat(font.getJSONObject(actual == 700 ? "bold" : "normal").getString("relativePath"))
                    .isEqualTo(expected.getString("face"));
        }
    }
    /** 同一固定构建下十二组经过真实生成，全部子集摘要匹配独立旧算法黄金值。 */
    @Test void preservesSubsetHashAtFixedBuildId() throws Exception {
        var goldens = goldens(); var cases = goldens.getJSONArray("cases");
        try (var fixture = new PortfolioFontExecutionFixture(temporary); var budget = new PortfolioFontBudget(30000)) {
            doReturn(goldens.getString("buildId")).when(fixture.sources).getBuildId();
            var groups = new ArrayList<PortfolioFontPlan.Group>();
            for (Object entry : cases) {
                var expected = (JSONObject) entry;
                groups.add(new PortfolioFontPlan.Group(expected.getString("fontId"), expected.getString("fontVersion"),
                        expected.getIntValue("requested"), "normal", List.of(65, 20013), expected.getString("demandHash")));
            }
            var outputs = fixture.runner.generate(groups, Files.createDirectory(temporary.resolve("all-faces")), budget);
            assertThat(outputs).hasSize(12);
            for (int index = 0; index < outputs.size(); index++) {
                assertThat(outputs.get(index).weight()).isEqualTo(cases.getJSONObject(index).getIntValue("physical"));
                assertThat(outputs.get(index).subsetHash()).isEqualTo(cases.getJSONObject(index).getString("subsetHash"));
            }
        }
    }
    /** 黄金数据只读取，不能在测试中用当前实现重写。 */
    private JSONObject goldens() throws Exception {
        return JSON.parseObject(Files.readString(Path.of("src/test/resources/portfolio-font-weight-v1-goldens.json")));
    }
    /** 旧版本不能套当前映射，未知版本保持请求字重。 */
    @Test void preservesHistoricalAndUnknownVersionMappings() throws Exception {
        var table = JSON.parseObject(Files.readString(Path.of("src/main/resources/fonts/physical-weights.json")));
        assertThat(table).hasSize(6);
        for (String key : table.keySet()) {
            int split = key.indexOf(':');
            String id = key.substring(0,split), version=key.substring(split+1);
            assertThat(PortfolioFontWeights.physical(id, version, 700)).isEqualTo(BOLD.get(id));
            assertThat(PortfolioFontWeights.physical(id, "unknown", 700)).isEqualTo(700);
            assertThat(PortfolioFontWeights.physical(id, null, 400)).isEqualTo(400);
        }
    }
}
