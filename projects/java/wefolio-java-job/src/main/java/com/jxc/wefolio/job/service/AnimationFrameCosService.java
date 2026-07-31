package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * job 侧数据万象动图临时审核帧适配服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnimationFrameCosService {

    /** 动图目录固定片段 */
    private static final String ANIMATION_DIRECTORY_SEGMENT = "/work/animation/";

    /** 支持的动图最小帧号 */
    private static final int MIN_FRAME_NUMBER = 1;

    /** 支持的动图最大帧号 */
    private static final int MAX_FRAME_NUMBER = 300;

    /** 单张临时审核帧最大读取字节数 */
    private static final int MAX_AUDIT_FRAME_BYTES = 10 * 1024 * 1024;

    /** 临时帧 MIME */
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";

    /** 可验证 JPEG 首尾魔数的最小字节数 */
    private static final int JPEG_MIN_BYTES = 4;

    /** 领取 token 中 UUID 部分的长度 */
    private static final int CLAIM_UUID_LENGTH = 32;

    /** 临时帧文件扩展名 */
    private static final String JPEG_FILE_EXTENSION = ".jpg";

    private final COSClient cosClient;

    private final CosProperties cosProperties;

    /**
     * 生成确定性命名的临时审核帧。
     *
     * @param task 动图审核任务
     * @param frameNumber 帧号
     * @return 临时 JPG 对象键
     */
    public String generate(WorkAuditTaskEntity task, int frameNumber) {
        validateTask(task, frameNumber);
        String sourceObjectKey = task.getMediaObjectKey();
        String targetObjectKey = buildTargetObjectKey(
                sourceObjectKey, task.getId(), frameNumber, task.getLockedBy());
        String rule = "imageMogr2/frame/" + frameNumber + "/strip/format/jpg";
        try {
            GetObjectRequest request = new GetObjectRequest(cosProperties.getBucketName(), sourceObjectKey);
            request.putCustomQueryParameter(rule, null);
            byte[] jpeg;
            try (COSObject object = cosClient.getObject(request);
                 InputStream input = object.getObjectContent()) {
                jpeg = readAtMost(input, MAX_AUDIT_FRAME_BYTES);
            }
            if (jpeg.length > MAX_AUDIT_FRAME_BYTES || !isJpeg(jpeg)) {
                throw new IllegalArgumentException("animation audit frame response is not a valid jpeg");
            }

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(JPEG_CONTENT_TYPE);
            metadata.setContentLength(jpeg.length);
            PutObjectRequest putRequest = new PutObjectRequest(
                    cosProperties.getBucketName(),
                    targetObjectKey,
                    new ByteArrayInputStream(jpeg),
                    metadata);
            putRequest.setCannedAcl(CannedAccessControlList.PublicRead);
            cosClient.putObject(putRequest);
            log.info("COS animation audit frame success: taskId={}, sourceObjectKey={}, targetObjectKey={}, "
                            + "frameNumber={}, size={}",
                    task.getId(), sourceObjectKey, targetObjectKey, frameNumber, jpeg.length);
            return targetObjectKey;
        } catch (Exception exception) {
            log.warn("COS animation audit frame failed: taskId={}, sourceObjectKey={}, targetObjectKey={}, "
                            + "frameNumber={}",
                    task.getId(), sourceObjectKey, targetObjectKey, frameNumber, exception);
            throw new IllegalStateException("生成动图审核帧失败", exception);
        }
    }

    /**
     * 删除临时审核帧，失败时只记录告警。
     *
     * @param objectKey 临时帧对象键
     * @param taskId 审核任务 ID
     */
    public void deleteQuietly(String objectKey, Long taskId) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            cosClient.deleteObject(cosProperties.getBucketName(), objectKey);
            log.info("COS animation audit frame delete success: taskId={}, objectKey={}", taskId, objectKey);
        } catch (Exception exception) {
            log.warn("COS animation audit frame delete failed: taskId={}, objectKey={}",
                    taskId, objectKey, exception);
        }
    }

    /**
     * 根据源文件和任务信息构造确定性临时帧对象键。
     *
     * @param sourceObjectKey 源动图对象键
     * @param taskId 审核任务 ID
     * @param frameNumber 帧号
     * @return 临时帧对象键
     */
    public String buildTargetObjectKey(String sourceObjectKey, Long taskId, int frameNumber) {
        return buildTargetObjectKeyPrefix(sourceObjectKey, taskId, frameNumber) + JPEG_FILE_EXTENSION;
    }

    /**
     * 按本次唯一领取 token 构造尝试级临时帧对象键，避免租约重抢时互相覆盖或删除。
     *
     * @param sourceObjectKey 源动图对象键
     * @param taskId 审核任务 ID
     * @param frameNumber 帧号
     * @param claimToken 本次领取 token
     * @return 尝试级临时帧对象键
     */
    public String buildTargetObjectKey(
            String sourceObjectKey, Long taskId, int frameNumber, String claimToken) {
        String attemptToken = claimAttemptToken(claimToken);
        return buildTargetObjectKeyPrefix(sourceObjectKey, taskId, frameNumber)
                + "-"
                + attemptToken
                + JPEG_FILE_EXTENSION;
    }

    private String buildTargetObjectKeyPrefix(String sourceObjectKey, Long taskId, int frameNumber) {
        int slashIndex = sourceObjectKey.lastIndexOf('/');
        int dotIndex = sourceObjectKey.lastIndexOf('.');
        if (slashIndex < 0 || dotIndex <= slashIndex + 1) {
            throw new IllegalArgumentException("动图源对象键格式非法");
        }
        String extension = sourceObjectKey.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!"gif".equals(extension) && !"webp".equals(extension)) {
            throw new IllegalArgumentException("动图源对象必须为 GIF 或 WebP");
        }
        String directory = sourceObjectKey.substring(0, slashIndex + 1);
        String stem = sourceObjectKey.substring(slashIndex + 1, dotIndex);
        return directory + stem + "-audit-" + taskId + "-" + frameNumber;
    }

    private String claimAttemptToken(String claimToken) {
        String normalized = claimToken == null ? "" : claimToken.strip();
        int separatorIndex = normalized.lastIndexOf('-');
        String token = separatorIndex < 0 ? normalized : normalized.substring(separatorIndex + 1);
        if (token.length() != CLAIM_UUID_LENGTH
                || !token.chars().allMatch(character ->
                        character >= '0' && character <= '9'
                                || character >= 'a' && character <= 'f'
                                || character >= 'A' && character <= 'F')) {
            throw new IllegalArgumentException("动图审核任务领取 token 非法");
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private void validateTask(WorkAuditTaskEntity task, int frameNumber) {
        if (task == null || task.getId() == null || task.getMediaObjectKey() == null
                || !task.getMediaObjectKey().contains(ANIMATION_DIRECTORY_SEGMENT)
                || task.getLockedBy() == null || task.getLockedBy().isBlank()) {
            throw new IllegalArgumentException("动图审核任务对象键非法");
        }
        if (frameNumber < MIN_FRAME_NUMBER || frameNumber > MAX_FRAME_NUMBER) {
            throw new IllegalArgumentException("动图审核帧号必须在 1 到 300 之间");
        }
    }

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
}
