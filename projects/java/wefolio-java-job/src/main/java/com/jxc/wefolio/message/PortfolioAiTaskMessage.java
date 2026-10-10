package com.jxc.wefolio.message;

/** Runtime 与 Job 共用的 AI 任务失败提示，两份源码由一致性测试约束。 */
public interface PortfolioAiTaskMessage {
    /** 生成未成功时的固定安全提示，小程序离线兜底与此同源。 */
    String TASK_FAILED_MESSAGE = "未能生成，现有内容已保留";
    /** 总执行预算耗尽时的固定安全提示。 */
    String TASK_TIMEOUT_MESSAGE = "生成超时，现有内容已保留";
}
