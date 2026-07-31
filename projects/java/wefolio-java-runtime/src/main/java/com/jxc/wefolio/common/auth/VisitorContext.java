package com.jxc.wefolio.common.auth;

import java.util.Objects;

/**
 * 当前请求访客认证上下文。
 */
public class VisitorContext {

    /** 令牌脱敏展示文本 */
    private static final String MASKED_TOKEN_TEXT = "***";

    /** 当前访客 ID */
    private final Long visitorId;

    /** 当前访客稳定 key */
    private final String visitorKey;

    /** 当前请求使用的访客登录令牌 */
    private final String token;

    /** 是否为朋友圈单页匿名访客 */
    private final boolean timelineAnonymous;

    /**
     * 创建访客认证上下文。
     *
     * @param visitorId 当前访客 ID
     * @param visitorKey 当前访客稳定 key
     * @param token 当前请求使用的访客登录令牌
     */
    public VisitorContext(Long visitorId, String visitorKey, String token) {
        this(visitorId, visitorKey, token, false);
    }

    /**
     * 创建访客认证上下文。
     *
     * @param visitorId 当前访客 ID
     * @param visitorKey 当前访客稳定 key
     * @param token 当前请求使用的访客登录令牌
     * @param timelineAnonymous 是否为朋友圈单页匿名访客
     */
    public VisitorContext(Long visitorId, String visitorKey, String token, boolean timelineAnonymous) {
        this.visitorId = visitorId;
        this.visitorKey = visitorKey;
        this.token = token;
        this.timelineAnonymous = timelineAnonymous;
    }

    /**
     * 获取当前访客 ID。
     *
     * @return 当前访客 ID
     */
    public Long getVisitorId() {
        return visitorId;
    }

    /**
     * 获取当前访客稳定 key。
     *
     * @return 当前访客稳定 key
     */
    public String getVisitorKey() {
        return visitorKey;
    }

    /**
     * 获取当前请求使用的访客登录令牌。
     *
     * @return 当前请求使用的访客登录令牌
     */
    public String getToken() {
        return token;
    }

    /**
     * 判断是否为朋友圈单页匿名访客。
     *
     * @return 是否为朋友圈单页匿名访客
     */
    public boolean isTimelineAnonymous() {
        return timelineAnonymous;
    }

    /**
     * 判断访客认证上下文是否相同。
     *
     * @param o 待比较对象
     * @return 是否相同
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VisitorContext that)) {
            return false;
        }
        return Objects.equals(visitorId, that.visitorId)
                && Objects.equals(visitorKey, that.visitorKey)
                && Objects.equals(token, that.token)
                && timelineAnonymous == that.timelineAnonymous;
    }

    /**
     * 计算访客认证上下文哈希值。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(visitorId, visitorKey, token, timelineAnonymous);
    }

    /**
     * 输出访客认证上下文调试文本。
     *
     * @return 调试文本
     */
    @Override
    public String toString() {
        return "VisitorContext{"
                + "visitorId=" + visitorId
                + ", visitorKey=" + visitorKey
                + ", token=" + maskToken(token)
                + ", timelineAnonymous=" + timelineAnonymous
                + '}';
    }

    /**
     * 脱敏展示访客登录令牌。
     *
     * @param rawToken 原始令牌
     * @return 脱敏令牌
     */
    private String maskToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        return MASKED_TOKEN_TEXT;
    }
}
