package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 我的消息列表响应 — 汇总未读数量、分页游标和系统消息明细。
 */
@Data
public class MineMessageListResponse {

    /** 列表摘要 */
    private Summary summary;

    /** 消息列表 */
    private List<MessageItem> messages;

    /** 是否还有下一页 */
    private boolean hasMore;

    /** 下一页游标，取最后一条消息 ID */
    private Long nextCursor;

    /**
     * 消息列表摘要。
     */
    @Data
    public static class Summary {

        /** 当前用户未读消息总数 */
        private Long unreadCount;
    }

    /**
     * 消息明细项。
     */
    @Data
    public static class MessageItem {

        /** 消息 ID */
        private Long messageId;

        /** 消息类型 */
        private String messageType;

        /** 消息分类 */
        private String category;

        /** 已读状态 */
        private String readStatus;

        /** 是否未读 */
        private boolean unread;

        /** 消息标题 */
        private String title;

        /** 消息正文 */
        private String content;

        /** 动作类型 */
        private String actionType;

        /** 动作路径 */
        private String actionUrl;

        /** 业务来源类型 */
        private String bizType;

        /** 业务来源 ID */
        private Long bizId;

        /** 创建时间展示文案 */
        private String createdAtText;
    }
}
