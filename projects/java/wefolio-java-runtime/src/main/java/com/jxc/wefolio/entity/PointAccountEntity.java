package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;


/**
 * wf_point_account — 积分账户表 — 每个用户唯一一个积分账户
 */
@Data
@TableName("wf_point_account")
public class PointAccountEntity extends BaseEntity {

    /** 用户 ID，团队不创建积分账户 */
    private Long userId;

    /** 实际可用积分，等于微信总代币余额减待扣代币 */
    private Long balance;

    /** 微信总代币余额快照 */
    private Long wechatBalance;

    /** 微信赠送代币余额快照 */
    private Long wechatPresentBalance;

    /** 当前待扣代币聚合 */
    private Long pendingDebit;

    /** 最近微信权威余额同步时间 */
    private LocalDateTime wechatBalanceSyncedAt;

    /** 累计充值获得积分 */
    private Long totalRecharged;

    /** 累计赠送积分 */
    private Long totalGifted;

    /** 累计消耗积分（正数值） */
    private Long totalConsumed;

}
