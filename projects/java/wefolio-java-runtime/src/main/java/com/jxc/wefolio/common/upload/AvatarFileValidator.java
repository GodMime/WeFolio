package com.jxc.wefolio.common.upload;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 头像文件校验工具 — 只服务头像、团队图标等公开头像类上传入口。
 */
public final class AvatarFileValidator {

    /** 头像文件最大大小：200KB */
    public static final long MAX_AVATAR_SIZE_BYTES = 200L * 1024L;

    /** 图片头读取长度，覆盖 JPG、PNG、GIF、WebP 的魔数判断 */
    private static final int HEADER_READ_LIMIT = 12;

    /** 支持的图片格式提示 */
    private static final String SUPPORTED_FORMATS_MESSAGE = "仅支持 JPG、PNG、GIF、WebP 格式";

    /** 文件为空提示后缀 */
    private static final String EMPTY_MESSAGE_SUFFIX = "不能为空";

    /** 文件过大提示后缀 */
    private static final String TOO_LARGE_MESSAGE_SUFFIX = "不能超过 200KB";

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
            return fileLabel + EMPTY_MESSAGE_SUFFIX;
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            return fileLabel + TOO_LARGE_MESSAGE_SUFFIX;
        }
        if (!hasSupportedImageHeader(file)) {
            return fileLabel + SUPPORTED_FORMATS_MESSAGE;
        }
        return null;
    }

    /**
     * 判断上传文件头是否为支持的图片格式。
     *
     * @param file 上传文件
     * @return 是否为支持的图片格式
     */
    private static boolean hasSupportedImageHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(HEADER_READ_LIMIT);
            return isJpeg(header) || isPng(header) || isGif(header) || isWebp(header);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 判断 JPG/JPEG 文件头。
     *
     * @param header 文件头字节
     * @return 是否为 JPG/JPEG
     */
    private static boolean isJpeg(byte[] header) {
        return header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    /**
     * 判断 PNG 文件头。
     *
     * @param header 文件头字节
     * @return 是否为 PNG
     */
    private static boolean isPng(byte[] header) {
        return header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 'P'
                && header[2] == 'N'
                && header[3] == 'G'
                && header[4] == '\r'
                && header[5] == '\n'
                && (header[6] & 0xFF) == 0x1A
                && header[7] == '\n';
    }

    /**
     * 判断 GIF 文件头。
     *
     * @param header 文件头字节
     * @return 是否为 GIF
     */
    private static boolean isGif(byte[] header) {
        return header.length >= 6
                && header[0] == 'G'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == '8'
                && (header[4] == '7' || header[4] == '9')
                && header[5] == 'a';
    }

    /**
     * 判断 WebP 文件头。
     *
     * @param header 文件头字节
     * @return 是否为 WebP
     */
    private static boolean isWebp(byte[] header) {
        return header.length >= 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P';
    }
}
