package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioFontManifestDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 已有资源按需求和原引用复用，部署构建身份不重写对象键。 */
class PortfolioFontServiceReuseTest {
    /** 同一请求同时覆盖两档字重，真实计划按已固定版本合并。 */
    static PortfolioFontPlan plan() {
        return PortfolioFontPlan.from(JSON.parseObject("""
                {"fonts":{"ALLURA":{"fontVersion":"gf-809e4d8b8d7e-r1"}},"components":[
                {"componentKey":"a","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hello","fontWeight":"BOLD"}},
                {"componentKey":"b","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hello"}}]}
                """, PortfolioConfigDto.class));
    }
    /** 服务端可信历史清单，物理身份独立于新构建。 */
    static String stored(PortfolioFontPlan plan, String bucket) {
        var manifest = PortfolioFontManifests.empty(plan);
        for (var group : plan.groups()) {
            var asset = new PortfolioFontManifestDto.Asset();
            asset.setBucket(bucket); asset.setObjectKey("WFUSER01/others/fonts/1/existing.woff");
            asset.setUrl("https://fonts.example/existing.woff"); asset.setAssetId("existing");
            asset.setStatus("READY"); asset.setFormat("woff"); asset.setFontId(group.fontId());
            asset.setFontVersion(group.fontVersion()); asset.setFontWeight(group.fontWeight()); asset.setFontStyle(group.fontStyle());
            asset.setDemandHash(group.demandHash()); asset.setSubsetHash("old-build-subset");
            manifest.getAssets().add(asset);
        }
        return JSON.toJSONString(manifest);
    }
    /** 准备、认领、发布均不重算已存对象键。 */
    @Test void reusesStoredAssetAcrossPrepareAcceptAndPublish() { assertReuse("build-one"); }
    /** 新构建也不得触发已存字体重新生成。 */
    @Test void reusesStoredAssetAcrossBuildChange() { assertReuse("different-build"); }
    /** 比较完整清单而不是只检查数量。 */
    private void assertReuse(String build) {
        var sources = mock(PortfolioFontSources.class); when(sources.getBuildId()).thenReturn(build);
        var runner = mock(PortfolioFontSubsetRunner.class); var storage = mock(PortfolioFontStorage.class);
        when(storage.bucket()).thenReturn("test-bucket");
        var plan = plan(); String original = stored(plan, "test-bucket");
        var snapshot = new PortfolioEntity(); snapshot.setId(1L); snapshot.setCurrentRevision(1); snapshot.setDraftFontAssetsJson(original);
        var service = new PortfolioFontService(new PortfolioFontProperties(), sources, runner, storage);
        try {
            var prepared = service.prepare(snapshot, plan, "WFUSER01/others/fonts/1/");
            String accepted = service.accept(snapshot, plan, prepared);
            assertThat(JSON.parseObject(accepted)).isEqualTo(JSON.parseObject(original));
            snapshot.setDraftFontAssetsJson(accepted);
            assertThat(JSON.parseObject(service.publish(snapshot, plan))).isEqualTo(JSON.parseObject(original));
            verifyNoInteractions(runner); verify(storage, never()).upload(any(), anyString(), any());
        } catch (Exception exception) { throw new AssertionError(exception); }
        finally { service.close(); }
    }
    /** null/null 不能被当作正确桶归属，空配置时不启动任何字体 I/O。 */
    @Test void rejectsMissingBucketWithoutLosingSelection() {
        var plan = plan(); var snapshot = new PortfolioEntity(); snapshot.setId(1L); snapshot.setCurrentRevision(1);
        snapshot.setDraftFontAssetsJson(stored(plan, null));
        var properties = new PortfolioFontProperties(); properties.setEnabled(true);
        var sources = mock(PortfolioFontSources.class); when(sources.isReady()).thenReturn(true);
        var runner = mock(PortfolioFontSubsetRunner.class); var storage = mock(PortfolioFontStorage.class);
        var service = new PortfolioFontService(properties, sources, runner, storage);
        try {
            var response = service.prepare(snapshot, plan, "WFUSER01/others/fonts/1/").response();
            assertThat(response.getAssets()).isEmpty(); assertThat(response.getUnavailable()).hasSize(plan.groups().size());
            verifyNoInteractions(runner);
        } finally { service.close(); }
    }
    /** 一份坏清单不妨碍另一份当前有效清单复用。 */
    @Test void damagedDraftDoesNotHideValidPublishedAssets() {
        var plan = plan(); var snapshot = new PortfolioEntity(); snapshot.setId(1L); snapshot.setCurrentRevision(1);
        snapshot.setPublishedFontAssetsJson(stored(plan, "test-bucket"));
        var storage = mock(PortfolioFontStorage.class); when(storage.bucket()).thenReturn("test-bucket");
        var service = new PortfolioFontService(new PortfolioFontProperties(), mock(PortfolioFontSources.class), mock(PortfolioFontSubsetRunner.class), storage);
        try {
            for (String json : List.of("", "not-json", "null", "{}", "{\"assets\":[null,{}]}")) {
                snapshot.setDraftFontAssetsJson(json);
                assertThat(service.prepare(snapshot, plan, "WFUSER01/others/fonts/1/").response().getAssets()).hasSize(plan.groups().size());
            }
        } finally { service.close(); }
    }
    /** 混合清理批次按原因准确汇总，重复对象去重且失败不重试。 */
    @Test void reportsExactCleanupCategoryCounts() throws Exception {
        var storage = mock(PortfolioFontStorage.class); when(storage.bucket()).thenReturn("test-bucket");
        String prefix = "WFUSER01/others/fonts/1/";
        doThrow(new IOException("isolated failure")).when(storage).delete(eq(prefix + "failed.woff"), any());
        var service = new PortfolioFontService(new PortfolioFontProperties(), null, null, storage);
        var logger = (Logger) LoggerFactory.getLogger(PortfolioFontService.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            service.cleanup(List.of("null|" + prefix + "missing.woff", "other-bucket|" + prefix + "foreign.woff",
                    "test-bucket|WFUSER01/protfolio/1/fonts/legacy.woff",
                    "test-bucket|WFUSER01/others/fonts/2/sibling.woff", "test-bucket|WFUSER01/others/avatar.png",
                    "test-bucket|" + prefix + "../outside.woff", "test-bucket|" + prefix,
                    "test-bucket|" + prefix + "ok.woff", "test-bucket|" + prefix + "ok.woff",
                    "test-bucket|" + prefix + "failed.woff"), prefix);
            assertCleanupCounts(logs, Map.of("BUCKET_MISSING", 1, "BUCKET_MISMATCH", 1, "LEGACY_PREFIX", 1,
                    "PREFIX_MISMATCH", 2, "INVALID_KEY", 2, "DELETE_CONFIRMED", 1, "DELETE_FAILED", 1));
            verify(storage).delete(eq(prefix + "ok.woff"), any());
            verify(storage).delete(eq(prefix + "failed.woff"), any());
            verify(storage, times(2)).delete(anyString(), any());
        } finally { service.close(); logger.detachAppender(logs); logs.stop(); }
    }
    /** 剩余预算为零时，归类为截止到期且不得启动存储删除。 */
    @Test void reportsCleanupDeadlineExpiredWithoutDeleting() throws Exception {
        var storage = mock(PortfolioFontStorage.class);
        var clock = new AtomicLong();
        var service = new PortfolioFontService(new PortfolioFontProperties(), null, null, storage) {
            @Override PortfolioFontBudget createBudget(long millis) {
                var budget = new PortfolioFontBudget(millis, clock::get);
                clock.set((millis + 1) * 1_000_000L);
                return budget;
            }
        };
        var logger = (Logger) LoggerFactory.getLogger(PortfolioFontService.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            service.cleanup(List.of("test-bucket|WFUSER01/others/fonts/1/a.woff"), "WFUSER01/others/fonts/1/");
            assertCleanupCounts(logs, Map.of("DEADLINE_EXPIRED", 1));
            verify(storage, never()).delete(anyString(), any());
        } finally { service.close(); logger.detachAppender(logs); logs.stop(); }
    }
    /** 从真实单次汇总日志读取全部计数；未预期的类别必须保持零。 */
    private static void assertCleanupCounts(ListAppender<ILoggingEvent> logs, Map<String, Integer> nonzero) {
        var events = logs.list.stream().filter(event -> event.getMessage().contains("counts={}")).toList();
        assertThat(events).hasSize(1);
        Object[] arguments = events.getFirst().getArgumentArray();
        var counters = (Map<?, ?>) arguments[arguments.length - 1];
        Map<String, Integer> actual = new HashMap<>();
        counters.forEach((reason, value) -> actual.put(reason.toString(), ((Number) value).intValue()));
        Map<String, Integer> expected = new HashMap<>();
        for (String reason : List.of("INVALID_PREFIX", "BUCKET_MISSING", "BUCKET_MISMATCH", "LEGACY_PREFIX",
                "PREFIX_MISMATCH", "INVALID_KEY", "DELETE_CONFIRMED", "DELETE_FAILED", "DEADLINE_EXPIRED", "CLEANUP_FAILED")) {
            expected.put(reason, nonzero.getOrDefault(reason, 0));
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
    /** 空前缀、越界路径、头像和目录占位对象均不能触发删除。 */
    @Test void refusesInvalidPrefixAndSiblingObjects() throws Exception {
        var storage = mock(PortfolioFontStorage.class); when(storage.bucket()).thenReturn("test-bucket");
        var service = new PortfolioFontService(new PortfolioFontProperties(), mock(PortfolioFontSources.class), mock(PortfolioFontSubsetRunner.class), storage);
        var logger = (Logger) LoggerFactory.getLogger(PortfolioFontService.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            for (String prefix : new String[]{null, "", "WFUSER01/others/", "WFUSER01/others/fonts/"}) {
                service.cleanup(List.of("test-bucket|WFUSER01/others/fonts/1/a.woff"), prefix);
                assertCleanupCounts(logs, Map.of("INVALID_PREFIX", 1));
                logs.list.clear();
            }
            service.cleanup(List.of("test-bucket|WFUSER01/others/fonts/2/a.woff", "test-bucket|WFUSER01/others/avatar.png",
                    "test-bucket|WFUSER01/others/fonts/1/../a.woff", "test-bucket|WFUSER01/others/fonts/1/",
                    "null|WFUSER01/others/fonts/1/a.woff", "test-bucket|WFUSER01/protfolio/1/fonts/a.woff"), "WFUSER01/others/fonts/1/");
            assertCleanupCounts(logs, Map.of("PREFIX_MISMATCH", 2, "INVALID_KEY", 2, "BUCKET_MISSING", 1, "LEGACY_PREFIX", 1));
            verify(storage, never()).delete(anyString(), any());
        } finally { service.close(); logger.detachAppender(logs); logs.stop(); }
    }
}
