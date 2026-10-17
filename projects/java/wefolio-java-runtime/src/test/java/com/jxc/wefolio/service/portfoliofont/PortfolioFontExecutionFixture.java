package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.List;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** 真实源、生成和许可校验；仅在进程启动与远端存储边界注入故障。 */
final class PortfolioFontExecutionFixture implements AutoCloseable {
    /** 本次测试的临时路径、真实工具配置和源状态。 */
    final Path root;
    final PortfolioFontProperties properties = new PortfolioFontProperties();
    final PortfolioFontSources sources;
    final PortfolioFontStorage storage = mock(PortfolioFontStorage.class);
    final PortfolioFontSubsetRunner runner;
    final PortfolioFontService service;
    /** 测试请求的固定配置与快照。 */
    final PortfolioConfigDto config;
    final PortfolioEntity snapshot = new PortfolioEntity();
    /** 只替换本次外部进程入口，不篡改正式脚本以绕过摘要检测。 */
    Path generationDriver;
    Path licenseDriver;
    /** 可控外部进程返回，用于不依赖睡眠的超时测试。 */
    Process simulatedProcess;
    String simulatedScript;
    /** 可控单调时钟用于上传回包晚于截止的真实服务验收。 */
    LongSupplier clock = System::nanoTime;

    /** 提取真实资源，锁定真实工具链并建立请求；不访问外部服务器。 */
    PortfolioFontExecutionFixture(Path root) throws Exception {
        this.root = root;
        String python = System.getenv("FONT_TEST_PYTHON"), harfbuzz = System.getenv("FONT_TEST_HARFBUZZ");
        assumeTrue(python != null && harfbuzz != null, "必须设置真实字体工具路径");
        properties.setPython(python); properties.setHarfbuzz(harfbuzz);
        properties.setDirectory(root.resolve("sources").toString()); properties.setEnabled(true);
        properties.setPrepareBudgetMs(10000);
        sources = spy(new PortfolioFontSources(properties));
        var fingerprint = sources.fingerprint(Files.readAllBytes(Path.of("src/main/resources/fonts/manifest.json")));
        properties.setToolchainHash(fingerprint.get("toolchainHash")); properties.setExpectedBuildId(fingerprint.get("buildId"));
        sources.initialize(); sources.selfTestPassed();
        if (!sources.isReady()) { throw new IllegalStateException("测试真实字体源未就绪"); }
        runner = new PortfolioFontSubsetRunner(sources, properties) {
            @Override Process startProcess(String script, Path input, Path result, Path log) throws Exception {
                if (simulatedProcess != null && script.equals(simulatedScript)) { return simulatedProcess; }
                Path replacement = script.equals("subset.py") ? generationDriver : licenseDriver;
                if (replacement == null) { return super.startProcess(script, input, result, log); }
                return startIsolatedProcess(properties.getPython(), replacement, input, result, log);
            }
        };
        when(storage.bucket()).thenReturn("test-bucket");
        when(storage.upload(any(), anyString(), any())).thenAnswer(call -> "https://fonts.example/" + call.getArgument(1));
        service = new PortfolioFontService(properties, sources, runner, storage) {
            @Override PortfolioFontBudget createBudget(long millis) { return new PortfolioFontBudget(millis, clock); }
        };
        config = JSON.parseObject("""
                {"fonts":{"ALLURA":{"fontVersion":"gf-809e4d8b8d7e-r1"},"MANROPE":{"fontVersion":"gf-809e4d8b8d7e-r1"}},
                 "components":[{"componentKey":"a","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hello"}},
                 {"componentKey":"b","componentType":"TEXT_SECTION","config":{"fontId":"MANROPE","content":"World"}}]}
                """, PortfolioConfigDto.class);
        snapshot.setId(1L); snapshot.setCurrentRevision(1);
    }
    /** 生成侧替身调用正式算法，再按测试场景退出或破坏协议。 */
    void generation(String body) throws Exception {
        generationDriver = root.resolve("generation-driver.py");
        Files.writeString(generationDriver, prelude() + body);
    }
    /** 许可侧替身仍可调用正式验证器，测试返回契约故障。 */
    void license(String body) throws Exception {
        licenseDriver = root.resolve("license-driver.py");
        Files.writeString(licenseDriver, prelude() + body);
    }
    /** 进程只访问本次请求和真实本地资源。 */
    String prelude() {
        return "import sys,json,os,hashlib\nfrom pathlib import Path\nsys.path.insert(0," + JSON.toJSONString(sources.getDirectory().toString())
                + ")\nfrom subset import generate,write_completion\nspecs=json.loads(Path(sys.argv[1]).read_text())\n";
    }
    /** 走真实同步服务准入到上传边界。 */
    PortfolioFontService.Prepared prepare() { return service.prepare(snapshot, PortfolioFontPlan.from(config), "WFUSER01/others/fonts/1/"); }
    /** 供预算、协议断言直接执行一次真实 runner。 */
    List<PortfolioFontSubsetRunner.Output> generate(PortfolioFontBudget budget) throws Exception {
        return runner.generate(PortfolioFontPlan.from(config).groups(), Files.createTempDirectory(root, "request-"), budget);
    }
    /** 释放同步执行器。 */
    @Override public void close() { service.close(); }
}
