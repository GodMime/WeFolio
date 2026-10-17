package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.config.PortfolioFontProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.io.TempDir;
import java.util.function.Consumer;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assumptions;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;

/** 字体环境故障只降级字体，不阻断原有业务启动。 */
class PortfolioFontReadinessTest {
    /** 所有提取与工具夹具均在测试私有目录。 */
    @TempDir Path temporary;
    /** 禁用安装仍提供包与已固定工具链的构建身份，不执行不存在的工具。 */
    @Test void disabledNodeReportsBuildIdentityWithoutLaunchingTools() {
        var properties = new PortfolioFontProperties();
        properties.setPython("/nonexistent/wefolio-python");
        properties.setHarfbuzz("/nonexistent/wefolio-hb-subset");
        properties.setToolchainHash("a".repeat(64));
        var sources = new PortfolioFontSources(properties);
        sources.initialize();
        assertThat(sources.getBuildId()).matches("[a-f0-9]{64}");
        assertThat(sources.diagnostics()).containsEntry("reasonCodes", List.of("DISABLED"));
        assertThat(sources.isSourcesValidated()).isFalse();
        assertThat(sources.getDirectory()).isNull();
    }
    /** 启用但工具缺失时初始化正常返回，目录明确不可用。 */
    @Test void missingToolDoesNotPreventRuntimeStartup() {
        var properties = new PortfolioFontProperties();
        properties.setEnabled(true);
        properties.setPython("/nonexistent/wefolio-python");
        properties.setHarfbuzz("/nonexistent/wefolio-hb-subset");
        var sources = new PortfolioFontSources(properties);
        assertThatCode(sources::initialize).doesNotThrowAnyException();
        assertThat(sources.isReady()).isFalse();
        assertThat(sources.catalog()).containsEntry("available", false);
        var runner = new PortfolioFontSubsetRunner(sources, properties);
        assertThatCode(runner::selfTest).doesNotThrowAnyException();
    }

    /** 损坏字段、重复身份、越界路径及无效字重必须在启动阶段被拒绝。 */
    @Test void malformedManifestDegradesAtInitialization() throws Exception {
        List<Consumer<JSONObject>> mutations = List.of(
                root -> root.getJSONArray("fonts").getJSONObject(0).remove("sample"),
                root -> root.getJSONArray("fonts").add(root.getJSONArray("fonts").getJSONObject(0)),
                root -> root.getJSONArray("fonts").getJSONObject(0).getJSONArray("sourceFiles").getJSONObject(0).put("relativePath", "../outside.ttf"),
                root -> root.getJSONArray("fonts").getJSONObject(0).getJSONArray("sourceFiles").getJSONObject(0).put("sha256", "not-sha"),
                root -> root.getJSONArray("fonts").getJSONObject(0).put("language", "unknown"),
                root -> root.getJSONArray("fonts").getJSONObject(0).getJSONObject("normal").getJSONObject("instantiateAxes").put("wght", 999));
        for (var mutation : mutations) {
            JSONObject manifest = JSON.parseObject(Files.readString(Path.of("src/main/resources/fonts/manifest.json")));
            mutation.accept(manifest);
            var sources = new PortfolioFontSources(new PortfolioFontProperties()) {
                @Override InputStream openResource(String relative) throws Exception {
                    return "manifest.json".equals(relative) ? new ByteArrayInputStream(JSON.toJSONBytes(manifest)) : super.openResource(relative);
                }
            };
            assertThatCode(sources::initialize).doesNotThrowAnyException();
            assertThat(sources.isReady()).isFalse();
            assertThat(sources.diagnostics()).containsEntry("reasonCodes", List.of("MANIFEST_INVALID"));
            assertThatCode(sources::catalog).doesNotThrowAnyException();
        }
    }

