package com.jxc.wefolio.job.service;

/**
 * 腾讯云数据万象审核客户端。
 */
public interface TencentCiAuditClient {

    /** 提交 COS 音频对象审核，不传输文件内容。 */
    TencentCiAuditResult submitAudio(String objectKey);

    /** 查询音频异步审核结果。 */
    TencentCiAuditResult queryAudio(String ciJobId);

    /**
     * 同步审核图片。
     *
     * @param objectKey COS 对象键
     * @return 审核结果
     */
    TencentCiAuditResult auditImage(String objectKey);

    /**
     * 发起视频异步审核。
     *
     * @param objectKey COS 对象键
     * @param snapshotIntervalSeconds 截帧间隔秒数
     * @param snapshotCount 截帧数量
     * @return 提交结果
     */
    TencentCiAuditResult submitVideo(String objectKey, int snapshotIntervalSeconds, int snapshotCount);

    /** 使用任务行的冻结请求提交，不从全局配置推导模式。 */
    TencentCiAuditResult submitVideo(String objectKey, TencentCiVideoAuditRequest request);

    /**
     * 查询视频审核结果。
     *
     * @param ciJobId 腾讯云数据万象任务 ID
     * @return 查询结果
     */
    TencentCiAuditResult queryVideo(String ciJobId);

    /** 新持久帧任务使用无隐式重试、有界请求的客户端。 */
    TencentCiAuditResult queryVideo(String ciJobId, boolean persistentFrames);
}
