package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实 Java→Python→最终许可校验→存储准入链；工具路径由显式测试环境提供。 */
class PortfolioFontFinalLicenseTest {
    /** 每次测试独立本地源和工作目录。 */
    @TempDir Path directory;

    /** 坏 WOFF 即使子进程声明生成成功且 SHA 正确，仍不得上传或成为 READY。 */
    @Test void rejectsMutatedFinalWoffBeforeUploadAndKeepsSelection() throws Exception {
        String python = System.getenv("FONT_TEST_PYTHON");
        String harfbuzz = System.getenv("FONT_TEST_HARFBUZZ");
        assumeTrue(python != null && harfbuzz != null,
                "真实许可集成测试须设置 FONT_TEST_PYTHON 与 FONT_TEST_HARFBUZZ");
        Path sourceRoot = Path.of("src/main/resources/fonts").toAbsolutePath();
        JSONObject manifest = JSON.parseObject(Files.readString(sourceRoot.resolve("manifest.json")));
        JSONObject font = manifest.getJSONArray("fonts").stream().map(item -> (JSONObject) item)
                .filter(item -> "ALLURA".equals(item.getString("fontId"))).findFirst().orElseThrow();
        for (Object item : font.getJSONArray("sourceFiles")) {
            String relative = ((JSONObject) item).getString("relativePath");
            Files.createDirectories(directory.resolve(relative).getParent());
            Files.copy(sourceRoot.resolve(relative), directory.resolve(relative));
        }
        for (Object relative : font.getJSONArray("licensePaths")) {
            Files.copy(sourceRoot.resolve((String) relative), directory.resolve((String) relative));
        }
        Files.copy(sourceRoot.resolve("font_license.py"), directory.resolve("font_license.py"));
        Files.writeString(directory.resolve("subset.py"), mutationScript(sourceRoot));
        var properties = new PortfolioFontProperties();
        properties.setEnabled(true); properties.setPrepareBudgetMs(10000);
        properties.setPython(python); properties.setHarfbuzz(harfbuzz);
        var sources = mock(PortfolioFontSources.class);
        when(sources.resolve("ALLURA", font.getString("fontVersion"))).thenReturn(font);
        when(sources.getDirectory()).thenReturn(directory);
        when(sources.getBuildId()).thenReturn("test-build");
        when(sources.isReady()).thenReturn(true);
        var runner = new PortfolioFontSubsetRunner(sources, properties);
        var storage = mock(PortfolioFontStorage.class);
        when(storage.bucket()).thenReturn("test-bucket");
        when(storage.upload(any(), anyString(), any())).thenAnswer(call -> "https://fonts.example/" + call.getArgument(1));
        PortfolioConfigDto config = JSON.parseObject("""
                {"fonts":{"ALLURA":{"fontVersion":"%s"}},"components":[
                {"componentKey":"text","componentType":"TEXT_SECTION","config":{"fontId":"ALLURA","content":"Hello"}}]}
                """.formatted(font.getString("fontVersion")), PortfolioConfigDto.class);
        var plan = PortfolioFontPlan.from(config);
        String originalConfig = JSON.toJSONString(config);
        var snapshot = new PortfolioEntity(); snapshot.setId(1L); snapshot.setCurrentRevision(1);
        var service = new PortfolioFontService(properties, sources, runner, storage);
        try {
            // 先证明相同真实处理链可接受合法产物，避免负测因环境或输入无效而假绿。
            Files.writeString(directory.resolve("mutation.txt"), "valid");
            assertThat(service.prepare(snapshot, plan, "WF1234/others/fonts/1/").response().getAssets()).hasSize(1);
            verify(storage).upload(any(), anyString(), any());
            clearInvocations(storage);
            for (String mutation : List.of("0-missing", "0-truncated", "0-replaced", "13-missing", "13-truncated",
                    "13-replaced", "14-missing", "14-truncated", "14-replaced", "unreadable")) {
                Files.writeString(directory.resolve("mutation.txt"), mutation);
                Files.writeString(directory.resolve("generation-count.txt"), "");
                var prepared = service.prepare(snapshot, plan, "WF1234/others/fonts/1/");
                assertThat(prepared.response().getAssets()).as(mutation).isEmpty();
                assertThat(prepared.response().getUnavailable()).as(mutation).hasSize(1);
                assertThat(JSON.toJSONString(config)).isEqualTo(originalConfig);
                verify(storage, never()).upload(any(), anyString(), any());
                assertThat(Files.readAllLines(directory.resolve("generation-count.txt"))).containsExactly("generate");
            }
        } finally { service.close(); }
    }

    /** 测试专用子进程替身：真实生成后变异产物并更新 SHA，不能绕过第二次正式许可校验。 */
    private String mutationScript(Path sourceRoot) {
        return """
                import hashlib, json, sys
                from pathlib import Path
                sys.path.insert(0, %s)
                from subset import generate, write_completion
                from fontTools.ttLib import TTFont
                root = Path(__file__).parent
                mutation = (root / 'mutation.txt').read_text()
                specs = json.loads(Path(sys.argv[1]).read_text())
                results = []
                for spec in specs:
                    with (root / 'generation-count.txt').open('a') as count:
                        count.write('generate\\n')
                    result = generate(spec)
                    path = Path(spec['output'])
                    if mutation == 'unreadable':
                        path.write_bytes(b'not a font')
                    elif mutation != 'valid':
                        name_id, change = mutation.split('-')
                        name_id = int(name_id)
                        with TTFont(path, recalcTimestamp=False) as font:
                            if change == 'missing':
                                font['name'].names = [r for r in font['name'].names if r.nameID != name_id]
                            else:
                                for record in font['name'].names:
                                    if record.nameID == name_id:
                                        value = record.toUnicode()
                                        value = value[:len(value)//2] if change == 'truncated' else 'INVALID TEST LEGAL TEXT'
                                        record.string = value.encode(record.getEncoding())
                            font.save(path)
                    result['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
                    result['bytes'] = path.stat().st_size
                    write_completion(spec, result)
                    results.append(result)
                Path(sys.argv[2]).write_text(json.dumps(results))
                """.formatted(JSON.toJSONString(sourceRoot.toString()));
    }
}
