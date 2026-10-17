package com.jxc.wefolio.job.config;

import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 视频持久帧与显式历史批处理的有界配置。 */
@Data
@Component
@ConfigurationProperties(prefix = VideoFrameProperties.CONFIG_PREFIX)
public class VideoFrameProperties {
    /** 统一配置前缀。 */
    public static final String CONFIG_PREFIX = "video-frame";
    /** 自动查询当前存储桶唯一可用媒体处理队列的配置值。 */
    public static final String AUTO_QUEUE_ID = "auto";
    /** 单次远端调用上限，SDK 不隐藏重试。 */
    private int remoteTimeoutSeconds = 5;
    /** 可调原始截图下载预算；不改变固定规范帧字节或帧数合同。 */
    private int maxDownloadBytes = 10 * 1024 * 1024;
    /** 解码前允许的最大像素数。 */
    private long maxPixels = 40_000_000L;
    /** 数据万象异步媒体队列，仅历史入口使用；默认自动查询，显式队列优先。 */
    private String ciQueueId = AUTO_QUEUE_ID;
    /** 可调历史批次总截图预算；不改变单作品固定帧数上限。 */
    private int backfillMaxFrames = 2400;
    /** 历史单件处理期限。 */
    private int backfillItemSeconds = 180;
    /** 历史扫描页大小。 */
    private int backfillBatchSize = 20;
    /** 历史异步状态查询间隔。 */
    private int backfillPollSeconds = 3;
    /** 单请求不允许超过三十秒或没有正上限。 */
    public void setRemoteTimeoutSeconds(int seconds) {
        if (seconds < 1 || seconds > 30) { throw new IllegalArgumentException("视频帧远端超时须为1至30秒"); }
        this.remoteTimeoutSeconds = seconds;
    }
    /** 单帧硬上限防止配置错误导致无界分配。 */
    public void setMaxDownloadBytes(int bytes) {
        if (bytes < 1 || bytes > 16 * 1024 * 1024) { throw new IllegalArgumentException("视频帧下载上限须为1字节至16MiB"); }
        this.maxDownloadBytes = bytes;
    }
    /** 像素预检不能关闭，最多允许四千万像素。 */
    public void setMaxPixels(long pixels) {
        if (pixels < 1 || pixels > 40_000_000L) { throw new IllegalArgumentException("视频帧像素上限须为1至四千万"); }
        this.maxPixels = pixels;
    }
    /** 历史单件必须在批次十分钟租约内结束。 */
    public void setBackfillItemSeconds(int seconds) {
        if (seconds < 15 || seconds > 300) { throw new IllegalArgumentException("历史补帧单件期限须为15至300秒"); }
        this.backfillItemSeconds = seconds;
    }
    /** 历史截图预算必须能覆盖至少八帧。 */
    public void setBackfillMaxFrames(int frames) {
        if (frames < PortfolioAiMediaContract.FRAME_MIN_COUNT || frames > 240000) { throw new IllegalArgumentException("历史补帧预算须为8至240000帧"); }
        this.backfillMaxFrames = frames;
    }
    /** 页大小有上限，不加载全部作品。 */
    public void setBackfillBatchSize(int size) {
        if (size < 1 || size > 200) { throw new IllegalArgumentException("历史补帧页大小须为1至200"); }
        this.backfillBatchSize = size;
    }
    /** 轮询等待不能为零，也不能超过单件处理期限。 */
    public void setBackfillPollSeconds(int seconds) {
        if (seconds < 1 || seconds > 10) { throw new IllegalArgumentException("历史补帧查询间隔须为1至10秒"); }
        this.backfillPollSeconds = seconds;
    }
}
