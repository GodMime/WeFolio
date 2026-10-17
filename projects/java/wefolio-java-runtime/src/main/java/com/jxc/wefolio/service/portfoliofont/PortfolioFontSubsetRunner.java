package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.message.PortfolioFontMessage;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.PortfolioFontProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/** 参数文件驱动的批量 HarfBuzz/FontTools 子集执行器。 */
@Slf4j
@Service
public class PortfolioFontSubsetRunner {
    /** 节点源文件仓库。 */
    private final PortfolioFontSources sources;
    /** 本地执行限制。 */
    private final PortfolioFontProperties properties;
    /** 生成脚本相对路径。 */
    private static final String SCRIPT = "subset.py";
    /** 设置进程组早于目标脚本执行；脚本抛异常也不能留下未登记的写入进程。 */
    private static final String PROCESS_GROUP_LAUNCHER = "import os,sys,runpy; os.setsid(); target=sys.argv.pop(1); sys.path.insert(0,os.path.dirname(target)); runpy.run_path(target,run_name='__main__')";
    /** 最终格式。 */
    private static final String SUFFIX = ".woff";

    /** 本次完整启动自检实际通过的逻辑组数。 */
    @Getter private int selfTestGroups;

    /** 本次最终校验通过的本地产物。 */
    public record Output(PortfolioFontPlan.Group group, Path file, int weight, String subsetHash) { }

    /** 注入源仓库与执行配置。 */
    public PortfolioFontSubsetRunner(PortfolioFontSources sources, PortfolioFontProperties properties) {
        this.sources = sources; this.properties = properties;
    }

    /** 启动执行真实最小生成，失败仅降级字体，不阻断 Runtime。 */
    @PostConstruct
    public void selfTest() {
        if (!sources.canSelfTest()) { return; }
        try { runSelfTest(); sources.selfTestPassed(); }
        catch (PortfolioFontSources.EnvironmentFailure exception) { /* 环境边界已经记录具体原因。 */ }
        catch (Exception exception) {
            log.error("字体启动生成自检失败", exception);
            sources.fail(PortfolioFontSources.Failure.SELF_TEST_FAILED);
        }
    }

    /** 完整十二组真实生成验证使用独立三十秒预算。 */
    private void runSelfTest() throws Exception {
        var catalog = sources.catalog();
        @SuppressWarnings("unchecked") var fonts = (List<Map<String, Object>>) catalog.get("fonts");
        List<PortfolioFontPlan.Group> groups = new ArrayList<>();
        for (var font : fonts) {
            String id = (String) font.get("fontId");
            String version = (String) font.get("fontVersion");
            for (int weight : List.of(400, 700)) {
                groups.add(new PortfolioFontPlan.Group(id, version, weight, PortfolioFontPlan.NORMAL_STYLE,
                        List.of(65, 20013), PortfolioFontPlan.digest(id + weight)));
            }
        }
        Path work;
        try { work = Files.createTempDirectory(sources.getDirectory(), "selftest-"); }
        catch (IOException exception) { sources.fail(PortfolioFontSources.Failure.WORK_DIRECTORY_UNAVAILABLE); return; }
        try (var budget = new PortfolioFontBudget(30000)) {
            int count = generate(groups, work, budget).size();
            if (count != groups.size()) { throw new IllegalStateException(PortfolioFontMessage.STARTUP_SELF_TEST_FAILED); }
            selfTestGroups = count;
        }
        finally { removeWorkDirectory(work); }
    }

