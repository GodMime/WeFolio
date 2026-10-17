package com.jxc.wefolio.job.service;

/** 已有持久帧确定不存在或不合格，可以在显式历史批次重建。 */
public class VideoFrameAssetInvalidException extends VideoFrameProcessingException {
    /** 创建确定资产失效信号，不把暂时的 COS 故障误当丢帧。 */
    public VideoFrameAssetInvalidException(String reason) { super("FRAME_VERIFY", reason); }
}
