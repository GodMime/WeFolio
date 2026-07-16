package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jxc.wefolio.dict.BillingWindowScopeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_point_billing_window — 个人作品集访客滚动扣费窗口表。
 */
@Data
@TableName("wf_point_billing_window")
public class PointBillingWindowEntity extends BaseEntity {

    /** 最近一次扣费使用的积分账户 ID */
    private Long accountId;

    /** 被扣费维护者用户 ID */
    private Long userId;

    /** 全局访客 ID */
    private Long visitorId;

    /**
     * 访客扣费积分场景。
     *
     * @see PointSceneCodeDict
     */
    private String sceneCode;

    /**
     * 扣费窗口作用域。
     *
     * @see BillingWindowScopeDict
     */
    private String scopeType;

    /** 作品集 ID 或作品 ID */
    private Long scopeId;

    /** 最近一次成功扣费时间 */
    private LocalDateTime lastChargedAt;

    /** 最近一次积分流水 ID */
    private Long pointTransactionId;
}
