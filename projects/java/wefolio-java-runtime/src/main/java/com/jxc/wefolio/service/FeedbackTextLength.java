package com.jxc.wefolio.service;

/**
 * 反馈文本长度工具，统一按 Unicode 代码点计数。
 */
final class FeedbackTextLength {

    /** 工具类不允许实例化。 */
    private FeedbackTextLength() {
    }

    /**
     * 判断文本是否超过指定 Unicode 代码点数量。
     *
     * @param value 非空文本
     * @param maxCodePointCount 最大代码点数量
     * @return 超过上限时返回 true
     */
    static boolean exceeds(String value, int maxCodePointCount) {
        return value.codePointCount(0, value.length()) > maxCodePointCount;
    }

    /**
     * 裁剪文本首尾的 Unicode 空白和空格字符。
     *
     * @param value 待处理文本，允许为空
     * @return 非空返回裁剪后的文本，空值返回空字符串
     */
    static String trim(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isTrimmable(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isTrimmable(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /** 判断代码点是否属于应裁剪的 Unicode 空白。 */
    private static boolean isTrimmable(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * 按 Unicode 代码点安全截断文本，不拆分代理项。
     *
     * @param value 非空文本
     * @param maxCodePointCount 最大代码点数量
     * @return 不超过上限的文本
     */
    static String truncate(String value, int maxCodePointCount) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maxCodePointCount) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePointCount);
        return value.substring(0, endIndex);
    }
}
