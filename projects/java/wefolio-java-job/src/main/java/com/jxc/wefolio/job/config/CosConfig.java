package com.jxc.wefolio.job.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * COS 客户端配置。
 */
@Configuration
@RequiredArgsConstructor
public class CosConfig {

    /** 秒到毫秒的换算值 */
    private static final int MILLIS_PER_SECOND = 1000;

    private final CosProperties cosProperties;

    private final WorkAuditProperties workAuditProperties;

    /**
     * 创建腾讯云 COS SDK 客户端。
     *
     * @return COS 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient() {
        BasicCOSCredentials credentials = new BasicCOSCredentials(
                cosProperties.getSecretId(), cosProperties.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(cosProperties.getRegion()));
        int timeoutMillis = workAuditProperties.getRemoteCallTimeoutSeconds() * MILLIS_PER_SECOND;
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(timeoutMillis);
        clientConfig.setSocketTimeout(timeoutMillis);
        return new COSClient(credentials, clientConfig);
    }
}
