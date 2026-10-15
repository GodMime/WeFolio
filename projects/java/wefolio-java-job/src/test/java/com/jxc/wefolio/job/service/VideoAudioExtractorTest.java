package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoAudioProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 本机合成无用户信息的视频，实际运行 FFmpeg 并通过回环 HTTP 验证有界受控下载。 */
class VideoAudioExtractorTest {
    /** 可调整工具路径、时长及输出上限的独立测试配置。 */
    private final VideoAudioProperties properties = new VideoAudioProperties();
    /** 仅返回回环服务地址，不使用真实 COS 签名。 */
    private final COSClient cos = mock(COSClient.class);
    /** 执行真实下载、探测及音频提取的被测服务。 */
    private VideoAudioExtractor extractor;
    /** 检查真实 ffprobe 返回的音频编码信息。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 合成容器与损坏文件的测试隔离目录。 */
    @TempDir Path temporary;

    /** CI 可通过系统属性指定工具；本机常用 Linux 和 Homebrew 路径自动探测。 */
    @BeforeEach void setup() {
        properties.setFfmpegPath(binary("ffmpeg")); properties.setFfprobePath(binary("ffprobe"));
        assumeTrue(Files.isExecutable(Path.of(properties.getFfmpegPath())) && Files.isExecutable(Path.of(properties.getFfprobePath())),
                "实际音频集成测试需要 ffmpeg 和 ffprobe");
        CosProperties storage = new CosProperties(); storage.setBucketName("test-only-bucket");
        extractor = new VideoAudioExtractor(cos, storage, properties, mapper);
    }

    /** 开启配置时验证真实 WAV 编码能力；只运行本地十毫秒无声样本。 */
    @Test void enabledStartupPerformsActualWaveCapabilityCheck() {
        properties.setEnabled(true); properties.setApiKey("test-only-key"); assertDoesNotThrow(properties::validate);
        properties.setFfmpegPath("relative/ffmpeg"); assertThrows(IllegalStateException.class, properties::validate);
    }

    /** 下载、探测、完整提取和目录释放覆盖一次真实媒体链路，不调用 ASR。 */
    @Test void downloadsControlledVideoAndProducesMono16kPcmWave() throws Exception {
        byte[] input = video(true);
        AtomicInteger gets = new AtomicInteger();
        HttpServer server = server(input, gets);
        try {
            var prepared = extractor.prepare("OWNER/work/video/a.mp4", future());
            Path directory = prepared.directory();
            try (prepared) {
                assertTrue(prepared.probe().hasAudio()); assertTrue(prepared.probe().seconds() > 1);
                Path wav = extractor.extract(prepared, future());
                var wavInfo = mapper.readTree(command(List.of(properties.getFfprobePath(), "-v", "error", "-show_entries",
                        "stream=codec_name,sample_rate,channels", "-of", "json", wav.toString()))).path("streams").get(0);
                assertEquals("pcm_s16le", wavInfo.path("codec_name").asText());
                assertEquals("16000", wavInfo.path("sample_rate").asText()); assertEquals(1, wavInfo.path("channels").asInt());
                assertEquals(1, gets.get()); assertEquals(input.length, Files.size(prepared.video()));
            }
            assertFalse(Files.exists(directory));
        } finally { server.stop(0); }
    }

    /** 无音轨视频通过真实 ffprobe 识别，无需生成空 WAV 冒充有效音频。 */
    @Test void silentTracklessVideoReportsNoAudio() throws Exception {
        Path file = temporary.resolve("trackless.mp4"); Files.write(file, video(false));
        assertFalse(extractor.probe(file, future()).hasAudio());
    }

    /** 整秒 AAC 解码尾帧可能略超容器时长，预算提前保守覆盖，避免误拒普通视频。 */
    @Test void integerDurationAacPaddingFitsConservativeReservation() throws Exception {
        byte[] input = video(true, "1");
        HttpServer server = server(input, new AtomicInteger());
        try (var prepared = extractor.prepare("OWNER/work/video/a.mp4", future())) {
            assertEquals(2, VideoAudioExtractor.reservedSeconds(prepared.probe(), 600));
            assertTrue(Files.size(extractor.extract(prepared, future())) > 44);
        } finally { server.stop(0); }
    }

