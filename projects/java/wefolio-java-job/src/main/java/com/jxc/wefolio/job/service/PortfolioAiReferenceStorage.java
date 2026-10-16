package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.PortfolioAiReferenceStorageConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** 私有参考原件的精确读取与扫描操作，所有远端调用均由专用客户端限时。 */
@Service
public class PortfolioAiReferenceStorage {
    /** 根目录和子目录单页上限。 */
    private static final int PAGE_SIZE = 100;
    /** 有界 SDK 与当前部署桶。 */
    private final COSClient cos;
    private final String bucket;
    /** 仅装配专用有界客户端。 */
    public PortfolioAiReferenceStorage(@Qualifier(PortfolioAiReferenceStorageConfig.CLIENT) COSClient cos, CosProperties properties) {
        this.cos = cos; this.bucket = properties.getBucketName();
    }
    /** 版本桶可能产生删除标记或允许覆盖，本期只支持 Off。 */
    public void requireVersioningOff() {
        BucketVersioningConfiguration versioning = cos.getBucketVersioningConfiguration(bucket);
        if (versioning == null || !BucketVersioningConfiguration.OFF.equals(versioning.getStatus())) {
            throw new IllegalStateException("参考附件要求 COS 版本控制关闭");
        }
    }
    /** 根前缀枚举包含没有数据库有效引用的旧 owner。 */
    public ObjectListing owners(String marker) {
        return cos.listObjects(new ListObjectsRequest().withBucketName(bucket).withDelimiter("/").withPrefix("")
                .withMarker(marker).withMaxKeys(PAGE_SIZE));
    }
    /** 只枚举精确附件子目录，不把作品集其它资源视为候选。 */
    public ObjectListing objects(String ownerCode, String marker) {
        return cos.listObjects(new ListObjectsRequest().withBucketName(bucket)
                .withPrefix(ownerCode + PortfolioAiReferenceContract.DIRECTORY).withMarker(marker).withMaxKeys(PAGE_SIZE));
    }
    /** 只有 404 表示不存在，其余失败明确向上传播。 */
    public ObjectMetadata head(String key) {
        try { return cos.getObjectMetadata(bucket, key); }
        catch (CosServiceException exception) { if (exception.getStatusCode() == 404) { return null; } throw exception; }
    }
    /** 签名只使用官方 COS 源站，不使用可能公开的 CDN。 */
    public String sign(String key, Duration duration) {
        return cos.generatePresignedUrl(bucket, key, Date.from(Instant.now().plus(duration)), HttpMethodName.GET).toExternalForm();
    }
    /** 无版本桶的精确原件删除；404 幂等完成，其它失败留给后续扫描。 */
    public void delete(String key) {
        if (!PortfolioAiReferenceContract.isObjectKey(key)) { throw new IllegalArgumentException("附件对象路径不合法"); }
        try { cos.deleteObject(bucket, key); }
        catch (CosServiceException exception) { if (exception.getStatusCode() != 404) { throw exception; } }
    }
}
