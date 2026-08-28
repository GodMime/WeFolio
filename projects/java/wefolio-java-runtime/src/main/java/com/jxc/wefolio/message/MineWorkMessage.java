package com.jxc.wefolio.message;

/**
 * 我的作品报错信息 — 统一维护作品、标签和上传确认相关提示。
 */
public interface MineWorkMessage {

    /** 图片作品数量超限提示模板 */
    String IMAGE_WORK_COUNT_LIMIT_TEMPLATE = "图片作品数量已达上限（%d个），请删除部分图片作品后再上传";

    /** 视频作品数量超限提示模板 */
    String VIDEO_WORK_COUNT_LIMIT_TEMPLATE = "视频作品数量已达上限（%d个），请删除部分视频作品后再上传";

    /** 动图作品数量超限提示模板 */
    String ANIMATION_WORK_COUNT_LIMIT_TEMPLATE = "动图作品最多保留 %d 个";

    /** 上传任务保存失败提示 */
    String UPLOAD_TASK_SAVE_FAILED_MESSAGE = "上传任务创建失败，请刷新后重试";

    /** 同批次重复文件提示 */
    String SAME_BATCH_DUPLICATE_FILE_MESSAGE = "同一批次存在重复文件，请重新选择后上传";

    /** 文件 SHA-256 格式错误提示 */
    String FILE_SHA256_INVALID_MESSAGE = "文件 SHA-256 格式不正确";

    /** 重复作品提示模板 */
    String DUPLICATE_WORK_MESSAGE_TEMPLATE = "作品已存在：「%s」";

    /** 作品对象键命名冲突提示 */
    String WORK_OBJECT_KEY_CONFLICT_MESSAGE = "上传文件命名冲突，请稍后重试";

    /** 标签不存在提示 */
    String TAG_NOT_FOUND_MESSAGE = "标签不存在或已删除";

    /** 标签重复提示 */
    String TAG_DUPLICATE_MESSAGE = "标签不能重复";

    /** 标签保存失败提示 */
    String TAG_SAVE_FAILED_MESSAGE = "标签保存失败，请刷新后重试";

    /** 标签删除失败提示 */
    String TAG_DELETE_FAILED_MESSAGE = "标签删除失败，请刷新后重试";

    /** 标签数量超限提示 */
    String TAG_COUNT_LIMIT_MESSAGE = "标签最多保留 10 个";

    /** 标签名称为空提示 */
    String TAG_NAME_EMPTY_MESSAGE = "标签名称不能为空";

    /** 标签名称超长提示 */
    String TAG_NAME_TOO_LONG_MESSAGE = "标签名称不能超过 10 个字";

    /** 排序列表为空提示 */
    String SORT_ITEMS_EMPTY_MESSAGE = "请提交要排序的作品";

    /** COS 文件读取失败提示 */
    String COS_OBJECT_READ_FAILED_MESSAGE = "上传文件读取失败，请重新上传";

    /** 上传确认未预期失败提示 */
    String UPLOAD_CONFIRM_UNEXPECTED_FAILED_MESSAGE = "作品确认失败，请稍后重试";

    /** 作品保存失败提示 */
    String WORK_SAVE_FAILED_MESSAGE = "作品保存失败，请刷新后重试";

    /** 非视频作品修改封面提示 */
    String VIDEO_COVER_UPDATE_MEDIA_TYPE_MESSAGE = "只有视频作品可以修改封面";

    /** 非图片作品修改缩略图提示 */
    String IMAGE_THUMBNAIL_UPDATE_MEDIA_TYPE_MESSAGE = "只有图片作品可以修改缩略图";

    /** 多个封面或缩略图编辑模式同时提交提示 */
    String COVER_EDIT_MODE_CONFLICT_MESSAGE = "不能同时提交多个封面或缩略图编辑";

    /** 作品媒体类型不支持提示 */
    String WORK_MEDIA_TYPE_UNSUPPORTED_MESSAGE = "作品媒体类型不支持";

    /** 图片缩略图缺失提示 */
    String IMAGE_THUMB_REQUIRED_MESSAGE = "图片缩略图缺失，请重新上传";

    /** 视频封面图缺失提示 */
    String VIDEO_COVER_REQUIRED_MESSAGE = "视频封面图缺失，请重新选择视频";

    /** 缩略图或封面图超限提示 */
    String COVER_TASK_SIZE_MESSAGE = "缩略图或封面图不能超过 100KB";

