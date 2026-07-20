package com.jxc.wefolio.common.upload;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** 头像图片格式 — 根据文件魔数识别受支持的真实图片类型。 */
public enum AvatarImageFormat {

    /** JPEG 图片 */
    JPEG("jpg", "image/jpeg"),

    /** PNG 图片 */
    PNG("png", "image/png"),

    /** GIF 图片 */
    GIF("gif", "image/gif"),

    /** WebP 图片 */
    WEBP("webp", "image/webp");

    /** 图片头读取长度，覆盖所有受支持格式的魔数判断 */
    private static final int HEADER_READ_LIMIT = 12;

    /** 规范扩展名，不含点号 */
    private final String extension;

    /** 规范 MIME 类型 */
    private final String contentType;

    /**
     * 创建头像图片格式。
     *
     * @param extension 规范扩展名
     * @param contentType 规范 MIME 类型
     */
    AvatarImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** @return 规范扩展名，不含点号。 */
    public String extension() {
        return extension;
    }

    /** @return 规范 MIME 类型。 */
    public String contentType() {
        return contentType;
    }

    /**
     * 根据文件名扩展名识别头像图片格式。
     *
     * @param fileName 文件名或完整对象键
     * @return 匹配到的头像图片格式
     */
    public static Optional<AvatarImageFormat> fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String normalized = fileName.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> normalized.endsWith("." + format.extension))
                .findFirst();
    }

    /**
     * 根据文件魔数识别头像图片格式。
     *
     * @param file 待识别文件
     * @return 识别到的图片格式，读取失败或格式不支持时为空
     */
    public static Optional<AvatarImageFormat> detect(MultipartFile file) {
        if (file == null) {
            return Optional.empty();
        }
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(HEADER_READ_LIMIT);
            if (isJpeg(header)) {
                return Optional.of(JPEG);
            }
            if (isPng(header)) {
                return Optional.of(PNG);
            }
            if (isGif(header)) {
                return Optional.of(GIF);
            }
            if (isWebp(header)) {
                return Optional.of(WEBP);
            }
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
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
