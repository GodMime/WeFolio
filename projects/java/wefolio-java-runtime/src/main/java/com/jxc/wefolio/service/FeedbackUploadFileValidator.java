package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.FeedbackMediaTypeDict;
import com.jxc.wefolio.dto.MineFeedbackUploadTicketRequest;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineFeedbackMessage;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 反馈附件客户端文件元数据校验器。
 */
@Component
public class FeedbackUploadFileValidator {

    /** 单次最多申请的反馈附件数量。 */
    static final int MAX_FILE_COUNT = 3;

    /** 图片最大字节数。 */
    static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;

    /** 视频最大字节数。 */
    static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024;

    /** 视频最大时长毫秒。 */
    static final long VIDEO_MAX_DURATION_MS = 600_000L;

    /** 文件扩展名分隔符。 */
    private static final String EXTENSION_SEPARATOR = ".";

    /** MIME 参数分隔符。 */
    private static final String MIME_PARAMETER_SEPARATOR = ";";

    /** 正斜杠路径分隔符。 */
    private static final String PATH_SEPARATOR = "/";

    /** 反斜杠路径分隔符。 */
    private static final String BACKSLASH = "\\";

    /** 图片扩展名与 MIME 精确映射。 */
    private static final Map<String, String> IMAGE_MIME_BY_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    /** 视频扩展名与 MIME 精确映射。 */
    private static final Map<String, String> VIDEO_MIME_BY_EXTENSION = Map.of(
            "mp4", "video/mp4",
            "mov", "video/quicktime");

