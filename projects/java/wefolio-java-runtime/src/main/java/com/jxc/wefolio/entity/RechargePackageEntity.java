package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_recharge_package — 充值档位表 — 可配置、可按时间生效的充值套餐
 */
@Data
@TableName("wf_recharge_package")
public class RechargePackageEntity extends BaseEntity {

    /** 稳定档位编码，使用英文大写下划线 */
    private String packageCode;

    /** 同一档位编码内递增版本 */
    private Integer packageVersion;

    /** 展示名称 */
    private String packageName;

    /** 支付金额，单位分 */
    private Integer amountFen;

    /** 基础到账积分 */
    private Integer basePoints;

    /** 赠送积分 */
    private Integer bonusPoints;

    /** 总到账积分（base + bonus） */
    private Integer totalPoints;

    /** 展示顺序 */
    private Integer sortOrder;

    /** 生效开始时间 */
    private LocalDateTime effectiveFrom;

    /** 生效结束时间，空表示长期有效 */
    private LocalDateTime effectiveTo;

    /** 状态：ACTIVE 启用 / DISABLED 停用 */
    private String status;

}
