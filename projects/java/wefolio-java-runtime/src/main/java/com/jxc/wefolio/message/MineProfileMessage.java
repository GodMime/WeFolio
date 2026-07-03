package com.jxc.wefolio.message;

/**
 * 我的资料报错信息 — 统一维护基础信息、资料图片和标签相关提示。
 */
public interface MineProfileMessage {

    /** 资料图片为空提示 */
    String PROFILE_ASSET_REQUIRED_MESSAGE = "资料图片不能为空";

    /** 资料内容为空提示 */
    String PROFILE_UPDATE_REQUIRED_MESSAGE = "资料内容不能为空";

    /** 资料保存失败提示 */
    String PROFILE_SAVE_FAILED_MESSAGE = "资料保存失败，请重试";

    /** 资料图片类型不支持提示 */
    String PROFILE_ASSET_TYPE_UNSUPPORTED_MESSAGE = "资料图片类型不支持";

    /** 微信二维码格式不支持提示 */
    String WECHAT_QR_FORMAT_UNSUPPORTED_MESSAGE = "微信二维码格式仅支持 JPG、PNG";

    /** 资料图片大小异常提示 */
    String PROFILE_ASSET_SIZE_INVALID_MESSAGE = "资料图片大小异常";

    /** 微信二维码大小超限提示 */
    String WECHAT_QR_SIZE_LIMIT_MESSAGE = "微信二维码不能超过 300KB";

    /** 头像大小超限提示 */
    String AVATAR_SIZE_LIMIT_MESSAGE = "头像文件不能超过 200KB";

    /** 头像当月更新次数超限提示模板 */
    String AVATAR_UPDATE_LIMIT_TEMPLATE = "当月头像更新次数已达上限（%d次），请下月再试";

    /** 微信二维码当月更新次数超限提示模板 */
    String WECHAT_QR_UPDATE_LIMIT_TEMPLATE = "微信二维码当月更换次数已达上限（%d次），请下月再试";

    /** 头像地址归属错误提示 */
    String AVATAR_OWNERSHIP_INVALID_MESSAGE = "头像地址不属于当前用户";

    /** 微信二维码地址归属错误提示 */
    String WECHAT_QR_OWNERSHIP_INVALID_MESSAGE = "微信二维码地址不属于当前用户";

    /** 资料图片不存在或过期提示 */
    String PROFILE_ASSET_EXPIRED_MESSAGE = "资料图片不存在或已过期，请重新上传";

    /** 用户未登录提示 */
    String USER_LOGIN_REQUIRED_MESSAGE = "用户未登录";

    /** 用户不存在或停用提示 */
    String USER_UNAVAILABLE_MESSAGE = "用户不存在或已停用";

    /** 标签数量超限提示 */
    String TAG_COUNT_LIMIT_MESSAGE = "标签最多保留 10 个";

    /** 标签为空提示 */
    String TAG_EMPTY_MESSAGE = "标签不能为空";

    /** 标签长度超限提示 */
    String TAG_LENGTH_LIMIT_MESSAGE = "单个标签不能超过 10 个字";

    /** 标签重复提示 */
    String TAG_DUPLICATE_MESSAGE = "标签不能重复";

    /** 标签颜色非法提示 */
    String TAG_COLOR_INVALID_MESSAGE = "请选择有效的标签颜色";

    /** 字段长度超限提示模板 */
    String FIELD_LENGTH_LIMIT_TEMPLATE = "%s不能超过 %d 个字";
}
