package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.ArrayList;
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
