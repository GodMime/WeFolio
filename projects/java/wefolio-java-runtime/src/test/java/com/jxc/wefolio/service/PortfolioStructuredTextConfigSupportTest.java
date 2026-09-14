package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentValidator;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 结构化文字规则测试，通过真实个人配置入口验证约定。 */
class PortfolioStructuredTextConfigSupportTest {
    /** 默认值仅回填缺失字段，保持内容换行与自动颜色。 */
    @Test void defaultsAndIndependentStyles() {
        JSONObject result = normalize("""
                {"blocks":[{"blockKey":"title","type":"TITLE","content":" 标题\\n第二行 "},
                {"blockKey":"body","type":"PARAGRAPH","content":"正文","fontSizeRpx":96,
                 "fontWeight":"BOLD","color":"#aAbBcC","alignment":"RIGHT","marginTopRpx":128}]}
                """);
        JSONObject title = result.getJSONArray("blocks").getJSONObject(0);
        assertThat(title.getString("color")).isEqualTo("AUTO");
        assertThat(title.getInteger("fontSizeRpx")).isEqualTo(44);
        assertThat(title.getInteger("marginTopRpx")).isEqualTo(24);
        assertThat(title.getString("content")).isEqualTo(" 标题\n第二行 ");
        JSONObject body = result.getJSONArray("blocks").getJSONObject(1);
        assertThat(body.getInteger("fontSizeRpx")).isEqualTo(96);
        assertThat(body.getString("color")).isEqualTo("#aAbBcC");
        assertThat(body.getInteger("marginTopRpx")).isEqualTo(128);
        assertThat(result.getBoolean("backgroundEnabled")).isFalse();
        assertThat(result.getString("backgroundTreatment")).isEqualTo("GRADIENT");
    }

    /** 个人与团队各类文字区块接受扩大后的字号边界及相邻整数，保存与展示保持一致。 */
    @Test void acceptsSharedFontSizeRangeForAllTextBlocks() {
        for (boolean team : List.of(false,true)) {
            for (String type : List.of("TITLE","PARAGRAPH","LIST","HINT")) {
                for (int size : List.of(10,11,19,20,48,49,95,96)) {
                    JSONObject source = textConfig(type,size);
                    JSONObject normalized = normalize(source,team);
                    assertThat(normalized.getJSONArray("blocks").getJSONObject(0))
                            .containsEntry("fontSizeRpx",size);
                    JSONObject rendered = new JSONObject(PortfolioStructuredTextConfigSupport.forRender(normalized,team));
                    assertThat(rendered.getJSONArray("blocks").getJSONObject(0))
                            .containsEntry("fontSizeRpx",size);
                }
                for (Object size : Arrays.asList(9,97,10.5,"10",null)) {
                    assertThatThrownBy(() -> normalize(textConfig(type,size),team))
                            .as("team=%s, type=%s, size=%s",team,type,size).isInstanceOf(BusinessException.class);
                }
            }
        }
    }

    /** 留白只保留适用字段，列表剔除临时普通内容。 */
    @Test void stripsInapplicableFields() {
        JSONObject result = normalize("""
                {"verticalAlignment":"TOP","backgroundWork":{"url":"injected"},"blocks":[
                {"blockKey":"space","type":"SPACER","content":"丢弃","fontSizeRpx":999},
                {"blockKey":"list","type":"LIST","content":"丢弃","items":["一","二"]}]}
                """);
        assertThat(result).doesNotContainKeys("verticalAlignment", "backgroundWork");
        assertThat(result.getJSONArray("blocks").getJSONObject(0)).containsEntry("heightRpx",32)
                .doesNotContainKeys("content","fontSizeRpx","color");
        assertThat(result.getJSONArray("blocks").getJSONObject(1)).doesNotContainKey("content");
    }

