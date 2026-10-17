package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioFontManifestDto.Asset;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 双清单引用、旧资源复核及事务边界的行为测试。 */
class PortfolioFontServiceTest {
    /** 构造固定单组字体计划。 */
    private PortfolioFontPlan plan() {
        return PortfolioFontPlan.from(JSON.parseObject("""
                {"fonts":{"ALLURA":{"fontVersion":"v1"}},"components":[
                  {"componentKey":"text","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hello"}}]}
                """, PortfolioConfigDto.class));
    }
    /** 构造仅服务端持有的可信清单。 */
    private String manifest(PortfolioFontPlan plan, String key) {
        var manifest = PortfolioFontManifests.empty(plan);
        var group = plan.groups().getFirst();
        Asset asset = new Asset();
        asset.setAssetId("asset"); asset.setBucket("test-bucket"); asset.setObjectKey(key);
        asset.setUrl("https://fonts.example/" + key); asset.setStatus(PortfolioFontManifests.READY);
        asset.setFormat(PortfolioFontManifests.WOFF); asset.setFontWeight(400); asset.setFontStyle(group.fontStyle());
        asset.setFontId(group.fontId()); asset.setFontVersion(group.fontVersion()); asset.setDemandHash(group.demandHash());
        manifest.getAssets().add(asset);
        return JSON.toJSONString(manifest);
    }
    /** 预检快照已释放的旧键不能在最终行锁内重新认领。 */
    @Test void rejectsReleasedKeysAndKeepsSharedPublishedReferences() {
        var sources = mock(PortfolioFontSources.class);
        var runner = mock(PortfolioFontSubsetRunner.class);
        var storage = mock(PortfolioFontStorage.class);
        when(storage.bucket()).thenReturn("test-bucket");
        var plan = plan();
        var snapshot = new PortfolioEntity(); snapshot.setId(1L); snapshot.setCurrentRevision(2);
        String json = manifest(plan, "WF/others/fonts/1/old.woff"); snapshot.setDraftFontAssetsJson(json);
        try (var service = new ServiceFixture(new PortfolioFontProperties(), sources, runner, storage)) {
            var prepared = service.value.prepare(snapshot, plan, "WF/others/fonts/1/");
            var locked = new PortfolioEntity(); locked.setId(1L); locked.setCurrentRevision(2);
            var rejected = PortfolioFontManifests.parse(service.value.accept(locked, plan, prepared));
            assertThat(rejected.getAssets()).isEmpty(); assertThat(rejected.getUnavailable()).hasSize(1);
            assertThatThrownBy(() -> service.value.accept(locked, plan, prepared)).isInstanceOf(IllegalStateException.class);
            locked.setPublishedFontAssetsJson(json);
            var second = service.value.prepare(snapshot, plan, "WF/others/fonts/1/");
            assertThat(PortfolioFontManifests.parse(service.value.accept(locked, plan, second)).getAssets()).hasSize(1);
            assertThat(PortfolioFontManifests.released(json, json, null, json)).isEmpty();
            assertThat(PortfolioFontManifests.released(json, json, null, null)).containsExactly("test-bucket|WF/others/fonts/1/old.woff");
            verifyNoInteractions(runner);
        }
    }
    /** 事务内准备和物理删除必须在任何存储请求发出前拒绝。 */
    @Test void rejectsFontIoInsideTransaction() {
        var storage = mock(PortfolioFontStorage.class);
        try (var service = new ServiceFixture(new PortfolioFontProperties(), mock(PortfolioFontSources.class), mock(PortfolioFontSubsetRunner.class), storage)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> service.value.prepare(new PortfolioEntity(), plan(), "WF/others/fonts/1/"))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> service.value.cleanup(List.of("test-bucket|old.woff"), "WF/others/fonts/1/"))
                        .isInstanceOf(IllegalStateException.class);
                verifyNoInteractions(storage);
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }
    /** 单个对象删除失败不补发，分享码和异桶对象不会被删除。 */
    @Test void deletionIsOnceAndRestrictedToCurrentFontDirectory() throws Exception {
        var storage = mock(PortfolioFontStorage.class); when(storage.bucket()).thenReturn("test-bucket");
        String key = "WF/others/fonts/1/old.woff";
        doThrow(new IllegalStateException("offline")).when(storage).delete(eq(key), any());
        try (var service = new ServiceFixture(new PortfolioFontProperties(), mock(PortfolioFontSources.class), mock(PortfolioFontSubsetRunner.class), storage)) {
            assertThatCode(() -> service.value.cleanup(List.of("test-bucket|" + key, "test-bucket|" + key,
                    "other-bucket|" + key, "test-bucket|WF/protfolio/miniapp-code/a.woff"), "WF/others/fonts/1/"))
                    .doesNotThrowAnyException();
            verify(storage, times(1)).delete(eq(key), any());
            verify(storage, times(1)).delete(anyString(), any());
        }
    }
    /** 测试退出时释放同步执行器。 */
    private static final class ServiceFixture implements AutoCloseable {
        /** 本次测试的真实资源编排服务。 */
        private final PortfolioFontService value;
        /** 注入受控依赖。 */
        private ServiceFixture(PortfolioFontProperties properties, PortfolioFontSources sources, PortfolioFontSubsetRunner runner, PortfolioFontStorage storage) {
            value = new PortfolioFontService(properties, sources, runner, storage);
        }
        /** 释放执行器。 */
        @Override public void close() { value.close(); }
    }
}
