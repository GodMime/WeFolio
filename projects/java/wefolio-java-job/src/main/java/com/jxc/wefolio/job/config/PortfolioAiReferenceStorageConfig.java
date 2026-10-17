package com.jxc.wefolio.job.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 附件元数据、列举和删除共用有界 COS 连接，持锁期间不允许 SDK 无限重试。 */
@Configuration
public class PortfolioAiReferenceStorageConfig {
    /** 附件存储连接的明确装配名称。 */
    public static final String CLIENT = "portfolioAiReferenceCosClient";
    /** 每个远端操作最多五秒，错误由下一轮扫描补偿。 */
    private static final int TIMEOUT_MS = 5000;
    /** 只创建固定客户端，不引入可配置存储平台。 */
    @Bean(name = CLIENT, destroyMethod = "shutdown")
    public COSClient referenceCosClient(CosProperties properties) {
        ClientConfig config = new ClientConfig(new Region(properties.getRegion()));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(TIMEOUT_MS); config.setConnectionRequestTimeout(TIMEOUT_MS);
        config.setSocketTimeout(TIMEOUT_MS); config.setRequestTimeout(TIMEOUT_MS);
        config.setRequestTimeOutEnable(true); config.setMaxErrorRetry(0); config.setChangeEndpointRetry(false);
        return new COSClient(new BasicCOSCredentials(properties.getSecretId(), properties.getSecretKey()), config);
    }
}
