package com.jxc.wefolio.common.auth;

/**
 * Authorization 请求头工具。
 */
public final class AuthorizationHeaderUtils {

    /** Bearer 认证类型 */
    private static final String TOKEN_TYPE = "Bearer";

    private AuthorizationHeaderUtils() {
    }

    /**
     * 标准化 Authorization 中的 Bearer 令牌。
     *
     * @param authorizationHeader Authorization 请求头或裸令牌
     * @return 去除认证类型和首尾空白后的令牌
     */
    public static String normalizeBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return "";
        }
        String value = stripBoundaryWhitespace(authorizationHeader);
        if (value.regionMatches(true, 0, TOKEN_TYPE, 0, TOKEN_TYPE.length())
                && value.length() > TOKEN_TYPE.length()
                && isBoundaryWhitespace(value.codePointAt(TOKEN_TYPE.length()))) {
            return stripBoundaryWhitespace(value.substring(TOKEN_TYPE.length()));
        }
        return value;
    }

    /**
     * 去除字符串首尾 Unicode 空白；额外覆盖不间断空格等 space separator。
     *
     * @param value 原字符串
     * @return 去除首尾空白后的字符串
     */
    private static String stripBoundaryWhitespace(String value) {
        String stripped = value.strip();
        int start = 0;
        int end = stripped.length();
        while (start < end) {
            int codePoint = stripped.codePointAt(start);
            if (!isBoundaryWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = stripped.codePointBefore(end);
            if (!isBoundaryWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return stripped.substring(start, end);
    }

    /**
     * 判断是否为需要在令牌边界剥离的空白字符。
     *
     * @param codePoint Unicode 码点
     * @return 是否为空白字符
     */
    private static boolean isBoundaryWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
