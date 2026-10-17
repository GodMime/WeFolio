package com.jxc.wefolio.job.dict;

/** AI 任务五态；超时是 FAILED 加 TIMEOUT，不新增状态。 */
public enum AiTaskStatusDict {
    /** 排队或退避等待。 */ PENDING,
    /** 当前执行代次已领取。 */ RUNNING,
    /** 得到合法候选或澄清。 */ SUCCEEDED,
    /** 最终失败或超时。 */ FAILED,
    /** 发起人取消。 */ CANCELLED;

    /** 数据库保存的固定编码。 */
    public String getCode() { return name(); }
}
