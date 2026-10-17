package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.PortfolioClientCapabilities;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 字体扩展通过稳定节点身份兼容旧请求。 */
class PortfolioFontConfigSupportTest {
    /** 旧端修改普通文字不会丢掉服务端选择及版本。 */
    @Test void legacySavePreservesSelection() {
        var old = config("{\"fontFamily\":\"SYSTEM\",\"fontId\":\"ALLURA\",\"content\":\"old\"}");
        old.setFonts(JSONObject.of("ALLURA", JSONObject.of("fontVersion", "v1")));
        var input = config("{\"fontFamily\":\"SYSTEM\",\"content\":\"new\"}");
        var result = config("{\"fontFamily\":\"SYSTEM\",\"content\":\"new\"}");
        PortfolioFontConfigSupport.merge(result, input, old, false);
        assertThat(result.getComponents().getFirst().getConfig()).containsEntry("fontId", "ALLURA");
        assertThat(JSON.toJSONString(result.getFonts())).isEqualTo("{\"ALLURA\":{\"fontVersion\":\"v1\"}}");
    }
    /** 显式清除与省略具有不同含义，坏版本条目不能抹掉已有版本。 */
    @Test void explicitNullClearsButOmissionPreserves() {
        var old = config("{\"fontFamily\":\"SYSTEM\",\"fontId\":\"ALLURA\"}");
        old.setFonts(JSONObject.of("ALLURA", JSONObject.of("fontVersion", "v1")));
        var input = config("{\"fontFamily\":\"SYSTEM\",\"fontId\":null}");
        var result = config("{\"fontFamily\":\"SYSTEM\"}");
        PortfolioFontConfigSupport.merge(result, input, old, true);
        assertThat(result.getComponents().getFirst().getConfig()).doesNotContainKey("fontId");
        assertThat(result.getFonts()).isNull();
        input.getComponents().getFirst().getConfig().remove("fontId");
        input.setFonts(JSONObject.of("ALLURA", JSONObject.of()));
        PortfolioFontConfigSupport.merge(result, input, old, true);
        assertThat(result.getComponents().getFirst().getConfig()).containsEntry("fontId", "ALLURA");
        assertThat(JSON.toJSONString(result.getFonts())).contains("v1");
    }
    /** 旧端透传扩展但切换旧字体时，不能被持久化回读逻辑改成系统字体。 */
    @Test void legacySwitchToWechatSurvivesPersistedNormalization() {
        var old = config("{\"fontFamily\":\"SYSTEM\",\"fontId\":\"ALLURA\"}");
        var input = config("{\"fontFamily\":\"WECHAT_SANS_SS\",\"fontId\":\"ALLURA\"}");
        var result = config("{\"fontFamily\":\"SYSTEM\",\"fontId\":\"ALLURA\"}");
        PortfolioFontConfigSupport.merge(result, input, old, false);
        assertThat(result.getComponents().getFirst().getConfig())
                .containsEntry("fontFamily", "WECHAT_SANS_SS").doesNotContainKey("fontId");
    }

    /** 根表显式 null 与省略不能命中同一个幂等请求摘要。 */
    @Test void fingerprintPreservesRootPresence() {
        var request = new MinePortfolioDraftSaveRequest();
        request.setConfig(config("{}"));
        String omitted = PortfolioFontConfigSupport.fingerprint(request);
        request.getConfig().setFonts(null);
        assertThat(PortfolioFontConfigSupport.fingerprint(request)).isNotEqualTo(omitted);
    }

    /** 能力声明不等于修改意图；字体根表和节点显式 null 则必须使用新域。 */
    @Test void capabilityOnlyUsesLegacyButExplicitNullUsesFontDomain() {
        var capability = new PortfolioClientCapabilities();
        capability.setPortfolioRemoteFont(1);
        var personal = new MinePortfolioDraftSaveRequest();
        personal.setClientCapabilities(capability);
        personal.setConfig(config("{}"));
        var team = new TeamPortfolioDraftSaveRequest();
        team.setClientCapabilities(capability);
        team.setConfig(JSON.parseObject(JSON.toJSONString(personal.getConfig()), TeamPortfolioConfigDto.class));
        assertThat(PortfolioFontConfigSupport.hasFontIntent(personal)).isFalse();
        assertThat(PortfolioFontConfigSupport.hasFontIntent(team)).isFalse();
        PortfolioFontConfigSupport.nodes(personal.getConfig()).values().forEach(node -> node.put("fontId", null));
        PortfolioFontConfigSupport.nodes(team.getConfig()).values().forEach(node -> node.put("fontId", null));
        assertThat(PortfolioFontConfigSupport.hasFontIntent(personal)).isTrue();
        assertThat(PortfolioFontConfigSupport.hasFontIntent(team)).isTrue();
        personal.setClientCapabilities(null);
        team.setClientCapabilities(null);
        assertThat(PortfolioFontConfigSupport.hasFontIntent(personal)).isFalse();
        assertThat(PortfolioFontConfigSupport.hasFontIntent(team)).isFalse();
        assertThat(PortfolioFontConfigSupport.legacyRequest(personal)).doesNotContainKeys("clientCapabilities");
    }

