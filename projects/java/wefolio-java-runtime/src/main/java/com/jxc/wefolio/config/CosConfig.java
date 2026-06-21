package com.jxc.wefolio.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.TransferManagerConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
@RequiredArgsConstructor
public class CosConfig {

    private final CosProperties cosProperties;

    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient() {
        BasicCOSCredentials credentials = new BasicCOSCredentials(
                cosProperties.getSecretId(), cosProperties.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(cosProperties.getRegion()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(30000);
        clientConfig.setSocketTimeout(60000);
        return new COSClient(credentials, clientConfig);
    }

    @Bean(destroyMethod = "shutdownNow")
    public TransferManager transferManager(COSClient cosClient) {
        TransferManager transferManager = new TransferManager(cosClient, Executors.newFixedThreadPool(8));
        TransferManagerConfiguration config = new TransferManagerConfiguration();
        config.setMultipartUploadThreshold(5 * 1024 * 1024L);
        config.setMinimumUploadPartSize(1 * 1024 * 1024L);
        transferManager.setConfiguration(config);
        return transferManager;
    }
}
