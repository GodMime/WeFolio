package com.jxc.wefolio.message;

/** 高级作品集 AI 边界的安全提示文案。 */
public interface PortfolioAiMessage {
    /** 不支持的业务输入。 */
    String INVALID_INPUT = "AI 请求参数不合法";
    /** 当前开关关闭。 */
    String DISABLED = "AI 暂不可用，请稍后重试";
    /** 活动任务占用额度。 */
    String ACTIVE_TASK = "已有生成任务，请等待结束或取消旧任务";
    /** 同键不同参数。 */
    String IDEMPOTENCY_CONFLICT = "同一请求标识不能用于不同内容";
    /** 不存在、越权或到期的任务。 */
    String TASK_NOT_FOUND = "生成任务不存在或已过期";
    /** 来源已经不再有效。 */
    String SOURCE_UNAVAILABLE = "所选来源已移除或不可用";
    /** 授权作品没有当前可读的COS媒体。 */
    String MEDIA_UNAVAILABLE = "所选作品媒体当前不可用";
    /** 持久帧必须由审核或历史任务提前生成。 */
    String MEDIA_NOT_PREPARED = "所选视频持久帧尚未准备完成，请稍后重试";
    /** 远端草稿修订已变化。 */
    String STALE_DRAFT = "作品集已被其他成员更新，请基于最新内容重新生成";
    /** 契约升级导致旧候选不可用。 */
    String CONTRACT_UNAVAILABLE = "生成协议已更新，请重新生成";
    /** 完整配置超出 AI 运行预算。 */
    String CONFIG_LIMIT = "当前内容超出 AI 处理范围，仍可手动编辑保存";
    /** 候选不能解释或业务校验不通过。 */
    String CANDIDATE_INVALID = "生成结果暂不可用，现有内容已保留，请重新生成";
    /** 实际合法差异为空。 */
    String NO_CHANGES = "当前内容已满足要求";
}
