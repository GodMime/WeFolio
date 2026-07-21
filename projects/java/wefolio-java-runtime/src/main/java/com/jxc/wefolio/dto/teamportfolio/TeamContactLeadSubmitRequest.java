package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 团队作品集访客预留联系信息请求。
 */
@Data
public class TeamContactLeadSubmitRequest {

    /** 已验证访问记录 ID。 */
    private Long visitRecordId;

    /** 联系人姓名。 */
    private String contactName;

    /** 手机号。 */
    private String phone;

    /** 微信号。 */
    private String wechat;

    /** 意向档期。 */
    private String desiredSchedule;

    /** 需求描述。 */
    private String needs;

    /** 客户端来源类型，仅兼容接收，不作为可信来源。 */
    private String sourceType;

    /** 隐私同意版本。 */
    private String consentVersion;

    /** 提交幂等键。 */
    private String idempotencyKey;
}
