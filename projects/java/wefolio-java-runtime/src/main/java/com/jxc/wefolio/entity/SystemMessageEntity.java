package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_system_message — 系统消息表 — 承载用户站内系统提醒。
 */
@Data
@TableName("wf_system_message")
public class SystemMessageEntity extends BaseEntity {

    /** 消息所属用户 ID */
    private Long userId;

    /** 消息类型：参见 MessageTypeDict */
    private String messageType;

    /** 消息分类：参见 MessageCategoryDict */
    private String category;

    /** 已读状态：UNREAD 未读 / READ 已读 */
    private String readStatus;

    /** 消息标题 */
    private String title;

    /** 消息正文 */
    private String content;

    /** 动作类型：参见 MessageActionTypeDict */
    private String actionType;

    /** 小程序跳转路径或后端约定动作地址 */
    private String actionUrl;

    /** 业务来源类型 */
    private String bizType;

    /** 业务来源 ID */
    private Long bizId;

    /** 消息幂等键 */
    private String idempotencyKey;

    /** 已读时间 */
    private LocalDateTime readAt;
}
