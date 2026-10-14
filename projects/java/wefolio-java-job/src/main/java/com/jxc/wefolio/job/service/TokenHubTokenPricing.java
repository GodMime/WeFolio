package com.jxc.wefolio.job.service;

import java.math.BigInteger;

/** 广州公开价格的任务无关估算，输出包含思考，不代表账户账单实扣。 */
public final class TokenHubTokenPricing {
    /** 每百万普通输入 token 的分单价。 */
    private static final BigInteger INPUT_PRICE_CENTS = BigInteger.valueOf(80);
    /** 每百万缓存输入 token 的分单价。 */
    private static final BigInteger CACHED_INPUT_PRICE_CENTS = BigInteger.valueOf(23);
    /** 每百万输出 token 的分单价，思考不能另加。 */
    private static final BigInteger OUTPUT_PRICE_CENTS = BigInteger.valueOf(280);
    /** 单价 token 基数。 */
    private static final BigInteger TOKEN_PRICE_UNIT = BigInteger.valueOf(1_000_000);
    /** 禁止实例化价格工具。 */
    private TokenHubTokenPricing() { }
    /** 预留费用不预测缓存命中。 */
    public static long costCents(long prompt, long completion) { return costCents(prompt, completion, null); }
    /** 非法缓存量保守全价，使用任意精度避免乘法溢出，向上取整到分。 */
    public static long costCents(long prompt, long completion, Long cachedTokens) {
        if (prompt < 0 || completion < 0) { throw new IllegalArgumentException("模型用量不能为负数"); }
        long cached = cachedTokens != null && cachedTokens >= 0 && cachedTokens <= prompt ? cachedTokens : 0;
        BigInteger units = BigInteger.valueOf(prompt - cached).multiply(INPUT_PRICE_CENTS)
                .add(BigInteger.valueOf(cached).multiply(CACHED_INPUT_PRICE_CENTS))
                .add(BigInteger.valueOf(completion).multiply(OUTPUT_PRICE_CENTS));
        return units.add(TOKEN_PRICE_UNIT.subtract(BigInteger.ONE)).divide(TOKEN_PRICE_UNIT).longValueExact();
    }
}
