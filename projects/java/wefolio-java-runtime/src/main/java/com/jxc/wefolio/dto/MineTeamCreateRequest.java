package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 我的团队创建请求。
 *
 * <p>小程序创建团队表单提交到后端后，后端会补充生成团队唯一码并初始化团队 COS 目录。</p>
 */
@Data
public class MineTeamCreateRequest {

    /** 团队名称，必填，后端会去除首尾空格并校验长度 */
    private String name;

    /** 团队简介，可为空，用于团队列表和维护页展示 */
    private String intro;

    /** 团队图标地址，可先传远程 URL；本地临时文件会由小程序单独走上传接口 */
    private String avatarUrl;
}
