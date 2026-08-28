package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 反馈附件清理 COS 适配服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackUploadCleanupCosService {

    /** COS 对象不存在错误码 */
    private static final String NO_SUCH_KEY_ERROR_CODE = "NoSuchKey";

    /** COS 对象不存在兼容错误码 */
    private static final String NO_SUCH_OBJECT_ERROR_CODE = "NoSuchObject";

    /** SHA-256 摘要算法名 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 删除失败时暴露给业务层的固定消息 */
    private static final String DELETE_FAILED_MESSAGE = "反馈附件 COS 对象删除失败";

    /** 摘要算法不可用时的固定消息 */
    private static final String DIGEST_UNAVAILABLE_MESSAGE = "反馈附件对象键摘要算法不可用";

    /** 非法删除参数消息 */
    private static final String INVALID_DELETE_ARGUMENT_MESSAGE = "反馈附件 COS 删除参数非法";

    /** 腾讯云 COS 客户端 */
    private final COSClient cosClient;

    /** COS 存储桶配置 */
    private final CosProperties cosProperties;

    /**
     * 删除指定上传任务的 COS 对象。
     *
     * @param taskId 上传任务 ID
     * @param objectKey COS 对象键
     */
    public void delete(Long taskId, String objectKey) {
        if (taskId == null || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException(INVALID_DELETE_ARGUMENT_MESSAGE);
        }
        String objectKeySha256 = sha256(objectKey);
        try {
            cosClient.deleteObject(cosProperties.getBucketName(), objectKey);
            log.info("反馈附件 COS 对象删除成功: taskId={}, objectKeySha256={}",
                    taskId, objectKeySha256);
        } catch (CosServiceException exception) {
            if (isObjectMissing(exception)) {
                log.info("反馈附件 COS 对象已不存在: taskId={}, objectKeySha256={}",
                        taskId, objectKeySha256);
                return;
            }
            log.warn("反馈附件 COS 对象删除失败: taskId={}, objectKeySha256={}",
                    taskId, objectKeySha256);
            throw new IllegalStateException(DELETE_FAILED_MESSAGE);
        } catch (RuntimeException ignored) {
            log.warn("反馈附件 COS 对象删除失败: taskId={}, objectKeySha256={}",
                    taskId, objectKeySha256);
            throw new IllegalStateException(DELETE_FAILED_MESSAGE);
        }
    }

    /**
     * 判断 COS 异常是否明确表示目标对象不存在。
     *
     * @param exception COS 服务异常
     * @return 是否可按幂等删除成功处理
     */
    private boolean isObjectMissing(CosServiceException exception) {
        return NO_SUCH_KEY_ERROR_CODE.equals(exception.getErrorCode())
                || NO_SUCH_OBJECT_ERROR_CODE.equals(exception.getErrorCode());
    }

    /**
     * 计算对象键摘要，日志中不暴露原始对象键。
     *
     * @param objectKey COS 对象键
     * @return 小写 SHA-256 十六进制摘要
     */
    private String sha256(String objectKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(objectKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(DIGEST_UNAVAILABLE_MESSAGE);
        }
    }
}
