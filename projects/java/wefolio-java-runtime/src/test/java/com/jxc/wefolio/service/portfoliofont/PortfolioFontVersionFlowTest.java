package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 不可用版本在准备、保存、投影与发布间保持可修复原因。 */
class PortfolioFontVersionFlowTest {
    /** 未知版本必须在所有公开投影中保留，不能污染不同文字计划。 */
    @Test void unknownVersionSurvivesSaveAndPublishButNotAnotherPlan() throws Exception {
        assertVersionFlow("unknown", "UNKNOWN_VERSION");
    }
    /** 缺失版本同样贯穿准备、落库及发布投影，且不启动生成。 */
    @Test void missingVersionSurvivesSaveAndPublishButNotAnotherPlan() throws Exception {
        assertVersionFlow("", "MISSING_VERSION");
    }
    /** 对两类不可用版本使用同一真实应用链，保持原因隔离。 */
    private void assertVersionFlow(String version, String reason) throws Exception {
        var plan = PortfolioFontPlan.from(JSON.parseObject("""
                {"fonts":{"ALLURA":{"fontVersion":"unknown"}},"components":[
                {"componentKey":"intro","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hello"}}]}
                """.replace("unknown", version), PortfolioConfigDto.class));
        var entity = new PortfolioEntity(); entity.setId(1L); entity.setCurrentRevision(1);
        var storage = mock(PortfolioFontStorage.class);
        when(storage.bucket()).thenReturn("bucket");
        var service = new PortfolioFontService(new PortfolioFontProperties(),
                mock(PortfolioFontSources.class), mock(PortfolioFontSubsetRunner.class), storage);
        try {
            var prepared = service.prepare(entity, plan, PortfolioFontWrite.prefix("WF", 1L));
            assertThat(prepared.response().getUnavailable().getFirst().getReason()).isEqualTo(reason);
            entity.setDraftFontAssetsJson(service.accept(entity, plan, prepared));
            assertThat(PortfolioFontManifests.project(plan, entity.getDraftFontAssetsJson())
                    .getUnavailable().getFirst().getReason()).isEqualTo(reason);
            String published = service.publish(entity, plan);
            assertThat(PortfolioFontManifests.project(plan, published).getUnavailable().getFirst().getReason())
                    .isEqualTo(reason);
            var different = PortfolioFontPlan.from(JSON.parseObject("""
                    {"fonts":{"ALLURA":{"fontVersion":"gf-809e4d8b8d7e-r1"}},"components":[
                    {"componentKey":"intro","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Other"}}]}
                    """, PortfolioConfigDto.class));
            assertThat(PortfolioFontManifests.project(different, published)
                    .getUnavailable().getFirst().getReason()).isEqualTo("FONT_UNAVAILABLE");
            verify(storage, never()).upload(any(), anyString(), any());
        } finally { service.close(); }
    }
}
