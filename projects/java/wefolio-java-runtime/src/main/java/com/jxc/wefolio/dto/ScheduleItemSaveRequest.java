package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 档期记录保存请求。
 */
@Data
public class ScheduleItemSaveRequest {

    /** 档期 ID；为空时新增档期，同日同档位重复新增会报错 */
    private Long scheduleId;

    /** 档期日期，格式 yyyy-MM-dd */
    private String scheduleDate;

    /** 档位定义 ID */
    private Long slotDefinitionId;

    /** 档期状态 */
    private String status;

    /** 联系人姓名 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 档期备注 */
    private String note;
}
