package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 访客作品集事件上报请求。
 */
@Data
public class VisitorPortfolioEventRequest {

    /** 旧版客户端兼容字段，服务端已改用访客认证上下文并覆盖该值 */
    @Deprecated
    private String visitorKey;

    /** 事件类型 */
    private String eventType;

    /** 相关作品 ID */
    private Long workId;

    /** 媒体类型 */
    private String mediaType;

    /** 查询档期日期 */
    private LocalDate queriedDate;

    /** 停留或播放秒数 */
    private Integer durationSeconds;

    /** 事件幂等键 */
    private String idempotencyKey;

    /** 扩展元数据 */
    private Map<String, Object> metadata;
}
