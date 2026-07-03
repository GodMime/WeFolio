package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 基础信息保存请求 — 承载维护者可编辑的个人资料字段
 */
@Data
public class MineProfileUpdateRequest {

    /** 昵称、姓名或艺名 */
    private String nickname;

    /** 头像地址 */
    private String avatarUrl;

    /** 微信二维码地址 */
    private String wechatQrUrl;

    /** 职业身份 */
    private String profession;

    /** 城市或服务区域 */
    private String city;

    /** 个人简介 */
    private String intro;

    /** 个人标签；兼容旧字符串数组和新对象数组 */
    private List<?> tags;
}