    /**
     * 校验并归一化整批文件声明。
     *
     * @param files 客户端文件列表
     * @return 完成归一化的文件列表
     */
    List<PreparedUploadFile> prepareFiles(List<MineFeedbackUploadTicketRequest.UploadFileItem> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_FILE_COUNT) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_COUNT_INVALID_MESSAGE);
        }
        Set<String> clientIds = new HashSet<>();
        List<PreparedUploadFile> preparedFiles = new ArrayList<>(files.size());
        for (MineFeedbackUploadTicketRequest.UploadFileItem file : files) {
            PreparedUploadFile prepared = prepareFile(file);
            if (!clientIds.add(prepared.clientId())) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_CLIENT_ID_INVALID_MESSAGE);
            }
            preparedFiles.add(prepared);
        }
        return preparedFiles;
    }

    /**
     * 根据媒体类型返回上传字节限制。
     *
     * @param mediaType 媒体类型字典码
     * @return 最大字节数
     */
    long maxBytes(String mediaType) {
        if (FeedbackMediaTypeDict.IMAGE.getCode().equals(mediaType)) {
            return IMAGE_MAX_BYTES;
        }
        if (FeedbackMediaTypeDict.VIDEO.getCode().equals(mediaType)) {
            return VIDEO_MAX_BYTES;
        }
        throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
    }

    /**
     * 将 MIME 转为无参数的小写标准形式。
     *
     * @param mimeType 原始 MIME
     * @return 归一化 MIME，空值返回空字符串
     */
    String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int parameterIndex = mimeType.indexOf(MIME_PARAMETER_SEPARATOR);
        String baseMime = parameterIndex >= 0 ? mimeType.substring(0, parameterIndex) : mimeType;
        return baseMime.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验持久化附件快照仍符合签发票据时的媒体规则。
     *
     * @param attachment 待校验附件快照
     * @return 媒体类型、扩展名、MIME、大小和时长是否一致
     */
    boolean isValidSnapshotAttachment(FeedbackRoundSnapshot.Attachment attachment) {
        if (attachment == null) {
            return false;
        }
        FeedbackMediaTypeDict mediaType = FeedbackMediaTypeDict.fromCode(attachment.getMediaType());
        if (mediaType == null) {
            return false;
        }
        String objectKey = attachment.getObjectKey();
        int extensionIndex = objectKey == null ? -1 : objectKey.lastIndexOf(EXTENSION_SEPARATOR);
        if (extensionIndex < 0 || extensionIndex == objectKey.length() - 1) {
            return false;
        }
        String extension = objectKey.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
        String normalizedMime = normalizeMime(attachment.getMimeType());
        String expectedMime;
        try {
            expectedMime = expectedMime(mediaType, extension);
        } catch (BusinessException exception) {
            return false;
        }
        if (!normalizedMime.equals(attachment.getMimeType())
                || !expectedMime.equals(normalizedMime)
                || attachment.getSize() <= 0L
                || attachment.getSize() > maxBytes(attachment.getMediaType())) {
            return false;
        }
        if (mediaType == FeedbackMediaTypeDict.IMAGE) {
            return attachment.getDurationMs() == 0L;
        }
        return attachment.getDurationMs() > 0L
                && attachment.getDurationMs() <= VIDEO_MAX_DURATION_MS;
    }

    /** 校验并归一化单个文件声明。 */
    private PreparedUploadFile prepareFile(MineFeedbackUploadTicketRequest.UploadFileItem file) {
        if (file == null) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_INVALID_MESSAGE);
        }
        String clientId = FeedbackIdentifierValidator.normalizePrintableAscii(
                file.getClientId(), MineFeedbackMessage.UPLOAD_CLIENT_ID_INVALID_MESSAGE);
        String mediaType = normalizeText(file.getMediaType());
        FeedbackMediaTypeDict mediaTypeDict = FeedbackMediaTypeDict.fromCode(mediaType);
        if (mediaTypeDict == null) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
        }
        String extension = extractExtension(normalizeText(file.getFileName()));
        String mimeType = normalizeMime(file.getMimeType());
        if (!expectedMime(mediaTypeDict, extension).equals(mimeType)) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
        }
        long fileSize = file.getFileSize() == null ? 0L : file.getFileSize();
        if (fileSize <= 0L || fileSize > maxBytes(mediaType)) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_SIZE_INVALID_MESSAGE);
        }
        long durationMs = normalizeDuration(mediaTypeDict, file.getDurationMs());
        return new PreparedUploadFile(clientId, extension, mediaType, mimeType, fileSize, durationMs);
    }

    /** 从不包含路径的文件名提取小写扩展名。 */
    private String extractExtension(String fileName) {
        if (fileName == null || fileName.contains(PATH_SEPARATOR) || fileName.contains(BACKSLASH)) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_NAME_INVALID_MESSAGE);
        }
        int separatorIndex = fileName.lastIndexOf(EXTENSION_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == fileName.length() - 1) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_NAME_INVALID_MESSAGE);
        }
        return fileName.substring(separatorIndex + 1).toLowerCase(Locale.ROOT);
    }

    /** 查找媒体类型和扩展名对应的唯一合法 MIME。 */
    private String expectedMime(FeedbackMediaTypeDict mediaType, String extension) {
        String expected = mediaType == FeedbackMediaTypeDict.IMAGE
                ? IMAGE_MIME_BY_EXTENSION.get(extension)
                : VIDEO_MIME_BY_EXTENSION.get(extension);
        if (expected == null) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
        }
        return expected;
    }

    /** 校验时长并将图片空时长归一为零。 */
    private long normalizeDuration(FeedbackMediaTypeDict mediaType, Long declaredDurationMs) {
        if (mediaType == FeedbackMediaTypeDict.IMAGE) {
            if (declaredDurationMs != null && declaredDurationMs != 0L) {
                throw new BusinessException(MineFeedbackMessage.UPLOAD_IMAGE_DURATION_INVALID_MESSAGE);
            }
            return 0L;
        }
        long durationMs = declaredDurationMs == null ? 0L : declaredDurationMs;
        if (durationMs <= 0L || durationMs > VIDEO_MAX_DURATION_MS) {
            throw new BusinessException(MineFeedbackMessage.UPLOAD_FILE_DURATION_INVALID_MESSAGE);
        }
        return durationMs;
    }

    /** 去除首尾空白并把空文本归一为空值。 */
    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /**
     * 已完成校验和归一化的单个上传文件。
     *
     * @param clientId 客户端文件幂等标识
     * @param extension 小写扩展名
     * @param mediaType 媒体类型字典码
     * @param mimeType 归一化 MIME
     * @param fileSize 客户端声明字节数
     * @param durationMs 客户端声明时长毫秒
     */
    record PreparedUploadFile(
            String clientId,
            String extension,
            String mediaType,
            String mimeType,
            long fileSize,
            long durationMs
    ) {
    }
}
