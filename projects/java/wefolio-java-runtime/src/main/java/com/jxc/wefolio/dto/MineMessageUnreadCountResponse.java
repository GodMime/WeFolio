package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 我的消息未读统计响应。
 */
@Data
public class MineMessageUnreadCountResponse {

    /** 当前用户未读消息总数 */
    private Long unreadCount;

    /** 当前用户团队分类未读数 */
    private Long teamUnreadCount;

    /** 当前用户积分分类未读数 */
    private Long pointUnreadCount;
}
