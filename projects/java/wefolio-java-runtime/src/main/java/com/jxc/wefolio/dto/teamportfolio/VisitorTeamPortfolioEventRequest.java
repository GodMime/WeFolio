package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 标准团队作品集访客事件请求。
 */
@Data
public class VisitorTeamPortfolioEventRequest {

    /** 事件类型。 */
    private String eventType;

    /** 相关作品 ID。 */
    private Long workId;

    /** 事件来源组件实例键。 */
    private String componentKey;

    /** 成员个人作品集 ID。 */
    private Long memberPortfolioId;

    /** 服务端线索提交事件关联的线索 ID。 */
    private Long leadId;

    /** 作品媒体类型。 */
    private String mediaType;

    /** 二维码交互动作。 */
    private String action;

    /** 服务端打开事件记录的原始来源类型。 */
    private String sourceType;

    /** 查询档期日期。 */
    private LocalDate queriedDate;

    /** 停留或播放秒数。 */
    private Integer durationSeconds;

    /** 事件幂等键。 */
    private String idempotencyKey;

    /** 不含敏感内容的扩展元数据。 */
    private Map<String, Object> metadata;
}