    /** 内部失败类别只用于诊断，不修改对外字体降级协议。 */
    enum Reason { FINAL_VALIDATION_FAILED, OUTPUT_TOO_LARGE, TOTAL_OUTPUT_TOO_LARGE, OUTPUT_CHANGED,
        COMPLETION_INVALID, PROCESS_FAILED, PROCESS_TIMEOUT, LICENSE_RESULTS_INCOMPLETE, ENVIRONMENT_EVIDENCE_INCOMPLETE }
    /** 请求记录协议字段；与 Python 逐组原子完成协议对应。 */
    private static final String REQUEST_ID = "requestId", GROUP_ID = "groupId", COMPLETION_PATH = "completionPath";
    private static final String STATUS = "status", COMPLETE = "COMPLETE", FAILED = "FAILED", SHA256 = "sha256", BYTES = "bytes";
    private static final String PROTOCOL_VERSION = "protocolVersion", REASON_CODE = "reasonCode", VALID = "valid";
    private static final String LICENSE_SCRIPT = "font_license.py";
    /** 小型完成记录的读取上限，防止损坏协议扩大内存开销。 */
    private static final int MAX_COMPLETION_BYTES = 4096;
    /** 已有完整记录，但仍须独立许可复核的候选组。 */
    private record Completed(Output output, JSONObject spec, String sha256) { }

    /** 一次生成，异常后先取证；完成记录不是最终上传凭据。 */
    List<Output> generate(List<PortfolioFontPlan.Group> groups, Path work, PortfolioFontBudget budget) throws Exception {
        try { return generateOnce(groups, work, budget); }
        catch (IOException exception) {
            evidence(groups, budget);
            if (!Files.isDirectory(work) || !Files.isWritable(work)) { sources.fail(PortfolioFontSources.Failure.WORK_DIRECTORY_UNAVAILABLE); }
            throw exception;
        }
    }

