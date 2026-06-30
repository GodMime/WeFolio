package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Data
@Component
@ConfigurationProperties(prefix = "cos")
public class CosProperties {
    /** 访问密钥 ID */
    private String secretId;

    /** 访问密钥 Secret */
    private String secretKey;

    /** COS 地域 */
    private String region;

    /** COS 存储桶名称 */
    private String bucketName;

    /** 小程序直传 POST 上传域名，必须指向可接受上传请求的 COS 域名 */
    private String uploadBaseUrl;

    /** 对外公开访问域名，可配置为 CDN 或 COS 公开域名 */
    private String publicBaseUrl;
}
