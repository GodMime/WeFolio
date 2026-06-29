package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品编辑请求。
 */
@Data
public class MineWorkUpdateRequest {

    /** 作品标题，最长 30 字 */
    private String title;

    /** 作品说明，最长 1000 字 */
    private String description;

    /** 标签名称列表 */
    private List<String> tagNames = new ArrayList<>();
}
