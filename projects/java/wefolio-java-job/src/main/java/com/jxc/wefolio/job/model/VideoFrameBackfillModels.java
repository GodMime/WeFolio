package com.jxc.wefolio.job.model;

/** 历史补帧的只读仓储快照与 HTTP 提交模型。 */
public final class VideoFrameBackfillModels {
    private VideoFrameBackfillModels() { }
    /** 作品读取只保留帧处理和最终 CAS 所需信息。 */
    public record Work(long id, String objectKey, String sha256, Integer durationMs,
            String manifest, int version, String ownerCode) { }
    /** runId 仅保留请求解析兼容性；无表的一次性执行不支持批次续跑。 */
    public record Request(Long runId, Integer maxFrames) { }
    /** 执行编号只关联本次日志，不代表持久化任务或已完成补帧。 */
    public record Submission(boolean accepted, Long runId, String status, String executionId) { }
    /** 单次内存执行汇总；提交失败也保留已预留的截图预算。 */
    public record Summary(long scanned, long succeeded, long skipped, long failed,
            int chargedFrames, boolean budgetExhausted) { }
}
