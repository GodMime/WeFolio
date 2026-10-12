package com.jxc.wefolio.message;

/** AI 任务及候选失败的固定安全说明，不包含模型原文、资源地址或内部异常值。 */
public interface PortfolioAiFailureMessage {
    /** 未识别的任务失败。 */
    String GENERATION_FAILED = "AI 生成未完成，请重新生成";
    /** 任务整体执行期限耗尽。 */
    String TASK_TIMEOUT = "生成任务超过处理时限，请重新生成";
    /** 单次官方模型调用超时。 */
    String REMOTE_TIMEOUT = "AI 服务响应超时，请重新生成";
    /** 官方模型服务繁忙。 */
    String REMOTE_BUSY = "AI 服务繁忙，请稍后重新生成";
    /** 官方模型网络失败。 */
    String REMOTE_ERROR = "连接 AI 服务失败，请稍后重新生成";
    /** 模型配置或账户不可用。 */
    String AI_UNAVAILABLE = "AI 服务配置或账户暂不可用，请稍后重试";
    /** 请求参数与模型接口配置不匹配。 */
    String REMOTE_CONFIGURATION = "AI 服务接口配置不符合要求，请稍后重试";
    /** 模型输出未能完整完成。 */
    String OUTPUT_TRUNCATED = "AI 生成内容超过长度限制，未能完整返回，请简化描述后重新生成";
    /** 模型中断输出。 */
    String OUTPUT_BLOCKED = "AI 未能完成本次内容生成，请调整描述后重新生成";
    /** 模型回复不满足当前协议。 */
    String INVALID_OUTPUT = "AI 返回的内容格式不符合要求，请重新生成";
    /** 模型回复超出响应预算。 */
    String RESPONSE_TOO_LARGE = "AI 返回的内容超过处理上限，请简化描述后重新生成";
    /** 所有尝试已耗尽。 */
    String ATTEMPTS_EXHAUSTED = "生成任务多次尝试仍未完成，请重新生成";
    /** 执行器或模型调用中断。 */
    String EXECUTION_INTERRUPTED = "生成任务执行中断，请重新生成";
    /** 未预期的内部执行失败。 */
    String INTERNAL_ERROR = "生成服务处理异常，请稍后重新生成";
    /** 媒体输入或冻结计划不符合处理条件。 */
    String MEDIA_INVALID = "所选素材不符合 AI 处理要求，请调整素材后重新生成";
    /** 媒体源身份变化或作品、团队授权失效。 */
    String SOURCE_UNAVAILABLE = "所选素材已失效或无权访问，请重新选择素材后生成";
    /** 视频持久帧缺失或不完整。 */
    String MEDIA_NOT_PREPARED = "所选视频尚未完成素材准备，请稍后重新生成";
    /** 媒体对象服务短暂故障，多次重试仍未恢复。 */
    String MEDIA_ACCESS_RETRY = "媒体对象服务暂时不可用，请稍后重新生成";
    /** 单任务、每日额度或媒体调用次数不足，不能继续付费请求。 */
    String MEDIA_BUDGET_EXHAUSTED = "AI 媒体处理额度不足，请减少素材或稍后重试";
    /** 模型实际用量超过媒体预留额度。 */
    String MEDIA_BUDGET_OVERRUN = "本次 AI 媒体处理超出额度，请减少素材后重新生成";
    /** 媒体账本异常，不允许在无法核对费用时继续执行。 */
    String MEDIA_BUDGET_INVALID = "AI 媒体处理费用记录异常，请联系管理员核对";
    /** 素材分析结果或摘要缓存不满足固定媒体协议。 */
    String INVALID_MEDIA_SUMMARY = "AI 返回的素材分析结果不符合要求，请重新生成";
    /** 冻结请求不能解析。 */
    String REQUEST_INVALID = "生成任务的原始输入不完整或已失效，请重新生成";
    /** 本次查询的基准校验失败。 */
    String REVISION_INVALID = "生成结果与当前作品集版本不一致，请基于最新内容重新生成";
    /** 本次查询的来源复核失败。 */
    String SOURCE_VALIDATION_FAILED = "生成结果引用的素材未通过可用性校验，请重新选择素材后生成";
    /** 中间结果不能编译为普通配置。 */
    String COMPILATION_FAILED = "AI 返回的内容未能转换为作品集页面，请重新生成";
    /** 普通配置规则拒绝候选。 */
    String CONFIG_INVALID = "生成结果未通过作品集组件配置校验，请重新生成";
    /** 显式字段受到普通规范化改变。 */
    String NORMALIZATION_CHANGED = "生成结果的配置在处理时发生变化，无法安全采用，请重新生成";
    /** 完整普通渲染失败。 */
    String RENDER_FAILED = "生成结果未能完成页面展示，请重新生成";
    /** 普通校验固定原因的展示前缀。 */
    String CONFIG_REASON_PREFIX = "生成结果不符合组件要求：";
    /** 普通规范化丢弃显式字段。 */
    String NORMALIZATION_FIELD_DROPPED = "生成配置包含无法保留的字段，未通过兼容性校验，请重新生成";
    /** 普通规范化改变显式字段类型。 */
    String NORMALIZATION_TYPE_CHANGED = "生成配置的字段类型在处理时发生变化，无法安全采用，请重新生成";
    /** 普通规范化改变显式字段值。 */
    String NORMALIZATION_VALUE_CHANGED = "生成配置的字段值在处理时发生变化，无法安全采用，请重新生成";
    /** 普通规范化改变数组元素数量。 */
    String NORMALIZATION_ARRAY_CHANGED = "生成配置的内容数量在处理时发生变化，无法安全采用，请重新生成";
}
