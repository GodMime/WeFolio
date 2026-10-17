package com.jxc.wefolio.dto;

import lombok.Data;
import java.util.List;

/** 作品集字体资源及同快照版本映射。 */
@Data
public class PortfolioFontManifestDto {
        /** 清单格式版本。 */
        private Integer formatVersion;
        /** 逻辑需求算法版本。 */
        private Integer planVersion;
        /** 全部菜单逻辑需求摘要。 */
        private String planHash;
        /** 同配置快照的固定字体版本表。 */
        private Object versions;
        /** 已校验上传成功的资源。 */
        private List<Asset> assets;
        /** 不可用的需求。 */
        private List<Unavailable> unavailable;

    /** 可信字体产物。 */
    @Data
    public static class Asset {
        /** 不可变物理产物标识。 */
        private String assetId;
        /** 字体标识。 */
        private String fontId;
        /** 固定字体版本。 */
        private String fontVersion;
        /** 实际字体字重。 */
        private Integer fontWeight;
        /** 字体样式。 */
        private String fontStyle;
        /** 产物构建摘要。 */
        private String subsetHash;
        /** 字符需求摘要。 */
        private String demandHash;
        /** 可信产物状态。 */
        private String status;
        /** 字体格式。 */
        private String format;
        /** 最终文件字节数。 */
        private Long bytes;
        /** 公开不可变地址。 */
        private String url;
        /** 服务端对象键，仅持久化使用。 */
        private String objectKey;
        /** 受控存储桶身份，不使用客户端提供的值。 */
        private String bucket;
    }

    /** 不可用字体需求。 */
    @Data
    public static class Unavailable {
        /** 字体标识。 */
        private String fontId;
        /** 固定字体版本，可缺失。 */
        private String fontVersion;
        /** 请求字重。 */
        private Integer fontWeight;
        /** 字体样式。 */
        private String fontStyle;
        /** 不可用状态。 */
        private String status;
        /** 不可用原因。 */
        private String reason;
    }
}
