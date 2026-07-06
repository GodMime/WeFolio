package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访客联系线索提交请求。
 */
@Data
public class ContactLeadSubmitRequest {

    /** 旧版客户端兼容字段，服务端已改用访客认证上下文并忽略该值 */
    @Deprecated
    private String visitorKey;

    /** 访问汇总记录 ID */
    private Long visitRecordId;

    /** 联系人 */
    private String contactName;

    /** 手机号 */
    private String phone;

    /** 微信号 */
    private String wechat;

    /** 意向档期 */
    private String desiredSchedule;

    /** 需求描述 */
    private String needs;

    /** 来源类型 */
    private String sourceType;

    /** 隐私说明版本 */
    private String consentVersion;

    /** 提交幂等键 */
    private String idempotencyKey;
}
