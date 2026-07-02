package com.jxc.wefolio.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 访客联系线索提交响应。
 */
@Data
public class ContactLeadSubmitResponse {

    /** 线索 ID */
    private Long leadId;

    /** 提交时间 */
    private LocalDateTime submittedAt;
}
