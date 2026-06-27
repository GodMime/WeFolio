package com.jxc.wefolio.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_schedule — 档期表 — 具体日期与档位的预约状态，快照存储档位信息
 */
@Data
@TableName("wf_schedule")
public class ScheduleEntity extends BaseEntity {

    /** 档期所属用户 ID */
    private Long userId;

    /** 具体日期 */
    private LocalDate scheduleDate;

    /** 逻辑关联档位定义 ID */
    private Long slotDefinitionId;

    /** 档位名称快照 */
    private String slotNameSnapshot;

    /** 开始时间快照 */
    private LocalTime startTimeSnapshot;

    /** 结束时间快照 */
    private LocalTime endTimeSnapshot;

    /** 展示颜色快照 */
    private String colorSnapshot;

    /** 档期状态：AVAILABLE 空闲 / BOOKED 已约 / TENTATIVE 待定 / REST 休息 */
    private String status;

    /** 联系人姓名，沿用历史字段名存储明文，仅维护者可见 */
    private String contactNameCiphertext;

    /** 联系电话，沿用历史字段名存储明文，仅维护者可见 */
    private String contactPhoneCiphertext;

    /** 内部备注，仅维护者可见 */
    private String note;

    /** 是否锁定档位快照：0 未锁定 / 1 已锁定 */
    private Integer lockedSnapshot;

}
