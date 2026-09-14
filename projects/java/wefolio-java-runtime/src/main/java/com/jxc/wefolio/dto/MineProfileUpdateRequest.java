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

    /** 联系手机；更新时 null 不改，空串表示明确清空。 */
    private String contactPhone;

    /** 联系微信；更新时 null 不改，空串表示明确清空。 */
    private String contactWechat;

    /** 个人标签；兼容旧字符串数组和新对象数组 */
    private List<?> tags;
}
