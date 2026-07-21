package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 访问明细分页响应。
 */
@Data
public class MineVisitRecordPageResponse {

    /** 当前页码，从 1 开始 */
    private Integer pageNo;

    /** 当前页大小 */
    private Integer pageSize;

    /** 是否还有下一页 */
    private Boolean hasMore;

    /** 当前页访问明细 */
    private List<MineVisitRecordsResponse.Record> records;
}