    /** 源文件声明超限在 GET 前拒绝，禁止读取任意目录或超期输入。 */
    @Test void sourceOwnershipAndSizeLimitsRejectBeforeDownload() throws Exception {
        AtomicInteger gets = new AtomicInteger(); HttpServer server = server(video(false), gets);
        try {
            assertThrows(IllegalStateException.class, () -> extractor.prepare("OTHER/../../etc/passwd", future()));
            properties.setMaxVideoBytes(1);
            assertThrows(IllegalStateException.class, () -> extractor.prepare("OWNER/work/video/a.mp4", future()));
            assertEquals(0, gets.get());
            assertThrows(IllegalStateException.class, () -> extractor.prepare("OWNER/work/video/a.mp4", Instant.now().minusSeconds(1)));
        } finally { server.stop(0); }
    }

    /** 历史视频可超过摘要的十进制百兆，音频 HEAD 准入仍遵守独立资源上限。 */
    @Test void audioSourceAcceptsHistoricalVideoAboveSummaryLimit() throws Exception {
        long historicalVideoBytes = 102_090_765L;
        AtomicInteger gets = new AtomicInteger();
        HttpServer server = server(new byte[1], gets, historicalVideoBytes);
        try {
            assertEquals(historicalVideoBytes, extractor.inspect("OWNER/work/video/a.mp4", future()).bytes());
            assertEquals(0, gets.get());
        } finally { server.stop(0); }
    }

    /** 真实时长超限直接失败，不通过 -t 截取前一段继续收费。 */
    @Test void excessiveActualDurationIsRejected() throws Exception {
        Path file = temporary.resolve("long.mp4"); Files.write(file, video(true)); properties.setMaxDurationSeconds(1);
        assertThrows(IllegalStateException.class, () -> extractor.probe(file, future()));
    }

    /** 损坏容器不能被当作无音轨成功；WAV 输出超限同样明确失败。 */
    @Test void corruptMediaAndWaveByteLimitNeverProduceSuccessfulEmptyText() throws Exception {
        Path corrupt = temporary.resolve("corrupt.mp4"); Files.writeString(corrupt, "invalid container");
        assertThrows(IllegalStateException.class, () -> extractor.probe(corrupt, future()));
        HttpServer server = server(video(true), new AtomicInteger());
        try (var prepared = extractor.prepare("OWNER/work/video/a.mp4", future())) {
            properties.setMaxWavBytes(64);
            assertThrows(IllegalStateException.class, () -> extractor.extract(prepared, future()));
        } finally { server.stop(0); }
    }

    /** 测试 COS 只返回由当前回环服务持有的签名地址，生产不开放端点覆写。 */
    private HttpServer server(byte[] data, AtomicInteger gets) throws Exception {
        return server(data, gets, data.length);
    }

    /** 可单独验证 HEAD 字节准入，避免为大小边界创建百兆测试文件。 */
    private HttpServer server(byte[] data, AtomicInteger gets, long sourceBytes) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
            exchange.getResponseHeaders().set("Content-Length", Long.toString(sourceBytes));
            if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); }
            else { gets.incrementAndGet(); exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); }
            exchange.close();
        });
        server.start();
        when(cos.generatePresignedUrl(eq("test-only-bucket"), anyString(), any(Date.class), any(HttpMethodName.class)))
                .thenReturn(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/").toURL());
        return server;
    }

    /** 低分辨率纯色与测试正弦波，不包含样本视频或真实用户音频。 */
    private byte[] video(boolean audio) throws Exception {
        return video(audio, "1.3");
    }
    /** 指定时长用于覆盖压缩音轨尾帧填充。 */
    private byte[] video(boolean audio, String seconds) throws Exception {
        Path output = temporary.resolve(audio ? "with-audio.mp4" : "without-audio.mp4");
        List<String> arguments = new ArrayList<>(List.of(properties.getFfmpegPath(), "-nostdin", "-v", "error", "-f", "lavfi",
                "-i", "color=c=black:s=16x16:r=10"));
        if (audio) { arguments.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000")); }
        arguments.addAll(List.of("-t", seconds, "-c:v", "mpeg4", "-c:a", "aac", "-y", output.toString()));
        command(arguments); return Files.readAllBytes(output);
    }
    /** 测试进程同样限时，便于发现工具缺失或阻塞。 */
    private byte[] command(List<String> arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); return process.getInputStream().readAllBytes(); }
        finally { process.destroyForcibly(); }
    }
    /** 显式路径优先，否则检测常见 Linux 和 Homebrew 安装位置。 */
    private static String binary(String name) {
        String override = System.getProperty("wefolio.test." + name);
        if (override != null) { return override; }
        return Files.isExecutable(Path.of("/usr/bin", name)) ? "/usr/bin/" + name : "/opt/homebrew/bin/" + name;
    }
    /** 给本地合成媒体步骤保留有限的测试期限。 */
    private Instant future() { return Instant.now().plusSeconds(30); }
}