    /** 同组仅启动一次生成；异常汇总缺失不妨碍安全读取独立完成记录。 */
    private List<Output> generateOnce(List<PortfolioFontPlan.Group> groups, Path work, PortfolioFontBudget budget) throws Exception {
        evidence(groups, budget);
        requireReady();
        String requestId = UUID.randomUUID().toString();
        List<JSONObject> specs = new ArrayList<>();
        List<Output> outputs = new ArrayList<>();
        for (var group : groups) {
            JSONObject font = sources.resolve(group.fontId(), group.fontVersion());
            if (font == null) { continue; }
            int weight = PortfolioFontWeights.physical(group.fontId(), group.fontVersion(), group.fontWeight());
            JSONObject face = font.getJSONObject(weight == 700 ? "bold" : "normal");
            String relative = face.getString("relativePath");
            JSONObject source = font.getJSONArray("sourceFiles").stream().map(item -> (JSONObject) item)
                    .filter(item -> relative.equals(item.getString("relativePath"))).findFirst().orElseThrow();
            String subsetHash = PortfolioFontPlan.digest(group.demandHash() + sources.getBuildId() + weight);
            Path output = work.resolve(subsetHash + SUFFIX);
            JSONObject spec = JSONObject.of("source", sources.getDirectory().resolve(relative).toString(),
                    "sourceSha256", source.getString("sha256"), "language", font.getString("language"),
                    "codepoints", group.codepoints(), "weight", weight, "variable", face.containsKey("instantiateAxes"),
                    "harfbuzz", properties.getHarfbuzz(), "subsetHash", subsetHash, "output", output.toString(),
                    "maxBytes", properties.getMaxOutputBytes());
            spec.put("licensePaths", font.getJSONArray("licensePaths").stream()
                    .map(path -> sources.getDirectory().resolve((String) path).toString()).toList());
            spec.put(REQUEST_ID, requestId); spec.put(GROUP_ID, String.valueOf(specs.size()));
            spec.put(COMPLETION_PATH, work.resolve("complete-" + specs.size() + ".json").toString());
            specs.add(spec); outputs.add(new Output(group, output, weight, subsetHash));
        }
        if (specs.isEmpty()) { return List.of(); }
        Path input = work.resolve("request.json"), result = work.resolve("result.json"), processLog = work.resolve("process.log");
        Files.writeString(input, JSON.toJSONString(specs));
        boolean normalExit = runProcess(SCRIPT, input, result, processLog, groups, budget);
        List<Completed> completed = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            budget.remaining();
            JSONObject spec = specs.get(index);
            JSONObject record = readCompletion(spec);
            if (record == null) { diagnostic(outputs.get(index), Reason.COMPLETION_INVALID); continue; }
            if (FAILED.equals(record.getString(STATUS))) {
                diagnostic(outputs.get(index), Reason.OUTPUT_TOO_LARGE.name().equals(record.getString(REASON_CODE))
                        ? Reason.OUTPUT_TOO_LARGE : Reason.FINAL_VALIDATION_FAILED);
                continue;
            }
            Output output = outputs.get(index);
            if (!Files.isRegularFile(output.file()) || Files.isSymbolicLink(output.file())
                    || Files.size(output.file()) != record.getLongValue(BYTES)
                    || !PortfolioFontSources.fileHash(output.file(), budget).equals(record.getString(SHA256))) {
                diagnostic(output, Reason.OUTPUT_CHANGED); continue;
            }
            completed.add(new Completed(output, spec, record.getString(SHA256)));
        }
        // 进程异常已取证；正常退出的组失败也须取得异常之后的新证据。
        if (normalExit && completed.size() != specs.size()) { evidence(groups, budget); }
        if (completed.isEmpty()) { return List.of(); }
        requireReady();
        Path filtered = work.resolve("completed-request.json"), licenseResult = work.resolve("license-results.json");
        Files.writeString(filtered, JSON.toJSONString(completed.stream().map(Completed::spec).toList()));
        if (!runProcess(LICENSE_SCRIPT, filtered, licenseResult, processLog, groups, budget)) { return List.of(); }
        List<JSONObject> licenses;
        try {
            licenses = JSON.parseArray(Files.readString(licenseResult), JSONObject.class);
            if (licenses == null || licenses.size() != completed.size() || licenses.stream().anyMatch(item -> item == null
                    || !(item.get(VALID) instanceof Boolean))) { throw new IllegalArgumentException(); }
        } catch (IOException | RuntimeException exception) {
            evidence(groups, budget);
            log.warn("字体许可返回协议不完整: reasonCode={}", Reason.LICENSE_RESULTS_INCOMPLETE); return List.of();
        }
        if (licenses.stream().anyMatch(item -> !item.getBooleanValue(VALID))) { evidence(groups, budget); }
        requireReady();
        long bytes = 0;
        List<Output> validated = new ArrayList<>();
        for (int index = 0; index < completed.size(); index++) {
            Completed candidate = completed.get(index); Output output = candidate.output();
            if (!licenses.get(index).getBooleanValue(VALID)) { diagnostic(output, Reason.FINAL_VALIDATION_FAILED); continue; }
            if (!PortfolioFontSources.fileHash(output.file(), budget).equals(candidate.sha256())) {
                diagnostic(output, Reason.OUTPUT_CHANGED); continue;
            }
            long size = Files.size(output.file());
            if (size > properties.getMaxOutputBytes()) { diagnostic(output, Reason.OUTPUT_TOO_LARGE); continue; }
            if (size > properties.getMaxOutputBytes() - bytes) { diagnostic(output, Reason.TOTAL_OUTPUT_TOO_LARGE); continue; }
            bytes += size; validated.add(output);
        }
        budget.remaining(); requireReady();
        return validated;
    }

    /** 只读取可信请求指定的小型记录；坏记录不让同批其它有效组失效。 */
    private JSONObject readCompletion(JSONObject spec) {
        try {
            Path path = Path.of(spec.getString(COMPLETION_PATH));
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > MAX_COMPLETION_BYTES) { return null; }
            JSONObject record = JSON.parseObject(Files.readString(path));
            if (record == null || record.getIntValue(PROTOCOL_VERSION) != 1
                    || !Objects.equals(record.getString(REQUEST_ID), spec.getString(REQUEST_ID))
                    || !Objects.equals(record.getString(GROUP_ID), spec.getString(GROUP_ID))) { return null; }
            if (FAILED.equals(record.getString(STATUS))) { return record; }
            if (!COMPLETE.equals(record.getString(STATUS)) || record.getString(SHA256) == null
                    || !record.getString(SHA256).matches("[a-f0-9]{64}") || !(record.get(BYTES) instanceof Number)
                    || record.getLongValue(BYTES) <= 0) { return null; }
            return record;
        } catch (IOException | RuntimeException exception) { return null; }
    }

    /** 启动、等待、终止与取证始终在同一总预算内；非零退出只允许恢复生成侧完成组。 */
    private boolean runProcess(String script, Path input, Path result, Path logFile,
                               List<PortfolioFontPlan.Group> groups, PortfolioFontBudget budget) throws Exception {
        requireReady(); budget.processRemaining();
        Process process;
        try { process = startProcess(script, input, result, logFile); }
        catch (Exception exception) { evidence(groups, budget); throw exception; }
        try {
            budget.attachGroup(process);
            if (!process.waitFor(budget.processRemaining(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException(PortfolioFontMessage.BUDGET_EXPIRED);
            }
        } catch (InterruptedException | TimeoutException exception) {
            budget.terminateProcess();
            if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            try {
                // 停止写入后才取证；等待也占用原预算，不能延长同步接口截止时间。
                budget.stopWriters();
                evidence(groups, budget);
            } catch (IOException | InterruptedException | TimeoutException incomplete) {
                if (incomplete instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                log.warn("字体环境取证未完成: reasonCode={}", Reason.ENVIRONMENT_EVIDENCE_INCOMPLETE);
            }
            log.warn("字体进程超时或取消: reasonCode={}", Reason.PROCESS_TIMEOUT);
            throw exception;
        }
        try { budget.stopWriters(); }
        catch (IOException | InterruptedException | TimeoutException incomplete) {
            if (incomplete instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            // 停止未确认仍需尝试环境取证，但即使环境完好也不能恢复完成组。
            try { evidence(groups, budget); }
            finally { log.warn("字体写入停止未确认: reasonCode={}", Reason.ENVIRONMENT_EVIDENCE_INCOMPLETE); }
            throw incomplete;
        }
        if (process.exitValue() != 0) {
            evidence(groups, budget);
            log.warn("字体进程异常退出: reasonCode={}, exit={}", Reason.PROCESS_FAILED, process.exitValue()); return false;
        }
        return true;
    }

    /** 异常后取证不能被开始时的成功结果缓存；超时不锁存。 */
    private void evidence(List<PortfolioFontPlan.Group> groups, PortfolioFontBudget budget) throws TimeoutException {
        try { sources.verifyEnvironment(groups, budget); }
        catch (TimeoutException exception) {
            log.warn("字体环境取证未完成: reasonCode={}", Reason.ENVIRONMENT_EVIDENCE_INCOMPLETE); throw exception;
        }
    }
    /** 启动自检有独立准入；普通请求不得绕过运行期锁存。 */
    private void requireReady() {
        if (!sources.isReady() && !sources.canSelfTest()) { throw new IllegalStateException(PortfolioFontMessage.NODE_NOT_READY); }
    }
    /** 字体内部分类不向用户暴露路径与工具细节。 */
    private void diagnostic(Output output, Reason reason) {
        log.warn("字体组不可交付: fontId={}, reasonCode={}", output.group().fontId(), reason);
    }

    /** 外部进程边界；测试可替换启动器而不修改受摘要保护的正式源文件。 */
    Process startProcess(String script, Path input, Path result, Path processLog) throws Exception {
        return startIsolatedProcess(properties.getPython(), sources.getDirectory().resolve(script), input, result, processLog);
    }
    /** macOS/Linux 启动独立 POSIX 会话，父进程异常退出后仍能按组终止所有子进程。 */
    static Process startIsolatedProcess(String python, Path script, Path input, Path result, Path processLog) throws IOException {
        return new ProcessBuilder(python, "-c", PROCESS_GROUP_LAUNCHER, script.toString(), input.toString(), result.toString())
                .redirectErrorStream(true).redirectOutput(processLog.toFile()).start();
    }

    /** 清理本次私有临时目录；不扫描或删除任何远端对象。 */
    static void removeWorkDirectory(Path directory) {
        if (directory == null) { return; }
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        } catch (Exception ignored) { /* 本地临时文件删除失败不影响业务结果。 */ }
    }
}
