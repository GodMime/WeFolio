package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import java.util.List;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.entity.PortfolioEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 供个人和团队真实规范化链共用的字体资源验收，不替代配置处理。 */
public final class PortfolioFontSavedFlowAssertions {
    /** 测试工具不实例化。 */
    private PortfolioFontSavedFlowAssertions() { }
    /** 保留作品集原身份，设置与草稿不同的已发布字体，验证访客只读取发布清单。 */
    public static void withPublishedFont(PortfolioEntity entity) {
        var config = JSON.parseObject(entity.getPublishedConfigJson());
        config.put("fonts", JSONObject.of("ALLURA", JSONObject.of("fontVersion", "gf-809e4d8b8d7e-r1")));
        config.put("components", List.of(JSONObject.of("componentKey", "font-intro", "componentType", "TEXT_SECTION",
                "enabled", true, "config", JSONObject.of("fontId", "ALLURA", "content", "Hello", "fontFamily", "SYSTEM"))));
        entity.setPublishedConfigJson(config.toJSONString());
        var plan = PortfolioFontPlan.from(config.to(PortfolioConfigDto.class));
        String stored = PortfolioFontServiceReuseTest.stored(plan, "test-bucket");
        entity.setPublishedFontAssetsJson(stored);
        entity.setDraftFontAssetsJson(stored.replace("existing", "draft-only"));
    }
    /** 断言真实访客返回 READY 发布字体、版本和原 URL，同时隐藏服务端存储身份。 */
    public static void assertPublishedVisitorFonts(PortfolioFontManifestDto fonts) {
        assertThat(fonts).isNotNull();
        assertThat(fonts.getUnavailable()).isEmpty();
        assertThat(fonts.getAssets()).singleElement().satisfies(asset -> {
            assertThat(asset.getStatus()).isEqualTo("READY");
            assertThat(asset.getFontId()).isEqualTo("ALLURA");
            assertThat(asset.getFontVersion()).isEqualTo("gf-809e4d8b8d7e-r1");
            assertThat(asset.getFontWeight()).isEqualTo(400);
            assertThat(asset.getAssetId()).isEqualTo("existing");
            assertThat(asset.getUrl()).isEqualTo("https://fonts.example/existing.woff");
            assertThat(asset.getBucket()).isNull();
            assertThat(asset.getObjectKey()).isNull();
        });
    }
    /** 真实准备、保存、发布及访客清单投影保持已存 READY 字体，不生成或上传。 */
    public static void assertReadyReuse(PortfolioFontPlan saved, PortfolioFontPlan published) throws Exception {
        var storage = mock(PortfolioFontStorage.class); when(storage.bucket()).thenReturn("test-bucket");
        var runner = mock(PortfolioFontSubsetRunner.class);
        var service = new PortfolioFontService(new PortfolioFontProperties(), mock(PortfolioFontSources.class), runner, storage);
        var entity = new PortfolioEntity(); entity.setId(1L); entity.setCurrentRevision(1);
        String original = PortfolioFontServiceReuseTest.stored(saved, "test-bucket"); entity.setDraftFontAssetsJson(original);
        try {
            var prepared = service.prepare(entity, saved, "WFUSER01/others/fonts/1/");
            entity.setDraftFontAssetsJson(service.accept(entity, saved, prepared));
            var result = PortfolioFontManifests.project(published, service.publish(entity, published));
            assertThat(result.getUnavailable()).isEmpty();
            assertThat(JSON.toJSONString(result.getAssets()))
                    .isEqualTo(JSON.toJSONString(PortfolioFontManifests.project(saved, original).getAssets()));
            verifyNoInteractions(runner); verify(storage, never()).upload(any(), anyString(), any());
        } finally { service.close(); }
    }
}
