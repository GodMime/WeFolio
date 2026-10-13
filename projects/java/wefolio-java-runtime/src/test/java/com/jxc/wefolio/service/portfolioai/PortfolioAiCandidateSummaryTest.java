package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 来源使用和差异文案来自真实配置，覆盖跨来源命名空间与未用失效项。 */
class PortfolioAiCandidateSummaryTest {
    /** 使用真实严格解析器。 */
    private ObjectNode node(String json) { return StrictAiJson.parseObject(json, 262144, 16); }

    /** 同号数据库ID的作品与成员作品集不能串成使用项，背景与组引用计入最终使用。 */
    @Test void actualReferencesDetermineUsedAndUnusedSources() {
        ObjectNode request = node("{\"facts\":{\"sources\":[{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"201\"},{\"sourceNo\":2,\"kind\":\"MEMBER_PORTFOLIO\",\"sourceId\":\"201\"},{\"sourceNo\":4,\"kind\":\"WORK\",\"sourceId\":\"202\",\"canBind\":false}]}}");
        ObjectNode candidate = node("{\"components\":[{\"config\":{\"groups\":[{\"workIds\":[201]}]}}],\"backgroundAudio\":{\"workId\":201}}");
        Map<String,Object> usage = PortfolioAiCandidateSummary.sources(candidate, request);
        assertThat(usage.get("usedSourceNos")).isEqualTo(List.of(1));
        assertThat((List<?>) usage.get("unusedSources")).hasSize(2);
        assertThat(usage.toString()).contains("来源当前不可用").doesNotContain("sourceId");
    }

    /** 仅用于作品集封面的素材也应计入已使用，并保持 WORK 与成员作品集身份隔离。 */
    @Test void exactTrustedShareCoverValueCountsItsWorkSourceAsUsed() {
        ObjectNode request = node("""
                {"facts":{"sources":[{"sourceNo":1,"kind":"WORK","sourceId":"201","canBind":true},
                  {"sourceNo":2,"kind":"MEMBER_PORTFOLIO","sourceId":"201","canBind":true}],
                 "bindings":[{"ref":"source_cover_1","kind":"IMAGE","sourceNo":1,
                  "allowedSlots":["coverUrl"],"value":"https://cdn.test/photo.jpg"}]}}
                """);
        ObjectNode candidate = node("{\"share\":{\"coverUrl\":\"https://cdn.test/photo.jpg\"},\"components\":[]}");

        Map<String, Object> usage = PortfolioAiCandidateSummary.sources(candidate, request);

        assertThat(usage.get("usedSourceNos")).isEqualTo(List.of(1));
        assertThat((List<?>) usage.get("unusedSources")).hasSize(1);
    }

    /** 地址前缀相同、正文中出现图片或其它类型隐藏资源均不能冒充封面素材使用。 */
    @Test void shareCoverUsageRequiresExactImageBindingValue() {
        ObjectNode request = node("""
                {"facts":{"sources":[{"sourceNo":1,"kind":"WORK","sourceId":"201","canBind":true}],
                 "bindings":[{"ref":"source_cover_1","kind":"IMAGE","sourceNo":1,
                  "allowedSlots":["coverUrl"],"value":"https://cdn.test/photo.jpg"}]}}
                """);
        ObjectNode candidate = node("{\"share\":{\"coverUrl\":\"https://cdn.test/photo.jpg?fake=1\"},\"components\":[{\"config\":{\"content\":\"https://cdn.test/photo.jpg\"}}]}");
        assertThat(PortfolioAiCandidateSummary.sources(candidate, request).get("usedSourceNos")).isEqualTo(List.of());
        ((ObjectNode) candidate.path("share")).put("coverUrl", "https://cdn.test/photo.jpg");
        ((ObjectNode) request.path("facts").path("bindings").get(0)).put("kind", "QR_IMAGE");
        assertThat(PortfolioAiCandidateSummary.sources(candidate, request).get("usedSourceNos")).isEqualTo(List.of());
    }

    /** 仅用于分享头像的图片来源计入已使用，本人头像句柄没有素材号则不参与统计。 */
    @Test void trustedShareAvatarValueCountsOnlyItsWorkSourceAsUsed() {
        ObjectNode request = node("""
                {"facts":{"sources":[{"sourceNo":1,"kind":"WORK","sourceId":"201","canBind":true}],
                 "bindings":[{"ref":"source_cover_1","kind":"IMAGE","sourceNo":1,
                  "allowedSlots":["coverUrl","avatarUrl"],"value":"https://cdn.test/photo.jpg"},
                  {"ref":"profile_share_avatar","kind":"IMAGE","allowedSlots":["avatarUrl"],
                   "value":"https://cdn.test/avatar.jpg"}]}}
                """);
        ObjectNode candidate = node("{\"share\":{\"avatarUrl\":\"https://cdn.test/photo.jpg\"},\"components\":[]}");

        assertThat(PortfolioAiCandidateSummary.sources(candidate, request).get("usedSourceNos")).isEqualTo(List.of(1));
        ((ObjectNode) candidate.path("share")).put("avatarUrl", "https://cdn.test/avatar.jpg");
        assertThat(PortfolioAiCandidateSummary.sources(candidate, request).get("usedSourceNos")).isEqualTo(List.of());
        ((ObjectNode) candidate.path("share")).put("avatarUrl", "https://cdn.test/photo.jpg");
        ((ObjectNode) request.path("facts").path("bindings").get(0)).withArray("allowedSlots").removeAll().add("coverUrl");
        assertThat(PortfolioAiCandidateSummary.sources(candidate, request).get("usedSourceNos")).isEqualTo(List.of());
    }

    /** 移动不应被派生排序误算为内容修改，新增/移除也由组件身份独立统计。 */
    @Test void movedComponentsAreNotFalselyReportedAsEditedContent() {
        ObjectNode before = node("{\"components\":[{\"componentKey\":\"a\",\"sortOrder\":1000,\"config\":{\"content\":\"甲\"}},{\"componentKey\":\"b\",\"sortOrder\":2000,\"config\":{\"content\":\"乙\"}}]}");
        ObjectNode after = node("{\"components\":[{\"componentKey\":\"b\",\"sortOrder\":1000,\"config\":{\"content\":\"乙\"}},{\"componentKey\":\"a\",\"sortOrder\":2000,\"config\":{\"content\":\"甲\"}}]}");
        assertThat(PortfolioAiCandidateSummary.describe(before, after, node("{\"facts\":{\"sources\":[]}}")))
                .containsExactly("调整 2 个组件的位置");
    }
}