    /** 并发故障原子累计且同一原因只通知一次；告警异常也不能冒泡破坏旧业务。 */
    @Test void concurrentFaultsAndBrokenAlertDoNotReopenOrBreakTheNode() throws Exception {
        var alerts = mock(PortfolioFontAlertService.class);
        when(alerts.notifyFailure(any(), any())).thenThrow(new IllegalStateException("local test"));
        var sources = new PortfolioFontSources(new PortfolioFontProperties(), alerts);
        sources.initialize();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Object>> jobs = IntStream.range(0, 100).mapToObj(index -> (Callable<Object>) () -> {
                sources.fail(index % 2 == 0 ? PortfolioFontSources.Failure.TOOL_UNAVAILABLE : PortfolioFontSources.Failure.SOURCE_UNAVAILABLE);
                sources.selfTestPassed();
                assertThat(sources.diagnostics().get("ready")).isEqualTo(false);
                return null;
            }).toList();
            for (var future : pool.invokeAll(jobs)) { future.get(); }
        }
        assertThat((List<?>) sources.diagnostics().get("reasonCodes")).extracting(Object::toString).containsExactlyInAnyOrder("TOOL_UNAVAILABLE", "SOURCE_UNAVAILABLE");
        verify(alerts, times(1)).notifyFailure(null, "TOOL_UNAVAILABLE");
        verify(alerts, times(1)).notifyFailure(null, "SOURCE_UNAVAILABLE");
        assertThat(sources.catalog()).containsEntry("available", false);
    }

    /** 真实十二组生成后才就绪；源、脚本、许可、工具与工作目录故障均锁存。 */
    @Test void realPrevalidationAndRuntimeFaultsStayLatchedUntilExplicitReinitialization() throws Exception {
        String python = System.getenv("FONT_TEST_PYTHON"), harfbuzz = System.getenv("FONT_TEST_HARFBUZZ");
        Assumptions.assumeTrue(python != null && harfbuzz != null, "须提供真实字体测试工具路径");
        Path wrapper = temporary.resolve("python");
        Files.writeString(wrapper, "#!/bin/sh\nexec '" + python.replace("'", "'\\''") + "' \"$@\"\n");
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        var properties = new PortfolioFontProperties();
        properties.setPython(wrapper.toString()); properties.setHarfbuzz(harfbuzz);
        properties.setDirectory(temporary.resolve("fonts").toString()); properties.setValidationEnabled(true);
        var sources = new PortfolioFontSources(properties);
        var fingerprint = sources.fingerprint(Files.readAllBytes(Path.of("src/main/resources/fonts/manifest.json")));
        properties.setToolchainHash(fingerprint.get("toolchainHash")); properties.setExpectedBuildId(fingerprint.get("buildId"));
        sources.initialize();
        assertThat(sources.isSourcesValidated()).isTrue();
        assertThat(sources.isReady()).isFalse();
        assertThat(sources.diagnostics()).containsEntry("reasonCodes", List.of("VALIDATING"));
        var runner = new PortfolioFontSubsetRunner(sources, properties);
        runner.selfTest();
        assertThat(sources.isReady()).isTrue();
        assertThat(sources.catalog()).containsEntry("available", false);
        var font = sources.resolve("ALLURA", "gf-809e4d8b8d7e-r1");
        var group = new PortfolioFontPlan.Group("ALLURA", "gf-809e4d8b8d7e-r1", 400, "NORMAL", List.of(65), "test");
        for (String relative : List.of(font.getJSONObject("normal").getString("relativePath"), "subset.py", font.getJSONArray("licensePaths").getString(0))) {
            Path file = sources.getDirectory().resolve(relative);
            byte[] original = Files.readAllBytes(file);
            Files.writeString(file, "corrupt");
            assertThatThrownBy(() -> sources.verifyEnvironment(List.of(group))).isInstanceOf(PortfolioFontSources.EnvironmentFailure.class);
            assertThat(sources.diagnostics()).containsEntry("reasonCodes", List.of("SOURCE_UNAVAILABLE"));
            Files.write(file, original);
            sources.verifyEnvironment(List.of(group));
            runner.selfTest();
            assertThat(sources.isReady()).isFalse();
            sources.initialize(); runner.selfTest();
            assertThat(sources.isReady()).isTrue();
        }
        assertThat(wrapper.toFile().setExecutable(false)).isTrue();
        assertThatThrownBy(() -> sources.verifyEnvironment(List.of(group))).isInstanceOf(PortfolioFontSources.EnvironmentFailure.class);
        assertThat(sources.diagnostics()).containsEntry("reasonCodes", List.of("TOOL_UNAVAILABLE"));
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        sources.verifyEnvironment(List.of(group));
        assertThat(sources.isReady()).isFalse();
        sources.initialize(); runner.selfTest();
        Path moved = temporary.resolve("moved");
        Files.move(sources.getDirectory(), moved); Files.writeString(sources.getDirectory(), "not a directory");
        assertThatThrownBy(() -> sources.verifyEnvironment(List.of(group))).isInstanceOf(PortfolioFontSources.EnvironmentFailure.class);
        assertThat(sources.diagnostics()).containsEntry("reasonCodes", List.of("WORK_DIRECTORY_UNAVAILABLE"));
        Files.delete(sources.getDirectory()); Files.move(moved, sources.getDirectory());
        sources.verifyEnvironment(List.of(group));
        assertThat(sources.isReady()).isFalse();
    }
}
