package com.jxc.wefolio.entity;

import java.time.LocalTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_slot_definition — 档位定义表 — 可复用的档位模板，如中午档、晚间档
 */
@Data
@TableName("wf_slot_definition")
public class SlotDefinitionEntity extends BaseEntity {

    /** 所属用户 ID */
    private Long userId;

    /** 档位名称，如中午档、晚间档、迎亲档 */
    private String name;

    /** 默认开始时间 */
    private LocalTime startTime;

    /** 默认结束时间 */
    private LocalTime endTime;

    /** 十六进制展示颜色，用于月历多色彩标记 */
    private String color;

    /** 展示顺序 */
    private Integer sortOrder;

    /** 是否系统初始化默认档位：0 否 / 1 是 */
    private Integer isSystemDefault;

    /** 状态：ACTIVE 启用 / DISABLED 停用 */
    private String status;

}