    /** 个人与团队留白支持扩大的高度范围，保存及展示保留旧客户端上下间距。 */
    @Test void acceptsSpacerHeightUpTo512AndPreservesLegacyMargins() {
        for (boolean team : List.of(false,true)) {
            for (int height : List.of(0,128,132,508,512)) {
                JSONObject source = spacerConfig(height);
                JSONObject normalized = normalize(source,team);
                JSONObject spacer = normalized.getJSONArray("blocks").getJSONObject(0);
                assertThat(spacer).containsEntry("heightRpx",height)
                        .containsEntry("marginTopRpx",128).containsEntry("marginBottomRpx",16);
                JSONObject rendered = new JSONObject(PortfolioStructuredTextConfigSupport.forRender(normalized,team));
                assertThat(rendered.getJSONArray("blocks").getJSONObject(0)).isEqualTo(spacer);
            }
        }
    }

    /** 留白高度仍须为范围内整数且符合步进，上下间距不能随留白高度放宽。 */
    @Test void rejectsInvalidSpacerHeightAndRetainsMarginLimits() {
        for (boolean team : List.of(false,true)) {
            for (Object height : Arrays.asList(-4,2,513,516,512.5,"512",null)) {
                assertThatThrownBy(() -> normalize(spacerConfig(height),team))
                        .as("team=%s, height=%s",team,height).isInstanceOf(BusinessException.class);
            }
            for (String margin : List.of("marginTopRpx","marginBottomRpx")) {
                JSONObject source = spacerConfig(512);
                source.getJSONArray("blocks").getJSONObject(0).put(margin,132);
                assertThatThrownBy(() -> normalize(source,team))
                        .as("team=%s, margin=%s",team,margin).isInstanceOf(BusinessException.class);
            }
        }
    }

