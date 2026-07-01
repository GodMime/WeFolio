package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品批量删除请求。
 */
@Data
public class MineWorkBatchDeleteRequest {

    /** 作品 ID 列表 */
    private List<Long> workIds = new ArrayList<>();
}
