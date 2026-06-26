package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 我的消息已读操作请求。
 */
@Data
public class MineMessageReadRequest {

    /** 待标记已读的消息 ID 列表 */
    private List<Long> messageIds;

    /** 全量已读可选分类过滤 */
    private String category;
}
