package com.jxc.wefolio.message;

/** 访客活动会话错误提示。 */
public interface VisitActivityMessage {
    /** 采集协议或双键格式不合法。 */
    String INVALID_TRACKING = "访问活动采集参数不合法";
    /** 会话不属于当前访客或作品集。 */
    String SESSION_UNAVAILABLE = "访问活动会话不可用";
    /** 活动键与首次打开键不匹配。 */
    String KEY_CONFLICT = "访问活动幂等键已用于其他业务";
    /** 累计值超出合理范围。 */
    String INVALID_DURATION = "前台停留时长不合法";
    /** 事务内写入失败。 */
    String PERSISTENCE_FAILED = "访问活动记录保存失败";
}
