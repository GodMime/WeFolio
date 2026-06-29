package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 我的作品删除检查响应。
 */
@Data
public class MineWorkDeleteCheckResponse {

    /** 是否可以删除 */
    private boolean canDelete;

    /** 有效引用次数 */
    private long referenceCount;

    /** 删除提示文案 */
    private String message;
}
