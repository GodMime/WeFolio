package com.jxc.wefolio.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/**
 * wf_user — 用户表 — 维护者账号资料与状态
 */
@Data
@TableName("wf_user")
public class UserEntity extends BaseEntity {

    /** 个人唯一码，注册后不可重复 */
    private String uniqueCode;

    /** 昵称、姓名或艺名 */
    private String nickname;

    /** 头像地址 */
    private String avatarUrl;

    /** 职业身份 */
    private String profession;

    /** 城市或服务区域 */
    private String city;

    /** 个人简介 */
    private String intro;

    /** 微信二维码地址 */
    private String wechatQrUrl;

    /** 资料联系电话密文，不作为登录凭证 */
    private String contactPhoneCiphertext;

    /** 展示标签数组（JSON） */
    private String profileTags;

    /** 账号状态：ACTIVE 正常 / DISABLED 禁用 */
    private String status;

    /** 完成手机号绑定时间 */
    private LocalDateTime phoneBoundAt;

    /** 注册时间 */
    private LocalDateTime registeredAt;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    /** 逻辑删除时间 */
    private LocalDateTime deletedAt;

}
