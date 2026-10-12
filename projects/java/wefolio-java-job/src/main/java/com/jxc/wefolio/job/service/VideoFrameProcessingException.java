package com.jxc.wefolio.job.service;

/** 帧链路安全错误，不将远端签名地址或密钥写入业务原因。 */
public class VideoFrameProcessingException extends RuntimeException {
    /** 失败阶段。 */
    private final String stage;
    /** 创建可以直接落库的安全异常。 */
    public VideoFrameProcessingException(String stage, String reason) {
        super(stage + ": " + reason);
        this.stage = stage;
    }
    /** 返回失败阶段。 */
    public String getStage() { return stage; }
}
