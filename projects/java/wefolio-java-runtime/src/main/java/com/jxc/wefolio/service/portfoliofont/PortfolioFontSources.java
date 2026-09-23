package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.message.PortfolioFontMessage;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.PortfolioFontProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.io.File;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 随包源字体目录与启动提取；请求不下载或解压源字体。 */
@Slf4j
@Service
public class PortfolioFontSources {
    /** 包内目录前缀。 */
    private static final String PREFIX = "fonts/";
    /** 包内固定源清单。 */
    private static final String MANIFEST = "manifest.json";
    /** 生成脚本及完整许可证辅助模块。 */
    private static final List<String> SCRIPTS = List.of("subset.py", "font_license.py");
    /** 就绪状态的固定原因码。 */
    private static final String DISABLED = "DISABLED";
    private static final String VALIDATING = "VALIDATING";
    /** 随包声明与摘要校验模式。 */
    private static final String HASH_PATTERN = "[a-f0-9]{64}";
    private static final String SYNTHETIC_FROM_NORMAL = "SYNTHETIC_FROM_NORMAL";
    /** 固定版本字重映射必须与源清单一致。 */
    private JSONObject physicalWeights;
    /** 提取后脚本和许可证须继续匹配包内原始资源。 */
    private final Map<String, String> resourceHashes = new LinkedHashMap<>();
    /** 字体部署配置。 */
    private final PortfolioFontProperties properties;
    /** 故障转换时使用独立配置通知。 */
    private final PortfolioFontAlertService alerts;
    /** 字体目录按声明順序提供。 */
    private final Map<String, JSONObject> fonts = new LinkedHashMap<>();
    /** 本节点不可变源目录。 */
    @Getter private Path directory;
    /** 源文件及工具链指纹。 */
    @Getter private String buildId;
    /** 原子保存就绪及故障原因，所有读入口共用此快照。 */
    private final AtomicReference<Readiness> readiness = new AtomicReference<>(new Readiness(false, List.of(DISABLED)));
    /** 源校验通过后才允许执行完整生成自检。 */
    @Getter private boolean sourcesValidated;
    /** 不可变诊断快照，恢复仅发生于初始化及完整自检边界。 */
    private record Readiness(boolean ready, List<String> reasons) { }
    /** 明确的节点环境故障原因。 */
    public enum Failure { MANIFEST_INVALID, TOOL_UNAVAILABLE, SOURCE_UNAVAILABLE, WORK_DIRECTORY_UNAVAILABLE,
        BUILD_MISMATCH, SELF_TEST_FAILED, CONFIG_INVALID }
    /** 节点故障携带原因码，不从异常文案推断。 */
    public static final class EnvironmentFailure extends IllegalStateException {
        /** 非敏感故障类别。 */
        private final Failure reason;
        /** 保留内部异常供日志诊断。 */
        EnvironmentFailure(Failure reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
    }

    /** 目录、准入与健康查询读取同一状态。 */
    public boolean isReady() { return readiness.get().ready(); }
    /** 原子降级；任何普通请求成功均不得自动恢复。 */
    public void fail(Failure reason) {
        Readiness previous;
        Readiness next;
        do {
            previous = readiness.get();
            if (previous.reasons().contains(reason.name())) { return; }
            var reasons = new LinkedHashSet<String>();
            previous.reasons().stream().filter(value -> !DISABLED.equals(value) && !VALIDATING.equals(value)).forEach(reasons::add);
            reasons.add(reason.name());
            next = new Readiness(false, List.copyOf(reasons));
        } while (!readiness.compareAndSet(previous, next));
        if (alerts != null) {
            try { alerts.notifyFailure(buildId, reason.name()); }
            catch (Exception exception) { log.error("字体告警边界失败: reasonCode=ALERT_DELIVERY_FAILED"); }
        }
        log.error("字体节点不可用: reasonCode={}", reason);
    }
    /** 仅处于初始化校验阶段才允许启动生成自检，故障后普通调用不能恢复。 */
    boolean canSelfTest() { return sourcesValidated && readiness.get().reasons().equals(List.of(VALIDATING)); }
    /** 完整源/工具与真实生成自检通过后，仅启动阶段可调用。 */
    void selfTestPassed() {
        Readiness current = readiness.get();
        if (sourcesValidated && current.reasons().equals(List.of(VALIDATING))) {
            readiness.compareAndSet(current, new Readiness(true, List.of()));
        }
    }
    /** 不含本地路径、工具输出或密钥的健康投影。 */
    public Map<String, Object> diagnostics() {
        Readiness state = readiness.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled()); result.put("ready", state.ready());
        result.put("buildId", buildId); result.put("reasonCodes", state.reasons());
        return result;
    }