    /** 缩略图或封面图文件名错误提示 */
    String COVER_TASK_FILE_NAME_MESSAGE = "缩略图或封面图文件名必须为原文件名-thumb";

    /** 缩略图或封面图来源任务错误提示 */
    String COVER_SOURCE_TASK_INVALID_MESSAGE = "缩略图或封面图来源任务无效";

    /** 视频封面生成失败提示 */
    String VIDEO_COVER_GENERATE_FAILED_MESSAGE = "视频封面生成失败，请稍后重试";

    /** 动图文件权威元数据解析失败提示 */
    String ANIMATION_METADATA_READ_FAILED_MESSAGE = "动图文件解析失败，请重新上传";

    /** 动图封面生成失败提示 */
    String ANIMATION_COVER_GENERATE_FAILED_MESSAGE = "动图封面生成失败，请稍后重试";

    /** 动图封面字段必须成对提交提示 */
    String ANIMATION_COVER_FIELDS_REQUIRED_MESSAGE = "动图封面帧号和幂等键必须同时提交";

    /** 非动图作品修改动图封面提示 */
    String ANIMATION_COVER_UPDATE_MEDIA_TYPE_MESSAGE = "只有动图作品可以选择封面帧";

    /** 动图封面帧号越界提示 */
    String ANIMATION_COVER_FRAME_INVALID_MESSAGE = "动图封面帧号超出范围";

    /** 单个作品标签数量超限提示 */
    String WORK_TAG_COUNT_LIMIT_MESSAGE = "作品标签最多 10 个";

    /** 已引用作品移除标签提示 */
    String WORK_TAG_REMOVE_REFERENCED_MESSAGE = "作品已被作品集引用，只能新增标签，不能移除已有标签";

    /** 标签颜色非法提示 */
    String TAG_COLOR_INVALID_MESSAGE = "请选择有效的标签颜色";

    /** 标签被作品占用提示模板 */
    String TAG_DELETE_BLOCKED_TEMPLATE = "标签「%s」下还有 %d 个作品，先移除这些作品的标签后再删除。";

    /** 缩略图或封面图已使用提示 */
    String COVER_TASK_USED_MESSAGE = "缩略图或封面图已被使用，请重新上传";

    /** 缩略图或封面图过期提示 */
    String COVER_TASK_EXPIRED_MESSAGE = "缩略图或封面图已过期，请重新上传";

    /** 重复作品兜底提示 */
    String DUPLICATE_WORK_FALLBACK_MESSAGE = "作品已存在，请勿重复上传";

    /** 缩略图或封面图缺失提示 */
    String COVER_REQUIRED_MESSAGE = "缩略图或封面图不能为空";

    /** 缩略图或封面图类型错误提示 */
    String COVER_TASK_MEDIA_TYPE_MESSAGE = "缩略图或封面图必须是图片";

    /** 缩略图尺寸错误提示 */
    String COVER_TASK_DIMENSION_MESSAGE = "缩略图宽高必须为正数且不超过 10000 像素";

    /** 作品 ID 为空提示 */
    String WORK_EMPTY_MESSAGE = "作品不能为空";

    /** 作品不存在提示 */
    String WORK_NOT_FOUND_MESSAGE = "作品不存在或无访问权限";

    /** 作品已经处于审核流程中的提示 */
    String AUDIT_RESUBMIT_IN_PROGRESS_MESSAGE = "作品已在审核流程中，请勿重复提交";

    /** 已通过作品无需重审提示 */
    String AUDIT_RESUBMIT_PASSED_MESSAGE = "作品已通过审核，无需再次提交";

    /** 作品达到审核总轮次提示 */
    String AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE = "作品已达到审核次数上限，无法再次提交";

    /** 并发状态变化提示 */
    String AUDIT_RESUBMIT_STATE_CHANGED_MESSAGE = "作品审核状态已变化，请刷新后重试";

    /** 人工审核编号生成冲突提示 */
    String MANUAL_AUDIT_NO_CONFLICT_MESSAGE = "人工审核编号生成冲突，请重试";

    /** 作品删除失败提示 */
    String WORK_DELETE_FAILED_MESSAGE = "作品删除失败，请刷新后重试";

    /** 作品允许删除提示 */
    String WORK_DELETE_ALLOWED_MESSAGE = "作品未被作品集引用，可以删除";

    /** 作品被作品集引用提示模板 */
    String WORK_DELETE_BLOCKED_TEMPLATE = "作品已被 %d 个作品集引用，请先从作品集中移除";
}
