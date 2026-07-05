package com.jxc.wefolio.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wf_visitor — 全局访客身份表，按微信 openid 跨维护者和作品集复用访客资料。
 */
@Data
@TableName("wf_visitor")
public class VisitorEntity extends BaseEntity {

    /** 微信 openid 明文，仅服务端使用，不返回前端 */
    private String openid;

    /** 微信 unionid，微信未返回时为空 */
    private String unionid;

    /** 服务端生成的匿名访客稳定 key，可返回前端用于访客事件链路 */
    private String visitorKey;

    /** 访客授权昵称 */
    private String nickname;

    /** 访客授权头像公开地址 */
    private String avatarUrl;

    /** 头像昵称最近成功保存时间 */
    private LocalDateTime profileAuthorizedAt;

    /** 最近访问时间 */
    private LocalDateTime lastSeenAt;
}
