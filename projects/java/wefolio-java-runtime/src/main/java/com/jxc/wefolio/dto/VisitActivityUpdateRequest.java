package com.jxc.wefolio.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 前台停留累计上报；使用十进制类型保留原始数值，交由服务拒绝小数及溢出。 */
@Data
public class VisitActivityUpdateRequest {
    /** 当前会话自开始以来累计的前台毫秒数，必须是非负整数。 */
    private BigDecimal activeDurationMs;
}