    /** 真实旧端省略及透传两种保存链均保留服务端选择，新端 null 才显式清除。 */
    @Test void realLegacySerializerVariantsPreserveAllTextKindsAndNewNullClears() throws Exception {
        String fixtureJson = Files.readString(Path.of("src/test/resources/portfolio-font-legacy-goldens.json"));
        for (String variant : List.of("untouched", "edited")) {
            JSONObject fixtures = JSON.parseObject(fixtureJson).getJSONObject("requests");
            JSONObject oldPersonal = fixtures.getJSONObject("portfolios").getJSONObject("untouched")
                    .getJSONObject("request").getJSONObject("config");
            var existing = oldPersonal.to(PortfolioConfigDto.class);
            existing.setFonts(JSONObject.of("ALLURA", JSONObject.of("fontVersion", "fixed-old")));
            var input = fixtures.getJSONObject("portfolios").getJSONObject(variant).getJSONObject("request")
                    .getJSONObject("config").to(PortfolioConfigDto.class);
            var output = JSON.parseObject(JSON.toJSONString(input), PortfolioConfigDto.class);
            PortfolioFontConfigSupport.merge(output, input, existing, false);
            assertThat(PortfolioFontConfigSupport.nodes(output)).hasSize(3);
            PortfolioFontConfigSupport.nodes(output).values().forEach(node -> assertThat(node).containsEntry("fontId", "ALLURA"));
            assertThat(PortfolioFontConfigSupport.version(output.getFonts(), "ALLURA")).isEqualTo("fixed-old");
            PortfolioFontConfigSupport.nodes(input).values().forEach(node -> node.put("fontId", null));
            PortfolioFontConfigSupport.merge(output, input, existing, true);
            PortfolioFontConfigSupport.nodes(output).values().forEach(node -> assertThat(node).doesNotContainKey("fontId"));
            assertThat(output.getFonts()).isNull();

            var existingTeam = fixtures.getJSONObject("team-portfolios").getJSONObject("untouched")
                    .getJSONObject("request").getJSONObject("config").to(TeamPortfolioConfigDto.class);
            existingTeam.setFonts(JSONObject.of("ALLURA", JSONObject.of("fontVersion", "fixed-old")));
            var inputTeam = fixtures.getJSONObject("team-portfolios").getJSONObject(variant).getJSONObject("request")
                    .getJSONObject("config").to(TeamPortfolioConfigDto.class);
            var outputTeam = JSON.parseObject(JSON.toJSONString(inputTeam), TeamPortfolioConfigDto.class);
            PortfolioFontConfigSupport.merge(outputTeam, inputTeam, existingTeam, false);
            assertThat(PortfolioFontConfigSupport.nodes(outputTeam)).hasSize(3);
            PortfolioFontConfigSupport.nodes(outputTeam).values().forEach(node -> assertThat(node).containsEntry("fontId", "ALLURA"));
            assertThat(PortfolioFontConfigSupport.version(outputTeam.getFonts(), "ALLURA")).isEqualTo("fixed-old");
            PortfolioFontConfigSupport.nodes(inputTeam).values().forEach(node -> node.put("fontId", null));
            PortfolioFontConfigSupport.merge(outputTeam, inputTeam, existingTeam, true);
            PortfolioFontConfigSupport.nodes(outputTeam).values().forEach(node -> assertThat(node).doesNotContainKey("fontId"));
            assertThat(outputTeam.getFonts()).isNull();
        }
    }

    /** 同一根表下，三类节点的省略/null/字符串分别形成不同请求身份。 */
    @Test void nodePresenceChangesFingerprintForEveryOwnerAndTextKind() throws Exception {
        var fixtures = JSON.parseObject(Files.readString(Path.of("src/test/resources/portfolio-font-legacy-goldens.json")))
                .getJSONObject("requests");
        for (String owner : List.of("portfolios", "team-portfolios")) {
            for (int index = 0; index < 3; index++) {
                var json = fixtures.getJSONObject(owner).getJSONObject("untouched").getJSONObject("request").toJSONString();
                Object request = owner.equals("portfolios") ? JSON.parseObject(json, MinePortfolioDraftSaveRequest.class)
                        : JSON.parseObject(json, TeamPortfolioDraftSaveRequest.class);
                var nodes = request instanceof MinePortfolioDraftSaveRequest personal ? PortfolioFontConfigSupport.nodes(personal.getConfig())
                        : PortfolioFontConfigSupport.nodes(((TeamPortfolioDraftSaveRequest) request).getConfig());
                assertThat(nodes).hasSize(3);
                var node = new ArrayList<>(nodes.values()).get(index);
                node.remove("fontId"); String omitted = PortfolioFontConfigSupport.fingerprint(request);
                node.put("fontId", null); String cleared = PortfolioFontConfigSupport.fingerprint(request);
                node.put("fontId", "ALLURA"); String selected = PortfolioFontConfigSupport.fingerprint(request);
                assertThat(List.of(omitted, cleared, selected)).as(owner + index).doesNotHaveDuplicates();
            }
        }
    }

    /** 构造包含普通文字的公开配置输入。 */
    private PortfolioConfigDto config(String style) {
        return JSON.parseObject("{\"components\":[{\"componentKey\":\"intro\",\"componentType\":\"TEXT_SECTION\",\"config\":" + style + "}]}", PortfolioConfigDto.class);
    }
}
