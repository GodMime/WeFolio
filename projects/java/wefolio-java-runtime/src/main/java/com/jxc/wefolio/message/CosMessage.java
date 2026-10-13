package com.jxc.wefolio.message;

/** COS 文件服务错误文案。 */
public interface CosMessage {

    /** COS 对象键为空提示 */
    String OBJECT_KEY_REQUIRED_MESSAGE = "COS 对象键不能为空";

    /** 稳定分享图片复制失败提示，不向客户端暴露 COS 内部信息。 */
    String OBJECT_COPY_FAILED_MESSAGE = "分享图片保存失败，请稍后重试";
}
