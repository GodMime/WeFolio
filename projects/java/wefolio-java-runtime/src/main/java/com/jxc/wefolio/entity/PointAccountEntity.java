package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_point_account — 积分账户表 — 每个用户唯一一个积分账户
 */
@Data
@TableName("wf_point_account")
public class PointAccountEntity extends BaseEntity {

    /** 用户 ID，团队不创建积分账户 */
    private Long userId;

    /** 当前可用积分 */
    private Long balance;

    /** 累计充值获得积分 */
    private Long totalRecharged;

    /** 累计赠送积分 */
    private Long totalGifted;

    /** 累计消耗积分（正数值） */
    private Long totalConsumed;

}
