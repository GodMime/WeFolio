package com.jxc.wefolio.exception;

/**
 * 维护者积分余额不足异常 — 由具体维护业务转换为对应操作提示。
 */
public class InsufficientPointBalanceException extends BusinessException {

    /** 使用统一底层余额不足消息创建异常。 */
    public InsufficientPointBalanceException(String message) {
        super(message);
    }
}
