package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 基础信息响应 — 返回维护者可编辑的个人资料
 */
@Data
public class MineProfileResponse {

    /** 用户 ID */
    private Long userId;

    /** 个人唯一码 */
    private String uniqueCode;

    /** 页面展示名称 */
    private String displayName;

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

    /** 个人标签 */
    private List<MineProfileTagDTO> tags;
}
