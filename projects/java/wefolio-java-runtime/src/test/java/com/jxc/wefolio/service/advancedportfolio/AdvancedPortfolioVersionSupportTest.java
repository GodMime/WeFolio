package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 高级版本的严格解析与外层适配不污染旧输入及普通 HTTP 数据结构。 */
class AdvancedPortfolioVersionSupportTest {
    /** 缺省只解释为 V1，不能为幂等比较向请求补值。 */
    @Test void interpretsLegacyRequestWithoutMutationAndRejectsInvalidExplicitValues() {
        ObjectNode legacy = tree("{}");
        assertThat(AdvancedVersionRegistry.requestVersion(legacy)).isEqualTo(1);
        assertThat(legacy.isEmpty()).isTrue();
        for (String raw : List.of("null", "\"2\"", "true", "2.0", "0", "-1", "3")) {
            assertThatThrownBy(() -> AdvancedVersionRegistry.requestVersion(tree("{\"clientAdvancedVersion\":" + raw + "}")))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(AdvancedVersionRegistry.requestVersion(tree("{\"clientAdvancedVersion\":2}"))).isEqualTo(2);
    }

    /** 规范化只操作副本；缺省及显式元数据都能恢复且不升级历史基准。 */
    @Test void preservesAbsentAndExplicitMetadataAcrossOrdinaryNormalization() {
        for (String raw : List.of("{}", "{\"advancedPortfolioVersion\":1}", "{\"advancedPortfolioVersion\":2}")) {
            ObjectNode config = tree(raw);
            var metadata = AdvancedPortfolioVersionSupport.extract(config, 2);
            ObjectNode normalized = AdvancedPortfolioVersionSupport.content(config);
            assertThat(normalized.has(AdvancedPortfolioVersionSupport.FIELD)).isFalse();
            assertThat(AdvancedPortfolioVersionSupport.restore(normalized, metadata)).isEqualTo(config);
            assertThat(config).isEqualTo(tree(raw));
        }
    }

    /** 元数据非法、越权与规范化过程中偷偷增加版本均不能被自动修复。 */
    @Test void rejectsForgedAndImplicitlyInsertedMetadata() {
        for (String raw : List.of("null", "\"1\"", "false", "1.5", "0", "999")) {
            assertThatThrownBy(() -> AdvancedPortfolioVersionSupport.extract(tree("{\"advancedPortfolioVersion\":" + raw + "}"), 2))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> AdvancedPortfolioVersionSupport.extract(tree("{\"advancedPortfolioVersion\":2}"), 1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.assertNormalized(tree("{}"), tree("{\"advancedPortfolioVersion\":1}")))
                .isInstanceOf(BusinessException.class);
    }

    /** 草稿结果版本与修订配对，即使事务后的实体已经被其它保存推进也不混用。 */
    @Test void restoresOnlyConfigurationRootAndHidesInternalMetadataForBothSerializers() throws Exception {
        MinePortfolioDetailResponse detail = new MinePortfolioDetailResponse();
        detail.setConfig(new PortfolioConfigDto());
        detail.setDraftRevision(8);
        detail.setAdvancedConfigMetadata(new AdvancedPortfolioVersionSupport.Metadata(true, 2));
        var result = AdvancedPortfolioVersionSupport.response(detail);
        assertThat(result).doesNotContainKeys("advancedConfigMetadata", "advancedPortfolioVersion");
        assertThat(result.getJSONObject("config").getInteger("advancedPortfolioVersion")).isEqualTo(2);
        assertThat(result.getInteger("draftRevision")).isEqualTo(8);
        assertThat(JSON.toJSONString(detail)).doesNotContain("advancedConfigMetadata");
        assertThat(StrictAiJson.mapper().writeValueAsString(detail)).doesNotContain("advancedConfigMetadata");
    }

    /** 测试 JSON 统一走真实严格解析器。 */
    private static ObjectNode tree(String json) { return AdvancedPortfolioVersionSupport.tree(json); }
}
