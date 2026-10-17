package com.jxc.wefolio.job.service;

import java.util.regex.Pattern;

/** 现有错误字段中的追加摘要格式，兼容历史单条错误及模型纠正反馈。 */
public final class PortfolioAiFailureHistory {
    /** 每条追加记录以执行次数和固定错误码开头，正文允许保留安全换行。 */
    private static final String ENTRY_FORMAT = "[attempt=%d code=%s] %s";
    /** 只识别服务端生成的记录头，不把普通换行误判为历史分隔。 */
    private static final Pattern ENTRY_HEADER = Pattern.compile("(?m)^\\[attempt=\\d+ code=[A-Z][A-Z0-9_]*] ");

    /** 禁止实例化。 */
    private PortfolioAiFailureHistory() { }

    /** 编码单次失败，调用方通过原状态版本条件在数据库原子追加。 */
    public static String entry(Integer attempt, String code, String message) {
        return ENTRY_FORMAT.formatted(attempt == null ? 0 : attempt, code, message);
    }

    /** 模型只读取最近一次纠正摘要，旧单条记录保持原样。 */
    public static String latestMessage(String history) {
        if (history == null) { return null; }
        var matcher = ENTRY_HEADER.matcher(history);
        int offset = 0;
        while (matcher.find()) { offset = matcher.end(); }
        return history.substring(offset);
    }
}