    /** 字号、间距、颜色、标识和内容边界不能静默回填。 */
    @Test void rejectsInvalidFieldsAndEmptyContent() {
        for (Map<String,Object> field : List.<Map<String,Object>>of(Map.of("fontSizeRpx",97),
                Map.of("fontSizeRpx",20.5), Map.of("fontSizeRpx","44"), Map.of("marginTopRpx",3),
                Map.of("marginBottomRpx",132), Map.of("color","#FFF"), Map.of("color",false),
                Map.of("fontWeight","LIGHT"), Map.of("alignment","TOP"), Map.of("fontFamily","bad"),
                Map.of("content"," \n "), Map.of("blockKey",""))) {
            JSONObject block = JSON.parseObject("{\"blockKey\":\"b\",\"type\":\"TITLE\",\"content\":\"正文\"}");
            block.putAll(field);
            assertThatThrownBy(() -> normalize(JSON.toJSONString(Map.of("blocks",List.of(block)))))
                    .as(field.toString()).isInstanceOf(BusinessException.class);
        }
        for (String blocks : List.of("[]", "[{\"blockKey\":\"s\",\"type\":\"SPACER\"}]",
                "[{\"blockKey\":\"l\",\"type\":\"LIST\",\"items\":[]}]")) {
            assertThatThrownBy(() -> normalize("{\"blocks\":"+blocks+"}"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    /** Unicode 总量按码点计算，超出一个字符即拒绝。 */
    @Test void countsCodePointsAndCapsBlocksAndItems() {
        JSONObject block = new JSONObject(Map.of("blockKey","b","type","TITLE","content","😀".repeat(2000)));
        assertThat(normalize(JSON.toJSONString(Map.of("blocks",List.of(block)))).getJSONArray("blocks")).hasSize(1);
        block.put("content","😀".repeat(2001));
        assertThatThrownBy(() -> normalize(JSON.toJSONString(Map.of("blocks",List.of(block)))))
                .isInstanceOf(BusinessException.class);
        block.put("content","正文");
        assertThatThrownBy(() -> normalize(JSON.toJSONString(Map.of("blocks",List.of(block,block)))))
                .isInstanceOf(BusinessException.class);
        block.put("type","LIST"); block.put("items",Collections.nCopies(11,"条目"));
        assertThatThrownBy(() -> normalize(JSON.toJSONString(Map.of("blocks",List.of(block)))))
                .isInstanceOf(BusinessException.class);
    }

    /** 区块上限含留白，列表上限及组件跨区块总量同时生效。 */
    @Test void enforcesBlockListAndCombinedTextBoundaries() {
        ArrayList<Map<String,Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("blockKey","title","type","TITLE","content","标题"));
        for (int index=1;index<20;index++) { blocks.add(Map.of("blockKey","space-"+index,"type","SPACER")); }
        assertThat(normalize(JSON.toJSONString(Map.of("blocks",blocks))).getJSONArray("blocks")).hasSize(20);
        blocks.add(Map.of("blockKey","space-20","type","SPACER"));
        assertThatThrownBy(() -> normalize(JSON.toJSONString(Map.of("blocks",blocks)))).isInstanceOf(BusinessException.class);
        JSONObject list = new JSONObject(Map.of("blockKey","list","type","LIST","items",Collections.nCopies(10,"😀".repeat(200))));
        assertThat(normalize(JSON.toJSONString(Map.of("blocks",List.of(list)))).getJSONArray("blocks")).hasSize(1);
        assertThatThrownBy(() -> normalize(JSON.toJSONString(Map.of("blocks",List.of(list,
                Map.of("blockKey","extra","type","PARAGRAPH","content","一")))))).isInstanceOf(BusinessException.class);
    }

    /** 背景偏好严格校验，显示信息不得进入持久化配置。 */
    @Test void validatesBackgroundBooleansTreatmentsAndExactIds() {
        JSONObject config = JSON.parseObject("{\"blocks\":[{\"blockKey\":\"b\",\"type\":\"TITLE\",\"content\":\"文字\"}]}");
        for (Object invalid : List.of("true",1)) {
            config.put("backgroundEnabled",invalid);
            assertThatThrownBy(() -> normalize(config.toJSONString())).isInstanceOf(BusinessException.class);
        }
        config.put("backgroundEnabled",false);
        config.put("backgroundTreatment","UNKNOWN");
        assertThatThrownBy(() -> normalize(config.toJSONString())).isInstanceOf(BusinessException.class);
        config.put("backgroundTreatment","ORIGINAL"); config.put("backgroundEnabled",true);
        for (Object invalid : List.of("11",0,-1,1.5,new BigInteger("9223372036854775808"))) {
            config.put("backgroundWorkId",invalid);
            assertThatThrownBy(() -> normalize(config.toJSONString())).isInstanceOf(BusinessException.class);
        }
    }

    /** 构造单个非空文字区块，列表使用条目字段。 */
    private JSONObject textConfig(String type,Object size) {
        JSONObject block = new JSONObject();
        block.put("blockKey","text"); block.put("type",type); block.put("fontSizeRpx",size);
        if ("LIST".equals(type)) { block.put("items",List.of("文字")); }
        else { block.put("content","文字"); }
        return new JSONObject(Map.of("blocks",List.of(block)));
    }

    /** 构造带旧客户端上下间距的留白及非空正文。 */
    private JSONObject spacerConfig(Object height) {
        JSONObject config = JSON.parseObject("""
                {"blocks":[{"blockKey":"space","type":"SPACER","marginTopRpx":128,"marginBottomRpx":16},
                {"blockKey":"body","type":"PARAGRAPH","content":"正文"}]}
                """);
        config.getJSONArray("blocks").getJSONObject(0).put("heightRpx",height);
        return config;
    }

    /** 分别通过个人和团队实际保存入口验证共享规则。 */
    private JSONObject normalize(JSONObject source,boolean team) {
        if (!team) { return normalize(JSON.toJSONString(source,JSONWriter.Feature.WriteNulls)); }
        return new TeamStructuredTextSectionComponentValidator(mock(TeamTextBackgroundSupport.class))
                .normalizeAndValidate(source,new TeamPortfolioComponentContext(1,2,3));
    }

    /** 使用实际组件入口，确保共享规则已接入保存流程。 */
    private JSONObject normalize(String raw) {
        PortfolioConfigDto config = JSON.parseObject("""
                {"schemaVersion":"standard-personal-v1","components":[
                 {"componentKey":"c_text","componentType":"STRUCTURED_TEXT_SECTION","enabled":true,"config":{}}]}
                """, PortfolioConfigDto.class);
        config.getComponents().getFirst().setConfig(JSON.parseObject(raw));
        return new JSONObject(new PortfolioConfigValidator(mock(WorkEntityMapper.class)).normalize(7L,config)
                .getComponents().getFirst().getConfig());
    }
}
