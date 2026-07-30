package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineWorkMessage;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.transfer.TransferManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 数据万象动图适配服务 — 读取权威元数据并生成静态帧对象。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnimationCosService {

    /** 数据万象图片信息查询参数。 */
    private static final String IMAGE_INFO_QUERY = "imageInfo";

    /** 图片信息响应最大字节数。 */
    private static final int IMAGE_INFO_MAX_BYTES = 64 * 1024;

    /** 动图最小帧数。 */
    public static final int ANIMATION_MIN_FRAME_COUNT = 2;

    /** 动图最大帧数。 */
    public static final int ANIMATION_MAX_FRAME_COUNT = 300;

    /** 持久封面最长边限制。 */
    private static final int COVER_MAX_SIDE = 1280;

    /** 持久封面最大字节数。 */
    private static final int COVER_MAX_BYTES = 100 * 1024;

    /** 静态帧输出 MIME。 */
    private static final String COVER_CONTENT_TYPE = "image/jpeg";

    /** 可验证 JPEG 首尾魔数的最小字节数。 */
    private static final int JPEG_MIN_BYTES = 4;

    /** SHA-256 算法名称。 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** COS 传输管理器。 */
    private final TransferManager transferManager;

    /** COS 配置。 */
    private final CosProperties cosProperties;

    /**
     * 通过数据万象 imageInfo 读取动图权威元数据。
     *
     * @param objectKey 原文件对象键
     * @return 权威元数据
     */
    public AnimationMetadata inspect(String objectKey) {
        try {
            GetObjectRequest request = new GetObjectRequest(cosProperties.getBucketName(), objectKey);
            request.putCustomQueryParameter(IMAGE_INFO_QUERY, null);
            byte[] payload;
            try (COSObject object = transferManager.getCOSClient().getObject(request);
                 InputStream input = object.getObjectContent()) {
                payload = readAtMost(input, IMAGE_INFO_MAX_BYTES);
            }
            if (payload.length == 0 || payload.length > IMAGE_INFO_MAX_BYTES) {
                throw new IllegalArgumentException("imageInfo response size is invalid");
            }
            JSONObject json = JSON.parseObject(new String(payload, StandardCharsets.UTF_8));
            AnimationMetadata metadata = new AnimationMetadata(
                    normalizeFormat(json.getString("format")),
                    requirePositiveInt(json, "width"),
                    requirePositiveInt(json, "height"),
                    requirePositiveInt(json, "frame_count"));
            log.info("COS animation imageInfo success: objectKey={}, format={}, width={}, height={}, frameCount={}",
                    objectKey,
                    metadata.format(),
                    metadata.width(),
                    metadata.height(),
                    metadata.frameCount());
            return metadata;
        } catch (Exception exception) {
            log.warn("COS animation imageInfo failed: objectKey={}", objectKey, exception);
            throw new BusinessException(MineWorkMessage.ANIMATION_METADATA_READ_FAILED_MESSAGE, exception);
        }
    }

    /**
     * 通过下载时处理生成持久静态封面。
     *
     * @param sourceObjectKey 原动图对象键
     * @param targetObjectKey 目标 JPG 对象键
     * @param frameNumber 帧序号
     * @return 已生成封面信息
     */
    public GeneratedFrame generateCover(
            String sourceObjectKey,
            String targetObjectKey,
            int frameNumber
    ) {
        if (frameNumber < 1 || frameNumber > ANIMATION_MAX_FRAME_COUNT) {
            throw new BusinessException(MineWorkMessage.ANIMATION_COVER_GENERATE_FAILED_MESSAGE);
        }
        String rule = "imageMogr2/frame/" + frameNumber
                + "/thumbnail/" + COVER_MAX_SIDE + "x" + COVER_MAX_SIDE
                + ">/strip/format/jpg/size-limit/100k!";
        try {
            GetObjectRequest request = new GetObjectRequest(cosProperties.getBucketName(), sourceObjectKey);
            request.putCustomQueryParameter(rule, null);
            byte[] jpeg;
            try (COSObject object = transferManager.getCOSClient().getObject(request);
                 InputStream input = object.getObjectContent()) {
                jpeg = readAtMost(input, COVER_MAX_BYTES);
            }
            if (jpeg.length > COVER_MAX_BYTES || !isJpeg(jpeg)) {
                throw new IllegalArgumentException("animation frame response is not a valid jpeg");
            }

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(COVER_CONTENT_TYPE);
            metadata.setContentLength(jpeg.length);
            PutObjectRequest putRequest = new PutObjectRequest(
                    cosProperties.getBucketName(),
                    targetObjectKey,
                    new ByteArrayInputStream(jpeg),
                    metadata);
            putRequest.setCannedAcl(CannedAccessControlList.PublicRead);
            transferManager.getCOSClient().putObject(putRequest);

            GeneratedFrame generatedFrame = new GeneratedFrame(
                    targetObjectKey,
                    COVER_CONTENT_TYPE,
                    jpeg.length,
                    sha256Hex(jpeg));
            log.info("COS animation cover success: sourceObjectKey={}, targetObjectKey={}, frameNumber={}, size={}",
                    sourceObjectKey, targetObjectKey, frameNumber, jpeg.length);
            return generatedFrame;
        } catch (Exception exception) {
            log.warn("COS animation cover failed: sourceObjectKey={}, targetObjectKey={}, frameNumber={}",
                    sourceObjectKey, targetObjectKey, frameNumber, exception);
            throw new BusinessException(MineWorkMessage.ANIMATION_COVER_GENERATE_FAILED_MESSAGE, exception);
        }
    }

    /**
     * 补偿删除对象，删除失败时只记录告警。
     *
     * @param objectKey 已解析的明确对象键
     * @param context 业务上下文
     */
    public void deleteQuietly(String objectKey, String context) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            transferManager.getCOSClient().deleteObject(cosProperties.getBucketName(), objectKey);
            log.info("COS animation object delete success: objectKey={}, context={}", objectKey, context);
        } catch (Exception exception) {
            log.warn("COS animation object delete failed: objectKey={}, context={}",
                    objectKey, context, exception);
        }
    }

    /**
     * 有界读取输入流。
     *
     * @param input 输入流
     * @param maxBytes 最大允许字节数
     * @return 最多多读取一个字节的响应
     * @throws Exception 读取失败时抛出
     */
    private byte[] readAtMost(InputStream input, int maxBytes) throws Exception {
        if (input == null) {
            throw new IllegalArgumentException("COS response body is missing");
        }
        return input.readNBytes(maxBytes + 1);
    }

    /**
     * 校验 JPEG SOI/EOI 魔数，拒绝数据万象返回的小体积错误体。
     *
     * @param content 待校验字节
     * @return 是否具有完整 JPEG 首尾标记
     */
    private boolean isJpeg(byte[] content) {
        return content != null
                && content.length >= JPEG_MIN_BYTES
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[content.length - 2] & 0xff) == 0xff
                && (content[content.length - 1] & 0xff) == 0xd9;
    }

    /**
     * 从 JSON 读取正整数。
     *
     * @param json JSON 对象
     * @param field 字段名
     * @return 正整数
     */
    private int requirePositiveInt(JSONObject json, String field) {
        String value = json == null ? null : json.getString(field);
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return parsed;
    }

    /**
     * 归一化格式名称。
     *
     * @param format 原始格式
     * @return 小写格式
     */
    private String normalizeFormat(String format) {
        String normalized = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("format is missing");
        }
        return normalized;
    }

    /**
     * 计算 SHA-256。
     *
     * @param bytes 文件字节
     * @return 小写十六进制摘要
     * @throws Exception 摘要算法不可用时抛出
     */
    private String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    /**
     * 动图权威元数据。
     *
     * @param format 格式
     * @param width 宽度
     * @param height 高度
     * @param frameCount 帧数
     */
    public record AnimationMetadata(String format, int width, int height, int frameCount) {
    }

    /**
     * 已生成静态帧信息。
     *
     * @param objectKey 对象键
     * @param contentType MIME
     * @param contentLength 字节数
     * @param sha256 SHA-256
     */
    public record GeneratedFrame(
            String objectKey,
            String contentType,
            long contentLength,
            String sha256
    ) {
    }
}
