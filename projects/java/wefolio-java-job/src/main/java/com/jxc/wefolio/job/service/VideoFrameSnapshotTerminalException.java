package com.jxc.wefolio.job.service;

/** 供应商明确终态失败，新的显式历史批次可付费创建新任务。 */
public class VideoFrameSnapshotTerminalException extends VideoFrameProcessingException {
    /** 持久原因的稳定分类前缀，网络查询故障不使用此分类。 */
    public static final String STAGE = "FRAME_PROVIDER_FAILED";
    /** 创建安全的供应商终态错误。 */
    public VideoFrameSnapshotTerminalException(String reason) { super(STAGE, reason); }
}
