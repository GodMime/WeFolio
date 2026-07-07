package com.jxc.wefolio.job.service;

/**
 * 腾讯云数据万象审核客户端。
 */
public interface TencentCiAuditClient {

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

    /**
     * 查询视频审核结果。
     *
     * @param ciJobId 腾讯云数据万象任务 ID
     * @return 查询结果
     */
    TencentCiAuditResult queryVideo(String ciJobId);
}
