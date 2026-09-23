package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 字体生成和一次性删除各自的有界资源配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "portfolio.fonts")
public class PortfolioFontProperties {
    /** 生成总开关；节点工具链通过准入后才启用。 */
    private boolean enabled;
    /** 独立预校验开关，允许尚未开放生成时完成节点准入。 */
    private boolean validationEnabled;
    /** 独立字体告警开关与接收配置，默认不向任何机器人发送。 */
    private Alert alert = new Alert();
    /** 字体告警专用配置，部署时可复用既有飞书机器人地址。 */
    @Data
    public static class Alert {
        /** 是否发送字体节点故障通知。 */
        private boolean enabled;
        /** 独立配置的飞书机器人地址，不从其它业务隐式继承。 */
        private String webhookUrl;
        /** 可选签名密钥。 */
        private String webhookSecret;
        /** 运维配置的非敏感节点标识。 */
        private String nodeId = "runtime";
    }
    /** 删除开关，关闭期间不建立补删待办。 */
    private boolean cleanupEnabled = true;
    /** 包含排队、裁剪和上传的总预算。 */
    private long prepareBudgetMs = 2000;
    /** 提交后删除的独立总预算。 */
    private long deleteBudgetMs = 1000;
    /** 同节点允许的生成请求并发。 */
    private int concurrency = 2;
    /** 单请求全部 WOFF 的体积上限。 */
    private long maxOutputBytes = 8 * 1024 * 1024;
    /** 流式提取源文件的私有工作目录。 */
    private String directory = System.getProperty("java.io.tmpdir") + "/wefolio-fonts";
    /** 固定版本 Python 可执行路径。 */
    private String python = "python3";
    /** 固定版本 HarfBuzz 可执行路径。 */
    private String harfbuzz = "hb-subset";
    /** 部署准入要求的 HarfBuzz 版本输出摘要。 */
    private String toolchainHash;
    /** 发布时要求的源清单和工具链组合摘要。 */
    private String expectedBuildId;
}
