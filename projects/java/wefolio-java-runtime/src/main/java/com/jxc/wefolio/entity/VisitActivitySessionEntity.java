package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** wf_visit_activity_session — 一次真实浏览的前台累计高水位和首次设备快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_visit_activity_session")
public class VisitActivitySessionEntity extends BaseEntity {
    /** 服务端认证的全局访客 ID。 */
    private Long visitorId;
    /** 服务端解析的作品集 ID。 */
    private Long portfolioId;
    /** 作品集类型，见 {@link PortfolioTypeDict}。 */
    private String portfolioType;
    /** 所属访问汇总 ID。 */
    private Long visitRecordId;
    /** 同次浏览固定的客户端活动键。 */
    private String clientSessionKey;
    /** 首次打开时绑定的事件幂等键。 */
    private String openIdempotencyKey;
    /** 当前会话已接受的累计前台毫秒数。 */
    private Long activeDurationMs;
    /** 最后一次成功接收 activity 的时间；尚未采集为 NULL。 */
    private LocalDateTime lastReportedAt;
    /** 首次设备品牌。 */
    private String brand;
    /** 首次设备型号。 */
    private String model;
    /** 首次设备系统。 */
    @TableField("`system`")
    private String system;
    /** 首次设备平台。 */
    private String platform;
}
