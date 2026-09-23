package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 字体计划按 Unicode 字符集合聚合，独立于工具链和节点顺序。 */
class PortfolioFontPlanTest {
    /** 固定字面摘要从独立 v1 输入留档；算法重构不得自动改写预期。 */
    @Test void preservesV1GoldenPlanAndDemandHashes() throws Exception {
        var cases = JSON.parseObject(Files.readString(Path.of("src/test/resources/portfolio-font-plan-v1-goldens.json"))).getJSONArray("cases");
        for (int i = 0; i < cases.size(); i++) {
            var fixture = cases.getJSONObject(i);
            var config = fixture.getJSONObject("config").to(PortfolioConfigDto.class);
            var plan = PortfolioFontPlan.from(config);
            assertThat(plan.hash()).as(fixture.getString("name")).isEqualTo(fixture.getString("planHash"));
            assertThat(PortfolioFontPlan.from(fixture.getJSONObject("config").to(TeamPortfolioConfigDto.class)).hash())
                    .as("团队 " + fixture.getString("name")).isEqualTo(fixture.getString("planHash"));
            var groups = fixture.getJSONArray("groups"); assertThat(plan.groups()).hasSize(groups.size());
            for (int g = 0; g < groups.size(); g++) {
                assertThat(plan.groups().get(g).demandHash()).isEqualTo(groups.getJSONObject(g).getString("demandHash"));
                assertThat(plan.groups().get(g).fontWeight()).isEqualTo(groups.getJSONObject(g).getIntValue("fontWeight"));
            }
        }
    }
    /** 普通文字只收集正文，改变顺序不改变资源需求。 */
    @Test void groupsContentAsCodepoints() {
        var first = plan("AB😀A");
        var second = plan("😀BA");
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(first.groups()).hasSize(1);
        assertThat(first.groups().getFirst().codepoints()).containsExactly(65, 66, 128512);
        assertThat(plan("ABC").hash()).isNotEqualTo(first.hash());
    }
    /** 构造字体选择和固定版本的公开配置。 */
    private PortfolioFontPlan plan(String text) {
        var config = JSON.parseObject("{\"fonts\":{\"ALLURA\":{\"fontVersion\":\"v1\"}},\"components\":[{\"componentKey\":\"text\",\"componentType\":\"TEXT_SECTION\",\"config\":{\"fontId\":\"ALLURA\",\"title\":\"never included\",\"content\":\"" + text + "\"}}]}", PortfolioConfigDto.class);
        return PortfolioFontPlan.from(config);
    }
}
