package com.jxc.wefolio.service.portfoliofont;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 完成记录必须独立于整批结果，并再次通过正式许可准入。 */
class PortfolioFontCompletionContractTest {
    /** 私有测试路径。 */
    @TempDir Path temporary;
    /** 首组已原子发布且环境正常时，异常退出不抹掉该组。 */
    @Test void preservesCompletedGroupAfterNonZeroExit() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            fixture.generation("write_completion(specs[0],generate(specs[0]))\nPath(sys.argv[2]).write_text('[broken')\nos._exit(9)\n");
            var response = fixture.prepare().response();
            assertThat(response.getAssets()).hasSize(1);
            assertThat(response.getUnavailable()).hasSize(1);
            assertThat(fixture.sources.isReady()).isTrue();
            verify(fixture.storage, times(1)).upload(any(), anyString(), any());
        }
    }
    /** 坏记录逐项失败，不能根据已落盘 WOFF 推断成功。 */
    @Test void rejectsMissingTruncatedOrMismatchedCompletionRecords() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            for (String mutation : List.of("missing", "truncated", "request", "group", "hash")) {
                fixture.generation("""
                        for spec in specs:
                            result=generate(spec)
                            write_completion(spec,result)
                        path=Path(specs[0]['completionPath'])
                        record=json.loads(path.read_text())
                        mode='%s'
                        if mode=='missing': path.unlink()
                        elif mode=='truncated': path.write_text('{')
                        else:
                            field={'request':'requestId','group':'groupId','hash':'sha256'}[mode]
                            record[field]='invalid'
                            path.write_text(json.dumps(record))
                        """.formatted(mutation));
                clearInvocations(fixture.storage);
                assertThat(fixture.prepare().response().getAssets()).as(mutation).hasSize(1);
                verify(fixture.storage, times(1)).upload(any(), anyString(), any());
            }
        }
    }
    /** 最终许可请求只包含可信原请求中的完成组，不能用全量输出计数。 */
    @Test void filtersLicenseRequestToCompletedGroups() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            fixture.generation("write_completion(specs[1],generate(specs[1]))\nos._exit(7)\n");
            fixture.license("""
                    assert len(specs)==1 and specs[0]['groupId']=='1'
                    original=json.loads(Path(sys.argv[1]).with_name('request.json').read_text())
                    assert specs[0]==original[1]
                    from font_license import validate_request_file
                    validate_request_file(sys.argv[1],sys.argv[2])
                    """);
            assertThat(fixture.prepare().response().getAssets()).hasSize(1);
            fixture.generation("os._exit(7)\n");
            fixture.license("Path(__file__).with_name('unexpected-license').touch()\n");
            assertThat(fixture.prepare().response().getAssets()).isEmpty();
            assertThat(Files.exists(temporary.resolve("unexpected-license"))).isFalse();
        }
    }
    /** 许可协议缺失、截断或少一项均不能猜测哪些组已通过。 */
    @Test void rejectsIncompleteLicenseResponse() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            for (String body : List.of("pass", "Path(sys.argv[2]).write_text('[broken')", "Path(sys.argv[2]).write_text('[{\"valid\":true}]')")) {
                fixture.license(body + "\n");
                assertThat(fixture.prepare().response().getAssets()).isEmpty();
                assertThat(fixture.sources.isReady()).isTrue();
            }
            verify(fixture.storage, never()).upload(any(), anyString(), any());
        }
    }
    /** 最终 WOFF 许可损坏且源环境完好时，只丢弃损坏组。 */
    @Test void rejectsOnlyInvalidLicenseGroupWhenEnvironmentHealthy() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            fixture.generation("""
                    from fontTools.ttLib import TTFont
                    for index,spec in enumerate(specs):
                        result=generate(spec)
                        if index==0:
                            with TTFont(spec['output'],recalcTimestamp=False) as font:
                                font['name'].names=[r for r in font['name'].names if r.nameID!=13]
                                font.save(spec['output'])
                            result['sha256']=hashlib.sha256(Path(spec['output']).read_bytes()).hexdigest()
                        write_completion(spec,result)
                    """);
            assertThat(fixture.prepare().response().getAssets()).hasSize(1);
            assertThat(fixture.sources.isReady()).isTrue();
            verify(fixture.storage, times(1)).upload(any(), anyString(), any());
        }
    }
    /** 单文件超限在生成侧拒绝，累计超限保留预算内第一个合法产物。 */
    @Test void distinguishesSingleAndTotalOutputLimits() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(PortfolioFontSubsetRunner.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            List<PortfolioFontSubsetRunner.Output> outputs;
            try (var budget = new PortfolioFontBudget(10000)) { outputs = fixture.generate(budget); }
            assertThat(outputs).hasSize(2);
            long max = Math.max(Files.size(outputs.get(0).file()), Files.size(outputs.get(1).file()));
            fixture.properties.setMaxOutputBytes(max);
            assertThat(fixture.prepare().response().getAssets()).hasSize(1);
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("reasonCode=TOTAL_OUTPUT_TOO_LARGE"));
            logs.list.clear();
            fixture.properties.setMaxOutputBytes(1);
            clearInvocations(fixture.storage);
            assertThat(fixture.prepare().response().getAssets()).isEmpty();
            verify(fixture.storage, never()).upload(any(), anyString(), any());
            assertThat(fixture.sources.isReady()).isTrue();
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("reasonCode=OUTPUT_TOO_LARGE"));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    /** 第二组上传已启动，但回包到达时预算耗尽，只保留此前登记的第一组。 */
    @Test void discardsLateResultsAfterBudgetExpiry() throws Exception {
        try (var fixture = new PortfolioFontExecutionFixture(temporary)) {
            AtomicLong clock = new AtomicLong(); fixture.clock = clock::get;
            AtomicLong uploads = new AtomicLong();
            when(fixture.storage.upload(any(), anyString(), any())).thenAnswer(call -> {
                if (uploads.incrementAndGet() == 2) { clock.set(11_000_000_000L); }
                return "https://fonts.example/" + call.getArgument(1);
            });
            var prepared = fixture.prepare();
            assertThat(uploads).hasValue(2);
            assertThat(prepared.response().getAssets()).hasSize(1);
            assertThat(prepared.response().getUnavailable()).hasSize(1);
            var plan = PortfolioFontPlan.from(fixture.config);
            fixture.snapshot.setDraftFontAssetsJson(fixture.service.accept(fixture.snapshot, plan, prepared));
            assertThat(PortfolioFontManifests.project(plan, fixture.service.publish(fixture.snapshot, plan)).getAssets()).hasSize(1);
            assertThat(fixture.sources.isReady()).isTrue();
        }
    }
}
