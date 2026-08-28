package com.jxc.wefolio.message;

/**
 * 我的反馈报错信息，统一维护反馈内容、附件上传和任务确认提示。
 */
public interface MineFeedbackMessage {

    /** 反馈轮次 JSON 损坏提示。 */
    String ROUNDS_JSON_CORRUPT_MESSAGE = "反馈数据异常，请稍后重试";

    /** 反馈描述为空提示。 */
    String DESCRIPTION_EMPTY_MESSAGE = "反馈描述不能为空";

    /** 反馈描述超长提示。 */
    String DESCRIPTION_TOO_LONG_MESSAGE = "反馈描述不能超过 200 个字";

    /** 单轮附件数量非法提示。 */
    String ATTACHMENT_COUNT_INVALID_MESSAGE = "每轮最多上传 3 个附件";

    /** 单次上传票据文件数量非法提示。 */
    String UPLOAD_FILE_COUNT_INVALID_MESSAGE = "请一次上传 1 至 3 个附件";

    /** 上传文件信息缺失提示。 */
    String UPLOAD_FILE_INVALID_MESSAGE = "上传文件信息不完整";

    /** 客户端文件标识非法提示。 */
    String UPLOAD_CLIENT_ID_INVALID_MESSAGE = "上传文件标识格式不正确";

    /** 客户端文件标识已失效提示。 */
    String UPLOAD_CLIENT_ID_REUSED_MESSAGE = "该文件上传任务已失效，请重新选择文件后再上传";

    /** 上传文件名或扩展名非法提示。 */
    String UPLOAD_FILE_NAME_INVALID_MESSAGE = "上传文件名或扩展名不正确";

    /** 上传文件媒体类型或 MIME 非法提示。 */
    String UPLOAD_FILE_TYPE_INVALID_MESSAGE = "上传文件类型不支持";

    /** 上传文件大小非法提示。 */
    String UPLOAD_FILE_SIZE_INVALID_MESSAGE = "上传文件大小不符合要求";

    /** 上传视频时长非法提示。 */
    String UPLOAD_FILE_DURATION_INVALID_MESSAGE = "上传视频时长必须大于 0 且不能超过 10 分钟";

    /** 图片携带时长提示。 */
    String UPLOAD_IMAGE_DURATION_INVALID_MESSAGE = "图片附件时长必须为 0";

    /** 当前用户不存在或已停用提示。 */
    String USER_NOT_FOUND_MESSAGE = "用户不存在或已停用";

    /** 上传任务创建失败提示。 */
    String UPLOAD_TASK_SAVE_FAILED_MESSAGE = "上传任务创建失败，请稍后重试";

    /** 上传任务不存在提示。 */
    String UPLOAD_TASK_MISSING_MESSAGE = "上传任务不存在，请重新上传";

    /** 上传任务不属于当前用户提示。 */
    String UPLOAD_TASK_NOT_OWNED_MESSAGE = "上传任务不属于当前用户";

    /** 上传任务已过期提示。 */
    String UPLOAD_TASK_EXPIRED_MESSAGE = "上传任务已过期，请重新上传";

    /** 上传任务状态不可用提示。 */
    String UPLOAD_TASK_STATUS_INVALID_MESSAGE = "上传任务已失效，请重新上传";

    /** COS 对象读取失败提示。 */
    String UPLOAD_OBJECT_READ_FAILED_MESSAGE = "上传文件读取失败，请重新上传";

    /** COS 对象元数据与任务声明不一致提示。 */
    String UPLOAD_OBJECT_MISMATCH_MESSAGE = "上传文件与任务信息不一致，请重新上传";

    /** 上传任务确认更新失败提示。 */
    String UPLOAD_TASK_CONFIRM_FAILED_MESSAGE = "上传任务确认失败，请稍后重试";

    /** 当前用户活跃反馈已达上限提示。 */
    String ACTIVE_LIMIT_REACHED_MESSAGE = "当前已有 3 个待处理问题，请处理完成后再提交";

    /** 反馈不存在或不属于当前用户提示。 */
    String FEEDBACK_NOT_FOUND_MESSAGE = "问题反馈不存在";

    /** 创建或追加幂等键非法提示。 */
    String IDEMPOTENCY_KEY_INVALID_MESSAGE = "提交标识格式不正确";

    /** 上传任务 ID 重复提示。 */
    String UPLOAD_TASK_DUPLICATED_MESSAGE = "附件任务不能重复";

    /** 当前反馈状态不允许追加提示。 */
    String APPEND_STATUS_INVALID_MESSAGE = "当前问题状态不允许补充反馈";

    /** 反馈轮次达到上限提示。 */
    String ROUND_LIMIT_REACHED_MESSAGE = "每个问题最多提交 3 轮反馈";

    /** 反馈保存失败提示。 */
    String FEEDBACK_SAVE_FAILED_MESSAGE = "问题反馈保存失败，请稍后重试";

    /** 内部状态值非法提示。 */
    String STATUS_INVALID_MESSAGE = "问题反馈状态不正确";

    /** 内部状态流转非法提示。 */
    String STATUS_TRANSITION_INVALID_MESSAGE = "当前问题状态不允许执行该操作";

    /** 团队反馈结果为空提示。 */
    String FEEDBACK_RESULT_EMPTY_MESSAGE = "反馈结果不能为空";

    /** 团队反馈结果超长提示。 */
    String FEEDBACK_RESULT_TOO_LONG_MESSAGE = "反馈结果不能超过 200 个字";

}
