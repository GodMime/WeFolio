package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 视频音频转写的独立开关、资源和收费预算，不复用作品集模型配置。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "video-audio")
public class VideoAudioProperties {
    /** 官方同步接口与模型固定，防止凭据被配置到其它主机。 */
    public static final String ENDPOINT = "https://tokenhub.tencentmaas.com/v1/wand/asrproxy/sync_transcribe";
    /** 当前经实测可用的腾讯云语音识别模型。 */
    public static final String MODEL = "hy-asr-3.0-preview";
    /** 依赖与迁移就绪后显式开启。 */
    private boolean enabled;
    /** 与现有 TokenHub 调用共用环境密钥，禁止输出对象全部属性。 */
    private String apiKey;
    /** 只能使用管理员配置的绝对可执行路径。 */
    private String ffmpegPath = "/usr/bin/ffmpeg";
    /** 音轨探测工具的管理员绝对路径。 */
    private String ffprobePath = "/usr/bin/ffprobe";
    /** 音频各阶段总预算与分阶段上限，单位秒。 */
    private int totalTimeoutSeconds = 180;
    /** 单次源对象下载的最长秒数。 */
    private int downloadTimeoutSeconds = 60;
    /** 单次本地音轨探测的最长秒数。 */
    private int probeTimeoutSeconds = 10;
    /** WAV 提取及输出验证共同使用的最长秒数。 */
    private int extractTimeoutSeconds = 30;
    /** 一次同步 ASR 请求的最长秒数。 */
    private int httpTimeoutSeconds = 120;
    /** 当前业务视频最长十分钟。 */
    private int maxDurationSeconds = 600;
    /** 单实例只并行处理一个音频，跨实例按作品数据库会话锁互斥。 */
    private int concurrency = 1;
    /** 原视频、音频、编码、临时目录和响应的硬字节边界。 */
    private long maxVideoBytes = 100L * 1024 * 1024;
    /** 提取后的完整 PCM WAV 字节上限。 */
    private long maxWavBytes = 25L * 1024 * 1024;
    /** 发送给 ASR 的裸 Base64 字节上限。 */
    private long maxBase64Bytes = 40L * 1024 * 1024;
    /** 单件原视频与中间音频占用的临时磁盘上限。 */
    private long maxTempBytes = 160L * 1024 * 1024;
    /** ASR 响应正文的增量读取字节上限。 */
    private int maxResponseBytes = 2 * 1024 * 1024;
    /** 全文不截断，超限作为可重试失败。 */
    private int maxTextCodePoints = 65_536;
    /** 全文编码为 UTF-8 后的最大存储字节数。 */
    private int maxTextBytes = 256 * 1024;
    /** 每次显式历史执行或审核扫描独立保留预算，不是持久账本。 */
    private int maxRequestsPerRun = 100;
    /** 每次运行可预留的累计音频秒数，独立于截图预算。 */
    private long maxAudioSecondsPerRun = 3600;

    /** 开启时立即验证依赖与边界，防止运行到付费阶段才发现部署缺项。 */
    @PostConstruct
    public void validate() {
        if (!enabled) { return; }
        if (apiKey == null || apiKey.isBlank()) { throw new IllegalStateException("视频音频转写未配置 TokenHub 密钥"); }
        for (String executable : new String[]{ffmpegPath, ffprobePath}) {
            if (executable == null || !Path.of(executable).isAbsolute()
                    || !Files.isRegularFile(Path.of(executable)) || !Files.isExecutable(Path.of(executable))) {
                throw new IllegalStateException("视频音频转写需要可执行的 ffmpeg 和 ffprobe 绝对路径");
            }
            verifyExecutable(executable);
        }
        if (totalTimeoutSeconds <= 0 || downloadTimeoutSeconds <= 0 || probeTimeoutSeconds <= 0
                || extractTimeoutSeconds <= 0 || httpTimeoutSeconds <= 0 || concurrency <= 0
                || maxDurationSeconds <= 0 || maxDurationSeconds > 600 || maxVideoBytes <= 0
                || maxVideoBytes > 100L * 1024 * 1024 || maxWavBytes <= 0 || maxWavBytes > 25L * 1024 * 1024
                || maxBase64Bytes <= 0 || maxBase64Bytes > 40L * 1024 * 1024
                || maxTempBytes < maxVideoBytes + maxWavBytes || maxResponseBytes <= 0
                || maxTextCodePoints <= 0 || maxTextBytes <= 0 || maxRequestsPerRun <= 0 || maxAudioSecondsPerRun <= 0) {
            throw new IllegalStateException("视频音频转写资源边界或预算非法");
        }
        verifyWaveSupport();
    }

    /** 绝对路径存在也可能缺少动态库，实际执行版本命令作为启用预检。 */
    private void verifyExecutable(String executable) {
        verifyProcess(List.of(executable, "-version"));
    }

    /** 用十毫秒无内容 PCM 在本地验证 WAV 编码及 ffprobe 解码，避免开启后才暴露裁剪版依赖问题。 */
    private void verifyWaveSupport() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("wefolio-audio-preflight-");
            Path raw = directory.resolve("input.pcm");
            Path wav = directory.resolve("output.wav");
            Files.write(raw, new byte[320]);
            verifyProcess(List.of(ffmpegPath, "-nostdin", "-v", "error", "-protocol_whitelist", "file",
                    "-f", "s16le", "-ar", "16000", "-ac", "1", "-i", raw.toString(),
                    "-c:a", "pcm_s16le", "-f", "wav", "-y", wav.toString()));
            if (Files.size(wav) <= 44) { throw new IllegalStateException("视频音频工具不支持完整 WAV 编码"); }
            verifyProcess(List.of(ffprobePath, "-v", "error", "-protocol_whitelist", "file", wav.toString()));
        } catch (IOException exception) {
            throw new IllegalStateException("视频音频工具 WAV 能力预检失败");
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("input.pcm"));
                    Files.deleteIfExists(directory.resolve("output.wav"));
                    Files.deleteIfExists(directory);
                } catch (IOException exception) { throw new IllegalStateException("视频音频预检临时文件清理失败"); }
            }
        }
    }

    /** 启用预检的每条本地命令最多运行五秒。 */
    private void verifyProcess(List<String> arguments) {
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("视频音频工具版本探测失败");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频音频工具预检已中断");
        } catch (IOException exception) {
            throw new IllegalStateException("视频音频工具无法运行");
        } finally { if (process != null) { process.destroyForcibly(); } }
    }
}
