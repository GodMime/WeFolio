package com.jxc.wefolio.service;

import com.jxc.wefolio.exception.BusinessException;

/**
 * 反馈业务写入 ASCII 排序字段前使用的标识校验器。
 */
final class FeedbackIdentifierValidator {

    /** 反馈请求标识最大字符数。 */
    private static final int MAX_LENGTH = 64;

    /** 可打印非空 ASCII 的最小字符码。 */
    private static final char MIN_PRINTABLE_ASCII = 0x21;

    /** 可打印 ASCII 的最大字符码。 */
    private static final char MAX_PRINTABLE_ASCII = 0x7E;

    private FeedbackIdentifierValidator() {
    }

    /**
     * 裁剪并校验长度为 1 至 64 的可打印非空 ASCII 标识。
     *
     * @param value 待校验标识
     * @param invalidMessage 校验失败业务文案
     * @return 裁剪后的合法标识
     */
    static String normalizePrintableAscii(String value, String invalidMessage) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            throw new BusinessException(invalidMessage);
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character < MIN_PRINTABLE_ASCII || character > MAX_PRINTABLE_ASCII) {
                throw new BusinessException(invalidMessage);
            }
        }
        return normalized;
    }
}
