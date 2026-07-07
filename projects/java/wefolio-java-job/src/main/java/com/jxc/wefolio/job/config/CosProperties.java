package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * COS 配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cos")
public class CosProperties {

    /** 腾讯云访问密钥 ID */
    private String secretId;

    /** 腾讯云访问密钥 Secret */
    private String secretKey;

    /** COS 地域 */
    private String region;

    /** COS 存储桶名称 */
    private String bucketName;

    /** 小程序直传上传域名 */
    private String uploadBaseUrl;

    /** COS 或 CDN 对外访问域名 */
    private String publicBaseUrl;
}
