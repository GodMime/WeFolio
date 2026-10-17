package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import com.jxc.wefolio.service.CosService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 附件专用有界 COS 客户端，不改变现有素材上传和读取的连接预算。 */
@Service
public class PortfolioAiReferenceStorage {
    /** 每次请求绝对五秒；禁止 SDK 重试在 owner 锁内累积等待。 */
    private static final int TIMEOUT_MS=5000;
    /** 同一部署固定桶，配置不能改变已保存对象的归属。 */
    private final String bucket;
    /** 不注册为通用 COSClient Bean，避免影响旧客户端装配。 */
    private final COSClient client;

    /** 创建独立的 HTTPS 短超时客户端。 */
    @Autowired
    public PortfolioAiReferenceStorage(CosProperties properties) {
        this(properties.getBucketName(),createClient(properties));
    }
    /** 测试注入客户端，禁止测试访问真实 COS。 */
    PortfolioAiReferenceStorage(String bucket,COSClient client) {this.bucket=bucket;this.client=client;}
    /** 统一构造可验证的连接预算。 */
    static COSClient createClient(CosProperties properties) {
        ClientConfig config=new ClientConfig(new Region(properties.getRegion()));
        config.setHttpProtocol(HttpProtocol.https);config.setConnectionTimeout(TIMEOUT_MS);config.setConnectionRequestTimeout(TIMEOUT_MS);
        config.setSocketTimeout(TIMEOUT_MS);config.setRequestTimeout(TIMEOUT_MS);config.setRequestTimeOutEnable(true);
        config.setMaxErrorRetry(0);config.setChangeEndpointRetry(false);
        return new COSClient(new BasicCOSCredentials(properties.getSecretId(),properties.getSecretKey()),config);
    }
    /** 404 为不存在，其它错误不能解释为可删除或可签票。 */
    public CosService.VersionedObjectHead head(String key) {
        try {
            ObjectMetadata metadata=client.getObjectMetadata(bucket,key);
            return new CosService.VersionedObjectHead(metadata.getContentType(),metadata.getContentLength(),metadata.getETag(),metadata.getLastModified()==null?0:metadata.getLastModified().getTime());
        } catch(CosServiceException exception) {if(exception.getStatusCode()==404)return null;throw exception;}
    }
    /** 开启或挂起过版本控制都不能保证不可覆盖，必须拒绝附件操作。 */
    public void ensureImmutable() {
        BucketVersioningConfiguration state=client.getBucketVersioningConfiguration(bucket);
        if(state==null||!BucketVersioningConfiguration.OFF.equals(state.getStatus()))throw new BusinessException(PortfolioAiReferenceMessage.STORAGE);
    }
    /** 每次删除都重新检查桶前提，避免只写删除标记而遗留历史原件。 */
    public void delete(String key) {ensureImmutable();client.deleteObject(bucket,key);}
    /** 进程关闭释放附件专用 HTTP 连接池。 */
    @PreDestroy public void close() {client.shutdown();}
}
