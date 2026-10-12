package com.jxc.wefolio.job.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** 视频帧专用远端连接及历史显式执行器配置。 */
@Configuration
public class VideoFrameConfig {
    /** 专用 COS 客户端名称，供审核与模型读取共同引用。 */
    public static final String COS_CLIENT_NAME = "videoFrameCosClient";
    /** 历史单线程执行器名称及线程前缀。 */
    public static final String BACKFILL_EXECUTOR_NAME = "videoFrameBackfillExecutor";
    private static final String BACKFILL_THREAD_PREFIX = "video-frame-backfill-";
    /** 创建禁用 SDK 重试且有总请求超时的帧客户端。 */
    @Bean(name = COS_CLIENT_NAME, destroyMethod = "shutdown")
    public COSClient videoFrameCosClient(CosProperties cos, VideoFrameProperties frames) {
        ClientConfig config = new ClientConfig(new Region(cos.getRegion()));
        int millis = Math.multiplyExact(Math.max(1, frames.getRemoteTimeoutSeconds()), 1000);
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(millis);
        config.setConnectionRequestTimeout(millis);
        config.setSocketTimeout(millis);
        config.setMaxErrorRetry(0);
        config.setChangeEndpointRetry(false);
        config.setRequestTimeOutEnable(true);
        config.setRequestTimeout(millis);
        return new COSClient(new BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey()), config);
    }
    /** 付费历史补帧仅显式提交；定时原始输出扫尾复用此执行器，不创建新截图任务。 */
    @Bean(name = BACKFILL_EXECUTOR_NAME)
    public ThreadPoolTaskExecutor videoFrameBackfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix(BACKFILL_THREAD_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
