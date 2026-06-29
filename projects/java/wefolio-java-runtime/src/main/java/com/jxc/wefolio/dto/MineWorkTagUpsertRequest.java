package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 我的作品标签新增或编辑请求。
 */
@Data
public class MineWorkTagUpsertRequest {

    /** 标签名称 */
    private String name;

    /** 标签颜色，必须来自固定色板 */
    private String color;
}
