package com.jxc.wefolio.common.upload;

import com.jxc.wefolio.dto.FileUploadResponse;

/**
 * 头像上传应用结果。
 *
 * @param success 是否上传成功
 * @param message 校验失败消息，成功时为空
 * @param data 上传响应，失败时为空
 */
public record AvatarUploadResult(boolean success, String message, FileUploadResponse data) {

    /** 创建校验失败结果。 */
    public static AvatarUploadResult failure(String message) {
        return new AvatarUploadResult(false, message, null);
    }

    /** 创建上传成功结果。 */
    public static AvatarUploadResult succeeded(FileUploadResponse data) {
        return new AvatarUploadResult(true, null, data);
    }
}
