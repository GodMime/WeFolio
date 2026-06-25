package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 我的团队资料更新请求。
 *
 * <p>字段为 null 时表示本次不更新该字段；空字符串表示主动清空可选字段。</p>
 */
@Data
public class MineTeamUpdateRequest {

    /** 团队名称，传入时必填且需要符合长度限制 */
    private String name;

    /** 团队简介，允许清空 */
    private String intro;

    /** 团队图标地址，通常来自团队图标上传接口返回的公开 URL */
    private String avatarUrl;
}
