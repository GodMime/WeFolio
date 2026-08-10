package com.jxc.wefolio.service.payment;

/**
 * 当前执行租约已被其他执行者接管。
 */
public class ExecutionLeaseLostException extends RuntimeException {

    /** 使用明确业务上下文构造异常。 */
    public ExecutionLeaseLostException(String message) {
        super(message);
    }
}
