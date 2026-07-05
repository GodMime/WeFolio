package com.jxc.wefolio.message;

/**
 * 访客身份报错信息 — 统一维护访客 openid、头像昵称资料和资料授权 token 提示。
 */
public interface VisitorMessage {

    /** 访客头像为空提示 */
    String VISITOR_AVATAR_REQUIRED_MESSAGE = "请选择访客头像";

    /** 访客昵称为空提示 */
    String VISITOR_NICKNAME_REQUIRED_MESSAGE = "请填写访客昵称";

    /** 访客头像大小无效提示 */
    String VISITOR_AVATAR_SIZE_INVALID_MESSAGE = "访客头像大小无效";

    /** 访客头像大小超限提示 */
    String VISITOR_AVATAR_SIZE_LIMIT_MESSAGE = "访客头像不能超过 200KB";

    /** 访客头像格式不支持提示 */
    String VISITOR_AVATAR_FORMAT_UNSUPPORTED_MESSAGE = "访客头像仅支持 JPG、PNG 或 WEBP";

    /** 访客资料 token 失效提示 */
    String VISITOR_PROFILE_TOKEN_INVALID_MESSAGE = "访客资料授权已过期，请重新进入页面";

    /** 访客头像归属错误提示 */
    String VISITOR_AVATAR_OWNERSHIP_INVALID_MESSAGE = "访客头像归属不正确";

    /** 访客头像已过期提示 */
    String VISITOR_AVATAR_EXPIRED_MESSAGE = "访客头像已过期，请重新上传";

    /** 访客昵称超长提示 */
    String VISITOR_NICKNAME_LENGTH_MESSAGE = "访客昵称不能超过 50 字";

    /** 访客头像地址超长提示 */
    String VISITOR_AVATAR_URL_LENGTH_MESSAGE = "访客头像地址不能超过 512 字";

    /** 访客资料保存失败提示 */
    String VISITOR_PROFILE_SAVE_FAILED_MESSAGE = "访客资料保存失败，请重试";
}
