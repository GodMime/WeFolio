package com.jxc.wefolio.common;

import java.util.Objects;

/** Shell 命令参数转义工具。 */
public final class ShellCommandEscaper {

    /** Shell 单引号参数中嵌入单引号时使用的转义片段。 */
    private static final String ESCAPED_SINGLE_QUOTE = "'\\''";

    /** 禁止实例化工具类。 */
    private ShellCommandEscaper() {
    }

    /**
     * 将完整值包装为 POSIX shell 单引号参数。
     *
     * @param value 原始参数值
     * @return 可直接拼入 shell 命令的单引号参数
     */
    public static String singleQuote(String value) {
        return "'" + Objects.requireNonNull(value).replace("'", ESCAPED_SINGLE_QUOTE) + "'";
    }
}
