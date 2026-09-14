package com.jxc.wefolio.dto;

import lombok.Data;

/** 打开作品集时可选的前台活动采集协议；未提供时保留旧客户端契约。 */
@Data
public class VisitActivityTrackingDto {
    /** 当前支持的协议版本为 1。 */
    private Integer version;
    /** 同一次真实浏览中固定的随机会话键。 */
    private String clientSessionKey;
    /** 可缺省的首次设备快照。 */
    private DeviceInfo device;

    /** 设备公开信息，不包含设备唯一标识。 */
    @Data
    public static class DeviceInfo {
        /** 品牌，最多 64 个字符。 */
        private String brand;
        /** 型号，最多 128 个字符。 */
        private String model;
        /** 系统，最多 128 个字符。 */
        private String system;
        /** 平台，最多 32 个字符。 */
        private String platform;
    }
}
