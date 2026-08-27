package com.jxc.wefolio.message;

/** 作品人工审核回传固定业务文案。 */
public interface WorkManualAuditMessage {

    /** 人工审核编号不存在或记录失效。 */
    String NOT_FOUND_MESSAGE = "人工审核记录不存在或已失效";

    /** 目标审核状态非法。 */
    String STATUS_INVALID_MESSAGE = "人工审核状态仅支持 PASSED 或 REJECTED";

    /** 拒绝原因缺失。 */
    String REJECT_REASON_EMPTY_MESSAGE = "审核拒绝原因不能为空";

    /** 拒绝原因超过 Unicode 码点上限。 */
    String REJECT_REASON_TOO_LONG_MESSAGE = "审核拒绝原因不能超过 512 个 Unicode 码点";

    /** 通过请求错误携带拒绝原因。 */
    String PASSED_REASON_NOT_ALLOWED_MESSAGE = "审核通过时不能填写拒绝原因";

    /** 已有结论与本次请求不一致。 */
    String RESULT_CONFLICT_MESSAGE = "人工审核结论已存在，不能覆盖";

    /** 行锁后的条件更新仍未命中。 */
    String CONCURRENT_CONFLICT_MESSAGE = "人工审核状态已变化，请重新查询";

    /** 飞书通知关联用户记录无效。 */
    String NOTIFICATION_USER_INVALID_MESSAGE = "作品人工审核通知用户无效";

    /** 飞书通知媒体类型无效。 */
    String NOTIFICATION_MEDIA_TYPE_INVALID_MESSAGE = "作品人工审核通知媒体类型无效";

    /** 飞书签名生成失败。 */
    String SIGNATURE_FAILURE_MESSAGE = "作品人工审核飞书签名生成失败";
}
