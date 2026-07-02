package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 作品集分享记录请求。
 */
@Data
public class MinePortfolioShareRecordRequest {

    /** 分享渠道：WECHAT_CARD / QR_CODE / COPIED_PATH */
    private String shareChannel;

    /** 分享场景 */
    private String shareScene;
}
