package com.jxc.wefolio.service.portfoliofont;

import org.junit.jupiter.api.Test;
import com.alibaba.fastjson2.JSON;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 异常后取证优先于恢复；锁存禁止未准入步骤而不抹掉已完成资源。 */
class PortfolioFontFailureEvidenceTest {
    /** 每项测试独立源文件与请求目录。 */
    @TempDir Path temporary;
    /** 初次取证成功不能替代异常之后的取证。 */
    @Test void collectsFreshEvidenceBeforeRecoveringCompletedGroups() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            fixture.generation("write_completion(specs[0],generate(specs[0]))\nos._exit(8)\n");
            clearInvocations(fixture.sources);
            assertThat(fixture.prepare().response().getAssets()).hasSize(1);
            var order = inOrder(fixture.sources, fixture.storage);
            order.verify(fixture.sources).verifyEnvironment(anyList(), any());
            order.verify(fixture.sources).verifyEnvironment(anyList(), any());
            order.verify(fixture.storage).upload(any(), anyString(), any());
        }
    }
    /** Python 父进程已退出时仍终止独立组中的写入者，再恢复安全完成组。 */
    @Test void stopsOrphanedWritersBeforeRecoveringCompletedGroups() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            Path marker = temporary.resolve("writer-pid");
            String child = "import os,time; from pathlib import Path; Path(" + JSON.toJSONString(marker.toString())
                    + ").write_text(str(os.getpid())); time.sleep(60)";
            fixture.generation("write_completion(specs[0],generate(specs[0]))\nimport subprocess,time\n"
                    + "subprocess.Popen([sys.executable,'-c'," + JSON.toJSONString(child) + "])\n"
                    + "while not Path(" + JSON.toJSONString(marker.toString()) + ").exists(): time.sleep(0.001)\n"
                    + "os._exit(8)\n");
            assertThat(fixture.prepare().response().getAssets()).hasSize(1);
            long pid = Long.parseLong(Files.readString(marker));
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            verify(fixture.storage, times(1)).upload(any(), anyString(), any());
        }
    }
    /** 两个进程的非零/超时路径均锁存实际源损坏，许可入口可用也不得上传。 */
    @Test void latchesEnvironmentFailureBeforeAnyPendingUpload() throws Exception {
        int index = 0;
        for (String phase : List.of("subset.py", "font_license.py")) {
            for (boolean timeout : List.of(false, true)) {
                try (var fixture = new PortfolioFontExecutionFixture(Files.createDirectory(temporary.resolve("case-" + index++)))) {
                    if (timeout) {
                        Process process = mock(Process.class);
                        when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
                            Path source = fixture.sources.getDirectory().resolve(fixture.sources.resolve("ALLURA", "gf-809e4d8b8d7e-r1")
                                    .getJSONObject("normal").getString("relativePath"));
                            Files.writeString(source, "damaged"); return false;
                        });
                        fixture.simulatedProcess = process; fixture.simulatedScript = phase;
                    } else {
                        String body = "write_completion(specs[0],generate(specs[0]))\nPath(specs[0]['source']).write_text('damaged')\nos._exit(8)\n";
                        if (phase.equals("subset.py")) fixture.generation(body); else fixture.license(body);
                    }
                    assertThat(fixture.prepare().response().getAssets()).isEmpty();
                    assertThat(fixture.sources.isReady()).isFalse();
                    assertThat(fixture.sources.diagnostics().get("reasonCodes").toString()).contains("SOURCE_UNAVAILABLE");
                    verify(fixture.storage, never()).upload(any(), anyString(), any());
                    clearInvocations(fixture.sources);
                    assertThat(fixture.prepare().response().getAssets()).isEmpty();
                    verify(fixture.sources, never()).verifyEnvironment(anyList(), any());
                }
            }
        }
    }
    /** 普通失败或超时无实际损坏证据时，只降级当前请求。 */
    @Test void keepsOrdinaryProcessFailureRequestScoped() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            fixture.generation("os._exit(7)\n");
            assertThat(fixture.prepare().response().getAssets()).isEmpty();
            assertThat(fixture.sources.isReady()).isTrue();
            Process timeout = mock(Process.class);
            when(timeout.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
            fixture.simulatedProcess = timeout; fixture.simulatedScript = "subset.py";
            assertThat(fixture.prepare().response().getAssets()).isEmpty();
            assertThat(fixture.sources.isReady()).isTrue();
        }
    }
    /** 可控时钟证明取证不越过总预算；缺证据不能锁存。 */
    @Test void reportsIncompleteEvidenceWithoutExtendingDeadline() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(PortfolioFontSubsetRunner.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            AtomicLong clock = new AtomicLong();
            Process process = mock(Process.class);
            when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
                clock.set(101_000_000); return false;
            });
            fixture.simulatedProcess = process; fixture.simulatedScript = "subset.py";
            try (var budget = new PortfolioFontBudget(100, clock::get)) {
                assertThat(budget.processRemaining()).isEqualTo(90);
                assertThatThrownBy(() -> fixture.generate(budget)).isInstanceOf(TimeoutException.class);
                assertThatThrownBy(budget::remaining).isInstanceOf(TimeoutException.class);
                verify(fixture.sources, times(2)).verifyEnvironment(anyList(), eq(budget));
                verify(fixture.storage, never()).upload(any(), anyString(), any());
                assertThat(fixture.sources.isReady()).isTrue();
                assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("reasonCode=ENVIRONMENT_EVIDENCE_INCOMPLETE"));
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    /** 第一个上传完成时并发锁存，第二个尚未准入的上传不能启动。 */
    @Test void stopsPendingUploadsAfterConcurrentLatch() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            when(fixture.storage.upload(any(), anyString(), any())).thenAnswer(call -> {
                fixture.sources.fail(PortfolioFontSources.Failure.TOOL_UNAVAILABLE);
                return "https://fonts.example/" + call.getArgument(1);
            });
            assertThat(fixture.prepare().response().getAssets()).hasSize(1);
            verify(fixture.storage, times(1)).upload(any(), anyString(), any());
            assertThat(fixture.sources.isReady()).isFalse();
        }
    }
    /** 已复用组及有效预算内完成上传的组在锁存后仍进入当前结果。 */
    @Test void retainsReusedAndRegisteredAssetsAfterLatch() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            var first = fixture.prepare();
            String stored = fixture.service.accept(fixture.snapshot, PortfolioFontPlan.from(fixture.config), first);
            fixture.snapshot.setDraftFontAssetsJson(stored);
            fixture.sources.fail(PortfolioFontSources.Failure.SOURCE_UNAVAILABLE);
            clearInvocations(fixture.storage);
            assertThat(fixture.prepare().response().getAssets()).hasSize(2);
            verify(fixture.storage, never()).upload(any(), anyString(), any());
            assertThat(fixture.sources.isReady()).isFalse();
        }
    }
}
