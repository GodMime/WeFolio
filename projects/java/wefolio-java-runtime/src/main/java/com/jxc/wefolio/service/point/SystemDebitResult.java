package com.jxc.wefolio.service.point;

/**
 * 后台月费扣除结果。
 *
 * @param deducted 是否实际执行完整扣除
 * @param mutation 扣除结果，余额非正跳过时为空
 */
public record SystemDebitResult(boolean deducted, PointMutationResult mutation) {

    /** 创建余额非正跳过结果。 */
    public static SystemDebitResult skipped() {
        return new SystemDebitResult(false, null);
    }

    /** 创建完整扣除结果。 */
    public static SystemDebitResult deducted(PointMutationResult mutation) {
        return new SystemDebitResult(true, mutation);
    }
}
