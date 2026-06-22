package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_referral_relation — 推荐关系表 — 首次注册推荐绑定，一个用户仅可绑定一次
 */
@Data
@TableName("wf_referral_relation")
public class ReferralRelationEntity extends BaseEntity {

    /** 推荐人用户 ID */
    private Long referrerUserId;

    /** 被推荐用户 ID */
    private Long referredUserId;

    /** 注册时填写的推荐码快照 */
    private String referralCodeSnapshot;

    /** 绑定时间 */
    private LocalDateTime boundAt;

}
