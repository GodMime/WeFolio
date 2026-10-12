package com.jxc.wefolio.job.service;

/** 源文件变化属于旧结果失效，不能覆盖当前作品审核状态。 */
public class VideoFrameSourceChangedException extends VideoFrameProcessingException {
    /** 创建源变化信号。 */
    public VideoFrameSourceChangedException() { super("SOURCE_VERIFY", "源视频对象已变化"); }
}
