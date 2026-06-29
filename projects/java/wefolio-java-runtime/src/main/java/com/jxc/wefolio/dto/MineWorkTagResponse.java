package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的作品标签响应。
 */
@Data
public class MineWorkTagResponse {

    /** 标签列表 */
    private List<MineWorkListResponse.TagItem> tags = new ArrayList<>();
}
