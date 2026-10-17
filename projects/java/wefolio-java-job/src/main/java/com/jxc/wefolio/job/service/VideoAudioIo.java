package com.jxc.wefolio.job.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 音频网络步骤的总期限保护；慢速持续响应也不能无限占用工作线程。 */
final class VideoAudioIo {
    /** 守护线程仅负责断开到期连接，不执行业务或保留凭据。 */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "video-audio-deadline");
        thread.setDaemon(true);
        return thread;
    });
    /** 禁止实例化工具类。 */
    private VideoAudioIo() { }
    /** 每一步都检查调用方中断和绝对期限。 */
    static void check(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || deadline == null || !Instant.now().isBefore(deadline)) {
            throw new IllegalStateException("视频音频处理已中断或超时");
        }
    }
    /** 分阶段上限受调用方剩余时间共同约束。 */
    static Instant deadline(Instant outer, int seconds) {
        check(outer);
        Instant stage = Instant.now().plusSeconds(seconds);
        return stage.isBefore(outer) ? stage : outer;
    }
    /** 打开不跟随重定向的连接；调用方必须关闭返回的资源。 */
    static Connection open(URI uri, Instant deadline) throws IOException {
        check(deadline);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Duration.between(Instant.now(), deadline).toMillis()));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(millis);
        connection.setReadTimeout(millis);
        Thread owner = Thread.currentThread();
        return new Connection(connection, WATCHDOG.scheduleAtFixedRate(() -> {
            if (owner.isInterrupted() || !Instant.now().isBefore(deadline)) { connection.disconnect(); }
        }, Math.min(100, millis), 100, TimeUnit.MILLISECONDS));
    }
    /** 即使响应没有 Content-Length，也逐段限制实际读取量。 */
    static byte[] read(InputStream input, int maximum, Instant deadline) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            check(deadline);
            if ((long) bytes.size() + read > maximum) { throw new IOException("视频音频响应超过字节上限"); }
            bytes.write(buffer, 0, read);
        }
        check(deadline);
        return bytes.toByteArray();
    }
    /** 结束步骤即取消看门狗并断开连接。 */
    record Connection(HttpURLConnection http, ScheduledFuture<?> timeout) implements AutoCloseable {
        /** 撤销定时断开任务，并释放当前网络连接。 */
        @Override public void close() { timeout.cancel(false); http.disconnect(); }
    }
}