    /** 注入节点配置。 */
    public PortfolioFontSources(PortfolioFontProperties properties) { this(properties, null); }
    /** 生产注入独立故障通知边界。 */
    @Autowired
    public PortfolioFontSources(PortfolioFontProperties properties, PortfolioFontAlertService alerts) {
        this.properties = properties; this.alerts = alerts;
    }

    /** 启动读取目录，启用时校验工具并流式提取源文件。 */
    @PostConstruct
    public void initialize() {
        sourcesValidated = false;
        directory = null; buildId = null; resourceHashes.clear();
        readiness.set(new Readiness(false, List.of(VALIDATING)));
        try { initializeSources(); }
        catch (EnvironmentFailure failure) { if (failure.reason == Failure.MANIFEST_INVALID) { fonts.clear(); } fail(failure.reason); }
        catch (Exception exception) { fonts.clear(); fail(Failure.MANIFEST_INVALID); }
    }

    /** 字体初始化异常全部由公共启动边界隔离。 */
    private void initializeSources() throws Exception {
        byte[] manifest;
        try (InputStream input = openResource(MANIFEST)) { manifest = input.readAllBytes(); }
        JSONObject root = JSON.parseObject(manifest);
        try (InputStream input = openResource("physical-weights.json")) { physicalWeights = JSON.parseObject(input.readAllBytes()); }
        if (root == null || root.getJSONArray("fonts") == null || root.getJSONArray("fonts").isEmpty() || physicalWeights == null
                || root.getIntValue("formatVersion") != 1 || root.getIntValue("fontCount") != root.getJSONArray("fonts").size()) {
            throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
        }
        fonts.clear();
        for (Object item : root.getJSONArray("fonts")) {
            JSONObject font = (JSONObject) item;
            validateEntry(font);
            if (fonts.putIfAbsent(font.getString("fontId"), font) != null) { throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null); }
        }
        if (properties.getToolchainHash() != null && !properties.getToolchainHash().matches(HASH_PATTERN)) {
            throw new EnvironmentFailure(Failure.CONFIG_INVALID, null);
        }
        if (!properties.isEnabled() && !properties.isValidationEnabled()) {
            if (properties.getToolchainHash() != null && properties.getToolchainHash().matches(HASH_PATTERN)) {
                buildId = resourceFingerprint(manifest, properties.getToolchainHash()).get("buildId");
                if (properties.getExpectedBuildId() != null && !properties.getExpectedBuildId().equals(buildId)) {
                    throw new EnvironmentFailure(Failure.BUILD_MISMATCH, null);
                }
            }
            readiness.set(new Readiness(false, List.of(DISABLED))); return;
        }
        if (properties.getConcurrency() < 1 || properties.getPrepareBudgetMs() < 1 || properties.getDeleteBudgetMs() < 1 || properties.getMaxOutputBytes() < 1
                || properties.getDirectory() == null || properties.getDirectory().isBlank()
                || properties.getPython() == null || properties.getPython().isBlank()
                || properties.getHarfbuzz() == null || properties.getHarfbuzz().isBlank()) {
            throw new EnvironmentFailure(Failure.CONFIG_INVALID, null);
        }
        Map<String, String> fingerprint = fingerprint(manifest);
        buildId = fingerprint.get("buildId");
        if (!Objects.equals(fingerprint.get("toolchainHash"), properties.getToolchainHash())
                || !Objects.equals(buildId, properties.getExpectedBuildId())) {
            throw new EnvironmentFailure(Failure.BUILD_MISMATCH, null);
        }
        directory = Path.of(properties.getDirectory(), buildId).toAbsolutePath().normalize();
        try { Files.createDirectories(directory); }
        catch (Exception exception) { throw new EnvironmentFailure(Failure.WORK_DIRECTORY_UNAVAILABLE, exception); }
        for (String script : SCRIPTS) { extract(script, null); }
        for (JSONObject font : fonts.values()) {
            for (Object file : font.getJSONArray("sourceFiles")) {
                JSONObject source = (JSONObject) file;
                extract(source.getString("relativePath"), source.getString("sha256"));
            }
            for (Object license : font.getJSONArray("licensePaths")) { extract((String) license, null); }
        }
        sourcesValidated = true;
    }

    /** 离线及启动共用的资源与工具链摘要算法。 */
    public Map<String, String> fingerprint(byte[] manifest) throws Exception {
        String tools = command(List.of(properties.getHarfbuzz(), "--version"))
                + command(List.of(properties.getPython(), "-c", "import sys,fontTools,zlib;print(sys.version);print(fontTools.__version__);print(zlib.ZLIB_VERSION)"));
        String toolHash = PortfolioFontPlan.digest(tools);
        return resourceFingerprint(manifest, toolHash);
    }

    /** 包内资源身份可独立计算，关闭生成时不启动外部进程。 */
    private Map<String, String> resourceFingerprint(byte[] manifest, String toolHash) throws Exception {
        StringBuilder identity = new StringBuilder(new String(manifest, StandardCharsets.UTF_8));
        var files = new ArrayList<>(SCRIPTS);
        files.add("physical-weights.json");
        for (Object item : JSON.parseObject(manifest).getJSONArray("fonts")) {
            JSONObject font = (JSONObject) item;
            for (Object license : font.getJSONArray("licensePaths")) { files.add((String) license); }
            for (Object itemSource : font.getJSONArray("sourceFiles")) {
                JSONObject source = (JSONObject) itemSource;
                try (InputStream input = openResource(source.getString("relativePath"))) {
                    if (!Objects.equals(source.getString("sha256"), streamHash(input))) {
                        throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, null);
                    }
                } catch (EnvironmentFailure failure) { throw failure; }
                catch (Exception exception) { throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, exception); }
            }
        }
        for (String file : files) {
            try (InputStream input = openResource(file)) {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                resourceHashes.put(file, hash);
                identity.append(file).append(hash);
            }
        }
        return Map.of("toolchainHash", toolHash, "buildId", PortfolioFontPlan.digest(identity + toolHash));
    }

    /** 资源读取边界保持包内相对路径，便于用受损资源夹具验证启动降级。 */
    InputStream openResource(String relative) throws Exception {
        validateRelative(relative);
        return new ClassPathResource(PREFIX + relative).getInputStream();
    }

    /** 从最终可执行 JAR 读取资源，不依赖服务启动报错取得指纹。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && (args.length != 6 || !"--verify".equals(args[2]))) {
            throw new IllegalArgumentException(PortfolioFontMessage.INVALID_OFFLINE_ARGUMENTS);
        }
        var properties = new PortfolioFontProperties(); properties.setPython(args[0]); properties.setHarfbuzz(args[1]);
        if (args.length == 2) {
            try (InputStream input = new ClassPathResource(PREFIX + MANIFEST).getInputStream()) {
                System.out.println(JSON.toJSONString(new PortfolioFontSources(properties).fingerprint(input.readAllBytes())));
            }
            return;
        }
        long started = System.nanoTime();
        properties.setValidationEnabled(true); properties.setDirectory(args[3]);
        properties.setToolchainHash(args[4]); properties.setExpectedBuildId(args[5]);
        var sources = new PortfolioFontSources(properties);
        sources.initialize();
        var runner = new PortfolioFontSubsetRunner(sources, properties);
        runner.selfTest();
        var result = new LinkedHashMap<String, Object>(sources.diagnostics());
        result.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        result.put("selfTestGroups", runner.getSelfTestGroups());
        System.out.println(JSON.toJSONString(result));
        if (!sources.isReady()) { System.exit(2); }
    }

    /** 启动期拒绝缺失身份、越界路径或损坏的固定版本元数据。 */
    private void validateEntry(JSONObject font) {
        for (String field : List.of("fontId", "fontVersion", "displayName", "languageLabel", "sample", "license", "language")) {
            if (font.getString(field) == null || font.getString(field).isBlank()) { throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null); }
        }
        if (!font.getString("fontId").matches("[A-Z][A-Z0-9_]{0,63}")
                || !font.getString("fontVersion").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
        }
        if (font.getJSONArray("sourceFiles") == null || font.getJSONArray("sourceFiles").isEmpty()
                || font.getJSONArray("licensePaths") == null || font.getJSONArray("licensePaths").isEmpty()
                || font.getJSONObject("normal") == null || font.getJSONObject("bold") == null) {
            throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
        }
        JSONObject weights = physicalWeights.getJSONObject(font.getString("fontId") + ":" + font.getString("fontVersion"));
        if (!List.of("en", "zh-Hans").contains(font.getString("language")) || !"OFL-1.1".equals(font.getString("license"))
                || weights == null || weights.getIntValue("400") != 400 || weights.getIntValue("700") != (hasBold(font) ? 700 : 400)) {
            throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
        }
        for (String face : List.of("normal", "bold")) {
            JSONObject axes = font.getJSONObject(face).getJSONObject("instantiateAxes");
            if (axes != null && (axes.size() != 1 || axes.getIntValue("wght") != ("normal".equals(face) ? 400 : 700))) {
                throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
            }
        }
        var paths = new HashSet<String>();
        for (Object item : font.getJSONArray("sourceFiles")) {
            JSONObject source = (JSONObject) item;
            String path = source.getString("relativePath"); validateRelative(path);
            if (!paths.add(path) || source.getString("sha256") == null || !source.getString("sha256").matches("[a-f0-9]{64}")) {
                throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
            }
        }
        for (Object path : font.getJSONArray("licensePaths")) { validateRelative((String) path); }
        if (!paths.contains(font.getJSONObject("normal").getString("relativePath"))
                || (hasBold(font) && !paths.contains(font.getJSONObject("bold").getString("relativePath")))
                || (!hasBold(font) && !SYNTHETIC_FROM_NORMAL.equals(font.getJSONObject("bold").getString("mode")))) {
            throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null);
        }
    }

    /** 包内资源仅允许普通相对路径。 */
    private static void validateRelative(String relative) {
        if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute()
                || relative.contains("..") || relative.contains("\\")) { throw new EnvironmentFailure(Failure.MANIFEST_INVALID, null); }
    }

    /** 请求实际访问前复核本节点环境；明确环境失败会关闭后续生成准入。 */
    public void verifyEnvironment(List<PortfolioFontPlan.Group> groups) {
        try (var budget = new PortfolioFontBudget(30000)) { verifyEnvironment(groups, budget); }
        catch (TimeoutException exception) { throw new IllegalStateException(PortfolioFontMessage.ENVIRONMENT_EVIDENCE_INCOMPLETE, exception); }
    }

    /** 实际访问前及异常后的新取证共用算法；预算取消不等同环境损坏。 */
    void verifyEnvironment(List<PortfolioFontPlan.Group> groups, PortfolioFontBudget budget) throws TimeoutException {
        try {
            budget.remaining();
            requireExecutable(properties.getPython()); requireExecutable(properties.getHarfbuzz());
            if (directory == null || !Files.isDirectory(directory) || !Files.isWritable(directory)) {
                throw new EnvironmentFailure(Failure.WORK_DIRECTORY_UNAVAILABLE, null);
            }
            for (var resource : resourceHashes.entrySet()) {
                budget.remaining();
                // 固定物理映射只在包内读取；生成脚本和许可在本地实际使用前重验。
                if ("physical-weights.json".equals(resource.getKey())) { continue; }
                Path path = safeResolve(resource.getKey());
                if (!Files.isRegularFile(path) || !Files.isReadable(path) || !resource.getValue().equals(fileHash(path, budget))) {
                    throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, null);
                }
            }
            var checked = new HashSet<String>();
            for (var group : groups) {
                budget.remaining();
                JSONObject font = resolve(group.fontId(), group.fontVersion());
                if (font == null) { continue; }
                for (Object item : font.getJSONArray("sourceFiles")) {
                    JSONObject source = (JSONObject) item; String path = source.getString("relativePath");
                    if (checked.add(path) && (!Files.isRegularFile(safeResolve(path)) || !Files.isReadable(safeResolve(path))
                            || !source.getString("sha256").equals(fileHash(safeResolve(path), budget)))) {
                        throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, null);
                    }
                }
            }
        } catch (TimeoutException exception) { throw exception; }
        catch (EnvironmentFailure exception) { fail(exception.reason); throw exception; }
        catch (Exception exception) { fail(Failure.SOURCE_UNAVAILABLE); throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, exception); }
    }

    /** 分块摘要在每次读取前后检查预算，异常后不沿用旧成功缓存。 */
    static String fileHash(Path path, PortfolioFontBudget budget) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        budget.remaining();
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                budget.remaining(); digest.update(buffer, 0, count);
            }
        }
        budget.remaining();
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 拒绝本地提取目录中的符号链接跳出固定资源边界。 */
    private Path safeResolve(String relative) {
        validateRelative(relative);
        Path target = directory.resolve(relative).normalize();
        if (!target.startsWith(directory)) { throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, null); }
        for (Path path = target; path != null && !path.equals(directory); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) { throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, null); }
        }
        return target;
    }

    /** 支持固定绝对路径及 PATH 命令，检查消失或不可执行的工具。 */
    private static void requireExecutable(String command) {
        if (command != null && command.contains("/")) {
            if (Files.isRegularFile(Path.of(command)) && Files.isExecutable(Path.of(command))) { return; }
        } else if (command != null) {
            for (String folder : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
                Path candidate = Path.of(folder, command);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { return; }
            }
        }
        throw new EnvironmentFailure(Failure.TOOL_UNAVAILABLE, null);
    }

    /** 目录仅返回公开能力，隐藏本地路径。 */
    public Map<String, Object> catalog() {
        var entries = fonts.values().stream().map(font -> {
            Map<String, Object> entry = new LinkedHashMap<>(Map.of(
                "fontId", font.getString("fontId"), "fontVersion", font.getString("fontVersion"),
                "displayName", font.getString("displayName"), "languageLabel", font.getString("languageLabel"),
                "sample", font.getString("sample"), "weights", hasBold(font) ? List.of(400, 700) : List.of(400),
                "license", font.getString("license")));
            if (font.getString("previewImageUrl") != null && !font.getString("previewImageUrl").isBlank()) {
                entry.put("previewImageUrl", font.getString("previewImageUrl"));
            }
            return entry;
        }).toList();
        return Map.of("capabilityVersion", 1, "available", isReady() && properties.isEnabled(), "fonts", entries);
    }

    /** 只解析固定版本；未知或缺版本不映射到最新版。 */
    public JSONObject resolve(String id, String version) {
        JSONObject font = fonts.get(id);
        return font != null && Objects.equals(font.getString("fontVersion"), version) ? font : null;
    }

    /** 当前源版本是否具备真实 700 字重。 */
    public static boolean hasBold(JSONObject font) { return font.getJSONObject("bold").containsKey("relativePath"); }

    /** 对源文件流式求摘要，避免常驻内存保存完整字库。 */
    public static String fileHash(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) { return streamHash(input); }
    }

    /** 包内和提取后文件共用有界内存摘要计算。 */
    private static String streamHash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 校验临时文件后原子替换节点本地源，永不暴露半写文件。 */
    private void extract(String relative, String expectedHash) throws Exception {
        Path target = safeResolve(relative);
        if (expectedHash != null && Files.isRegularFile(target) && expectedHash.equals(fileHash(target))) { return; }
        Path temp;
        try { Files.createDirectories(target.getParent()); temp = Files.createTempFile(target.getParent(), "font-", ".tmp"); }
        catch (Exception exception) { throw new EnvironmentFailure(Failure.WORK_DIRECTORY_UNAVAILABLE, exception); }
        try {
            try (InputStream input = openResource(relative)) { Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING); }
            if (expectedHash != null && !expectedHash.equals(fileHash(temp))) { throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, null); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (EnvironmentFailure failure) { throw failure; }
        catch (Exception exception) { throw new EnvironmentFailure(Failure.SOURCE_UNAVAILABLE, exception); }
        finally { Files.deleteIfExists(temp); }
    }

    /** 启动检查有独立超时，不使用 shell 解释器。 */
    private String command(List<String> args) throws Exception {
        Process process = null;
        try {
            requireExecutable(args.getFirst());
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) { throw new EnvironmentFailure(Failure.TOOL_UNAVAILABLE, null); }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (EnvironmentFailure failure) { throw failure; }
        catch (Exception exception) {
            if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new EnvironmentFailure(Failure.TOOL_UNAVAILABLE, exception);
        } finally { if (process != null) { process.destroyForcibly(); } }
    }
}
