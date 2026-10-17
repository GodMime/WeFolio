package com.jxc.wefolio.job.service;

import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;

/** 视频持久帧的稳定采样合同。 */
public final class VideoFramePolicy {
    /** 清单结构版本。 */
    public static final String SCHEMA_VERSION = PortfolioAiMediaContract.FRAME_SCHEMA;
    /** 采样策略版本。 */
    public static final String POLICY_VERSION = "balanced-frames-v1";
    /** 单件视频允许的帧数范围。 */
    public static final int MIN_COUNT = PortfolioAiMediaContract.FRAME_MIN_COUNT;
    /** 固定单作品上限，与媒体冻结及模型消费者一致；不是可调批次预算。 */
    public static final int MAX_COUNT = PortfolioAiMediaContract.FRAME_MAX_COUNT;
    /** 每帧目标覆盖毫秒数，只用于数量计算。 */
    private static final int TARGET_SPAN_MS = PortfolioAiMediaContract.FRAME_TARGET_SPAN_MS;
    private VideoFramePolicy() { }
    /** 使用前端时长计算目标数量，未知时长使用八帧。 */
    public static int calculateFrameCount(Integer durationMs) {
        if (durationMs == null || durationMs <= 0) { return MIN_COUNT; }
        return (int) Math.min(MAX_COUNT, Math.max(MIN_COUNT,
                ((long) durationMs + TARGET_SPAN_MS - 1) / TARGET_SPAN_MS));
    }
}
