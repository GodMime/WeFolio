package com.jxc.wefolio.exception;

/**
 * 非法积分金额异常 — 扣除或赠送金额不是正整数时由统一积分入口抛出。
 */
public class InvalidPointAmountException extends BusinessException {

    /** 使用用户可理解的错误消息创建异常。 */
    public InvalidPointAmountException(String message) {
        super(message);
    }
}
