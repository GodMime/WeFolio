package com.jxc.wefolio.message;

/**
 * 头像文件报错信息 — 统一维护头像、团队图标等头像类上传校验提示。
 */
public interface AvatarFileMessage {

    /** 支持的图片格式提示 */
    String SUPPORTED_FORMATS_MESSAGE = "仅支持 JPG、PNG、GIF、WebP 格式";

    /** 文件为空提示后缀 */
    String EMPTY_MESSAGE_SUFFIX = "不能为空";

    /** 文件过大提示后缀 */
    String TOO_LARGE_MESSAGE_SUFFIX = "不能超过 200KB";
}
