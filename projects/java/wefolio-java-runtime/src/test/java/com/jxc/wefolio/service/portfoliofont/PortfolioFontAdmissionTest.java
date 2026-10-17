package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 节点降级的生成准入边界，普通请求失败不改变节点状态。 */
class PortfolioFontAdmissionTest {
    /** 测试工作根目录。 */
    @TempDir Path directory;
    /** 两次请求使用相同固定版本的独立作品集。 */
    private PortfolioFontPlan plan() {
        return PortfolioFontPlan.from(JSON.parseObject("""
                {"fonts":{"ALLURA":{"fontVersion":"gf-809e4d8b8d7e-r1"}},"components":[
                {"componentKey":"t","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hi"}}]}
                """, PortfolioConfigDto.class));
    }
    /** 构造请求快照。 */
    private PortfolioEntity entity(long id) { var entity = new PortfolioEntity(); entity.setId(id); entity.setCurrentRevision(1); return entity; }
    /** 锁存同时阻断排队生成和尚未开始的上传。 */
    @Test void queuedAndUnuploadedRequestsStopAfterLatch() throws Exception {
        var properties = new PortfolioFontProperties(); properties.setEnabled(true); properties.setConcurrency(1); properties.setPrepareBudgetMs(5000);
        var sources = mock(PortfolioFontSources.class); var runner = mock(PortfolioFontSubsetRunner.class); var storage = mock(PortfolioFontStorage.class);
        when(sources.resolve(anyString(), anyString())).thenReturn(JSON.parseObject("{}"));
        when(sources.getDirectory()).thenReturn(directory); when(storage.bucket()).thenReturn("bucket");
        var ready = new AtomicBoolean(true); var running = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondAdmission = new CountDownLatch(1);
        when(sources.isReady()).thenAnswer(invocation -> { if (Thread.currentThread().getName().equals("queued-font")) secondAdmission.countDown(); return ready.get(); });
        var plan = plan(); Path output = directory.resolve("valid.woff"); Files.writeString(output,"validated");
        when(runner.generate(anyList(), any(), any())).thenAnswer(invocation -> {
            running.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(new PortfolioFontSubsetRunner.Output(plan.groups().getFirst(), output,400,"hash"));
        });
        when(storage.upload(any(), anyString(), any())).thenReturn("https://font.example/valid.woff");
        var service = new PortfolioFontService(properties,sources,runner,storage);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> service.prepare(entity(1),plan,PortfolioFontWrite.prefix("WF",1L)));
            assertThat(running.await(2,TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> { Thread.currentThread().setName("queued-font"); return service.prepare(entity(2),plan,PortfolioFontWrite.prefix("WF",2L)); });
            assertThat(secondAdmission.await(2,TimeUnit.SECONDS)).isTrue();
            ready.set(false); release.countDown();
            assertThat(first.get(3,TimeUnit.SECONDS).response().getAssets()).isEmpty();
            verify(storage,never()).upload(any(),anyString(),any());
            assertThat(second.get(3,TimeUnit.SECONDS).response().getAssets()).isEmpty();
            verify(runner,times(1)).generate(anyList(),any(),any());
        } finally { release.countDown(); service.close(); }
    }
    /** COS 网络失败和普通预算超时只降级本请求，不关闭整节点。 */
    @Test void requestFailuresDoNotChangeNodeReadiness() throws Exception {
        var properties = new PortfolioFontProperties(); properties.setEnabled(true); properties.setPrepareBudgetMs(300);
        var sources = mock(PortfolioFontSources.class); var runner = mock(PortfolioFontSubsetRunner.class); var storage = mock(PortfolioFontStorage.class);
        when(sources.isReady()).thenReturn(true); when(sources.resolve(anyString(),anyString())).thenReturn(JSON.parseObject("{}"));
        when(sources.getDirectory()).thenReturn(directory); when(storage.bucket()).thenReturn("bucket");
        var plan = plan(); Path output = directory.resolve("valid.woff"); Files.writeString(output,"validated");
        when(runner.generate(anyList(),any(),any())).thenReturn(List.of(new PortfolioFontSubsetRunner.Output(plan.groups().getFirst(),output,400,"hash")));
        when(storage.upload(any(),anyString(),any())).thenThrow(new java.io.IOException("network"));
        var service = new PortfolioFontService(properties,sources,runner,storage);
        try {
            assertThat(service.prepare(entity(1),plan,PortfolioFontWrite.prefix("WF",1L)).response().getAssets()).isEmpty();
            when(runner.generate(anyList(),any(),any())).thenAnswer(invocation -> { Thread.sleep(1000); return List.of(); });
            assertThat(service.prepare(entity(2),plan,PortfolioFontWrite.prefix("WF",2L)).response().getAssets()).isEmpty();
            verify(sources,never()).fail(any()); assertThat(sources.isReady()).isTrue();
        } finally { service.close(); }
    }
}
