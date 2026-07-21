package com.jxc.wefolio.common.upload;

import com.jxc.wefolio.message.AvatarFileMessage;
import org.springframework.web.multipart.MultipartFile;

/**
 * 头像文件校验工具 — 只服务头像、团队图标等公开头像类上传入口。
 */
public final class AvatarFileValidator {

    /** 头像文件最大大小：200KB */
    public static final long MAX_AVATAR_SIZE_BYTES = 200L * 1024L;

    private AvatarFileValidator() {
    }

    /**
     * 校验头像类上传文件。
     *
     * @param file 上传文件
     * @param fileLabel 文件提示名称，如“头像文件”“团队图标”
     * @return 校验失败提示，校验通过时返回空
     */
    public static String validate(MultipartFile file, String fileLabel) {
        if (file == null || file.isEmpty()) {
            return fileLabel + AvatarFileMessage.EMPTY_MESSAGE_SUFFIX;
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            return fileLabel + AvatarFileMessage.TOO_LARGE_MESSAGE_SUFFIX;
        }
        if (AvatarImageFormat.detect(file).isEmpty()) {
            return fileLabel + AvatarFileMessage.SUPPORTED_FORMATS_MESSAGE;
        }
        return null;
    }
}
