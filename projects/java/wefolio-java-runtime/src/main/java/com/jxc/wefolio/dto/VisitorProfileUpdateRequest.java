package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访客头像昵称保存请求。
 */
@Data
public class VisitorProfileUpdateRequest {

    /** 访客资料短期授权 token */
    private String visitorProfileToken;

    /** 访客授权昵称 */
    private String nickname;

    /** 访客头像公开地址 */
    private String avatarUrl;
}
