package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoAudioProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 受控 COS 原视频下载和本地音频提取；临时资源只存于当前执行目录。 */
@Component
@Slf4j
public class VideoAudioExtractor {
    /** 下载和处理只支持作品目录中的单个本地容器。 */
    private static final String SOURCE_PATTERN = "[A-Za-z0-9_-]+/work/video/[^/]+";
    /** 每件处理隔离目录与目录内固定文件名。 */
    private static final String DIRECTORY_PREFIX = "wefolio-video-audio-", VIDEO_FILE = "source.video", WAV_FILE = "audio.wav";
    /** COS 条件请求头及 ffprobe 容器元数据字段。 */
    private static final String ETAG = "ETag", IF_MATCH = "If-Match", FORMAT = "format", DURATION = "duration";
    /** ffprobe 音轨列表的固定字段和值。 */
    private static final String STREAMS = "streams", CODEC_TYPE = "codec_type", AUDIO = "audio";
    /** 仅开放视频容器解码，避免输入驱动任意协议或播放列表。 */
    private static final String CONTAINERS = "mov,matroska,webm,avi,mpegts,mpeg,flv,ogg";
    /** 子进程输出和单笔分配都有限；输出超限即失败，不落无界日志。 */
    private static final int PROCESS_OUTPUT_BYTES = 64 * 1024;
    /** FFmpeg 单笔内存分配限制，单位字节。 */
    private static final String MAX_ALLOCATION = "67108864";
    /** 只用于本地生成受控存储桶对象签名。 */
    private final COSClient cosClient;
    /** 视频来源所在的受控 COS 存储桶配置。 */
    private final CosProperties cos;
    /** 下载、解码和临时资源边界。 */
    private final VideoAudioProperties properties;
    /** 解析受限大小的 ffprobe JSON 响应。 */
    private final ObjectMapper mapper;

    /** COS 客户端仅本地签名，网络读写由具有绝对期限的连接完成。 */
    public VideoAudioExtractor(COSClient cosClient, CosProperties cos, VideoAudioProperties properties, ObjectMapper mapper) {
        this.cosClient = cosClient; this.cos = cos; this.properties = properties; this.mapper = mapper;
    }

    /** 下载前冻结对象身份，再下载并探测；失败时不向调用方转移临时目录所有权。 */
    public Prepared prepare(String key, Instant deadline) throws Exception {
        Source source = inspect(key, deadline);
        Path directory = Files.createTempDirectory(DIRECTORY_PREFIX);
        try {
            if (Files.getFileStore(directory).getUsableSpace() < properties.getMaxTempBytes()) {
                throw new IllegalStateException("视频音频临时空间不足");
            }
            Path video = directory.resolve(VIDEO_FILE);
            download(source, video, deadline);
            Probe probe = probe(video, VideoAudioIo.deadline(deadline, properties.getProbeTimeoutSeconds()));
            if (!source.equals(inspect(key, deadline))) { throw new IllegalStateException("下载期间视频源已变化"); }
            return new Prepared(directory, video, source, probe);
        } catch (Exception exception) { cleanup(directory); throw exception; }
    }

    /** HEAD 使用配置内存储桶的本地签名 URL，不接受调用方外部 URL。 */
    public Source inspect(String key, Instant outerDeadline) throws Exception {
        if (key == null || !key.matches(SOURCE_PATTERN) || key.contains("..")) {
            throw new IllegalStateException("视频源对象键不在受控目录");
        }
        Instant deadline = VideoAudioIo.deadline(outerDeadline, properties.getDownloadTimeoutSeconds());
        try (VideoAudioIo.Connection opened = VideoAudioIo.open(signed(key, HttpMethodName.HEAD, deadline), deadline)) {
            HttpURLConnection connection = opened.http();
            connection.setRequestMethod(HttpMethodName.HEAD.name());
            if (connection.getResponseCode() != 200) { throw new IllegalStateException("视频源 HEAD 失败"); }
            long length = connection.getContentLengthLong();
            String etag = normalizeEtag(connection.getHeaderField(ETAG));
            if (length <= 0 || length > properties.getMaxVideoBytes() || etag.isEmpty()) {
                throw new IllegalStateException("视频源大小或 ETag 非法");
            }
            VideoAudioIo.check(deadline);
            return new Source(key, etag, length);
        }
    }

