package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * 历史用户目录占位对象 COS 服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserStorageFolderCosService {

    /** COS 目录对象 MIME */
    private static final String DIRECTORY_CONTENT_TYPE = "application/x-directory";

    private final COSClient cosClient;

    private final CosProperties cosProperties;

    /**
     * 精确检查目录占位对象是否存在。
     *
     * @param uniqueCode 用户唯一码
     * @param relativeFolder 固定相对目录
     * @return 是否存在
     */
    public boolean exists(String uniqueCode, String relativeFolder) {
        String objectKey = buildFolderObjectKey(uniqueCode, relativeFolder);
        return cosClient.doesObjectExist(
                cosProperties.getBucketName(), objectKey);
    }

    /**
     * 创建 0 字节目录占位对象。
     *
     * @param uniqueCode 用户唯一码
     * @param relativeFolder 固定相对目录
     */
    public void create(String uniqueCode, String relativeFolder) {
        String objectKey = buildFolderObjectKey(uniqueCode, relativeFolder);
        byte[] emptyContent = new byte[0];
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(0);
        metadata.setContentType(DIRECTORY_CONTENT_TYPE);
        PutObjectRequest request = new PutObjectRequest(
                cosProperties.getBucketName(),
                objectKey,
                new ByteArrayInputStream(emptyContent),
                metadata);
        cosClient.putObject(request);
        log.info("历史用户 COS 目录创建成功: objectKey={}", objectKey);
    }

    /**
     * 构造完整目录对象键。
     *
     * @param uniqueCode 用户唯一码
     * @param relativeFolder 相对目录
     * @return 完整对象键
     */
    public String buildFolderObjectKey(String uniqueCode, String relativeFolder) {
        if (uniqueCode == null || uniqueCode.isBlank()
                || relativeFolder == null || relativeFolder.isBlank()
                || relativeFolder.startsWith("/")
                || !relativeFolder.endsWith("/")
                || relativeFolder.contains("..")) {
            throw new IllegalArgumentException("用户 COS 目录参数非法");
        }
        return uniqueCode.strip() + "/" + relativeFolder;
    }
}
