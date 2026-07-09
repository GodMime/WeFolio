package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_user_auth — 用户登录身份表 — 微信、手机验证码、手机密码三种登录方式
 */
@Data
@TableName("wf_user_auth")
public class UserAuthEntity extends BaseEntity {

    /** 逻辑关联用户 ID */
    private Long userId;

    /** 登录类型：WECHAT_MINI_APP / PHONE_OTP / PHONE_PASSWORD */
    private String authType;

    /** openid 或手机号的 HMAC-SHA256 查询摘要 */
    private String identifierHash;

    /** openid 或手机号密文 */
    private String identifierCiphertext;

    /** 微信 openid 明文，仅服务端用于维护者本人访问识别，不返回前端 */
    private String openId;

    /** 微信 unionid 查询摘要 */
    private String unionIdentifierHash;

    /** 微信 unionid 密文 */
    private String unionIdentifierCiphertext;

    /** 密码摘要（仅 PHONE_PASSWORD 类型必填） */
    private String credentialHash;

    /** 状态：ACTIVE 正常 / DISABLED 禁用 */
    private String status;

    /** 最近认证时间 */
    private LocalDateTime lastAuthenticatedAt;

}