    /** 有条件 GET 与实际字节计数共同保证下载的源和空间上限。 */
    private void download(Source source, Path video, Instant outerDeadline) throws Exception {
        Instant deadline = VideoAudioIo.deadline(outerDeadline, properties.getDownloadTimeoutSeconds());
        try (VideoAudioIo.Connection opened = VideoAudioIo.open(signed(source.key(), HttpMethodName.GET, deadline), deadline)) {
            HttpURLConnection connection = opened.http();
            connection.setRequestProperty(IF_MATCH, "\"" + source.etag() + "\"");
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() != source.bytes()
                    || !source.etag().equals(normalizeEtag(connection.getHeaderField(ETAG)))) {
                throw new IllegalStateException("视频源下载身份发生变化");
            }
            long total = 0;
            try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(video)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    VideoAudioIo.check(deadline);
                    total += count;
                    if (total > source.bytes() || total > properties.getMaxVideoBytes()) {
                        throw new IllegalStateException("视频源下载超过字节上限");
                    }
                    output.write(buffer, 0, count);
                }
            }
            VideoAudioIo.check(deadline);
            if (total != source.bytes()) { throw new IllegalStateException("视频源下载不完整"); }
        }
    }

    /** 一次探测验证容器与所有流真实时长，音轨不存在时仍要求有效视频时长。 */
    Probe probe(Path video, Instant deadline) throws Exception {
        return probe(video, deadline, CONTAINERS);
    }

    /** 解码后的 WAV 另行读取真实采样时长，不能完全信任原容器的时长声明。 */
    private Probe probe(Path video, Instant deadline, String containers) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(properties.getFfprobePath(), "-v", "error", "-max_alloc",
                MAX_ALLOCATION, "-protocol_whitelist", "file", "-format_whitelist", containers,
                "-show_entries", "format=duration:stream=codec_type,duration", "-of", "json", video.toString()));
        JsonNode root = mapper.readTree(process(arguments, deadline));
        if (root == null || !root.path(STREAMS).isArray()) { throw new IllegalStateException("视频音轨探测响应非法"); }
        double duration = duration(root.path(FORMAT).path(DURATION));
        boolean audio = false;
        for (JsonNode stream : root.path(STREAMS)) {
            audio |= AUDIO.equals(stream.path(CODEC_TYPE).asText());
            duration = Math.max(duration, duration(stream.path(DURATION)));
        }
        if (!Double.isFinite(duration) || duration <= 0 || duration > properties.getMaxDurationSeconds()) {
            throw new IllegalStateException("视频真实时长缺失或超过十分钟，不截取处理");
        }
        return new Probe(audio, duration);
    }

    /** 第一音轨完整转成单声道 16kHz PCM16 WAV；不做截时或片段拼接。 */
    public Path extract(Prepared prepared, Instant outerDeadline) throws Exception {
        Instant deadline = VideoAudioIo.deadline(outerDeadline, properties.getExtractTimeoutSeconds());
        Path wav = prepared.directory().resolve(WAV_FILE);
        process(List.of(properties.getFfmpegPath(), "-nostdin", "-v", "error", "-max_alloc", MAX_ALLOCATION,
                "-threads", "1", "-protocol_whitelist", "file", "-format_whitelist", CONTAINERS,
                "-i", prepared.video().toString(), "-map", "0:a:0", "-vn", "-sn", "-dn", "-ac", "1",
                "-ar", "16000", "-c:a", "pcm_s16le", "-threads", "1", "-fs",
                Long.toString(properties.getMaxWavBytes() + 1), "-f", "wav", "-y", wav.toString()), deadline);
        long size = Files.size(wav);
        if (size <= 44 || size > properties.getMaxWavBytes()
                || size + prepared.source().bytes() > properties.getMaxTempBytes()) {
            throw new IllegalStateException("提取音频为空或超过字节边界");
        }
        Probe extracted = probe(wav, VideoAudioIo.deadline(deadline, properties.getProbeTimeoutSeconds()), "wav");
        if (!extracted.hasAudio() || Math.ceil(extracted.seconds()) > reservedSeconds(prepared.probe(), properties.getMaxDurationSeconds())) {
            throw new IllegalStateException("解码音频时长超出预检预算，不发送付费请求");
        }
        return wav;
    }

    /** AAC 等压缩流解码可能带尾帧填充，保守多预留一秒；真实结果仍必须符合十分钟上限。 */
    public static long reservedSeconds(Probe probe, int maxDurationSeconds) {
        return Math.min(maxDurationSeconds, (long) Math.ceil(probe.seconds()) + 1);
    }

    /** 子进程执行不用 shell，始终回收进程；输出单独读取防止管道阻塞。 */
    private byte[] process(List<String> arguments, Instant deadline) throws Exception {
        VideoAudioIo.check(deadline);
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                boolean overflow = false;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count <= PROCESS_OUTPUT_BYTES) { bytes.write(buffer, 0, count); }
                    else { overflow = true; }
                }
                if (overflow) { output.completeExceptionally(new IllegalStateException("视频音频进程输出超限")); }
                else { output.complete(bytes.toByteArray()); }
            } catch (Exception exception) { output.completeExceptionally(exception); }
        });
        try {
            long millis = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
            if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) { throw new IllegalStateException("视频音频进程超时"); }
            VideoAudioIo.check(deadline);
            if (process.exitValue() != 0) { throw new IllegalStateException("视频音频进程执行失败"); }
            return output.get(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw exception; }
        finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.getInputStream().close();
            reader.interrupt();
        }
    }

    /** 签名不会触发 SDK 网络重试；有效期略长于本步骤期限。 */
    private URI signed(String key, HttpMethodName method, Instant deadline) throws Exception {
        return cosClient.generatePresignedUrl(cos.getBucketName(), key, Date.from(deadline.plusSeconds(30)), method).toURI();
    }
    /** 缺少某条流时长可由容器时长补足，非数值不参与取最大值。 */
    private static double duration(JsonNode value) {
        try { double parsed = Double.parseDouble(value.asText()); return Double.isFinite(parsed) ? parsed : 0; }
        catch (NumberFormatException exception) { return 0; }
    }
    /** 与持久帧源指纹使用同样的 ETag 规范化规则。 */
    private static String normalizeEtag(String value) {
        return value == null ? "" : value.replace("\"", "").strip().toLowerCase(Locale.ROOT);
    }
    /** 只删除由本模块创建的隔离目录，失败仅记录异常类型。 */
    private static void cleanup(Path directory) {
        if (!Files.exists(directory)) { return; }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        } catch (Exception exception) { log.warn("视频音频临时目录清理失败: exceptionType={}", exception.getClass().getSimpleName()); }
    }
    /** 同一对象键、ETag 和大小构成冻结的远端源身份。 */
    public record Source(String key, String etag, long bytes) { }
    /** 音轨存在性和真实时长，不使用客户端上报时长预估收费。 */
    public record Probe(boolean hasAudio, double seconds) { }
    /** 预检结果可跨帧重建阶段复用，结束时统一删除全部中间文件。 */
    public record Prepared(Path directory, Path video, Source source, Probe probe) implements AutoCloseable {
        /** 结束本件处理时回收原视频和所有派生音频文件。 */
        @Override public void close() { cleanup(directory); }
    }
}
