package com.jxc.wefolio.exception;

import lombok.Getter;

/**
 * 携带结构化错误详情的作品集业务异常。
 */
@Getter
public class PortfolioValidationException extends BusinessException {

    /** 供新版客户端定位错误或展示引用来源的增量数据。 */
    private final Object data;

    /**
     * 创建结构化作品集业务异常。
     *
     * @param message 用户可读提示
     * @param data 结构化错误详情
     */
    public PortfolioValidationException(String message, Object data) {
        super(message);
        this.data = data;
    }

    /**
     * 创建包含原始异常的结构化作品集业务异常。
     *
     * @param message 用户可读提示
     * @param data 结构化错误详情
     * @param cause 原始异常
     */
    public PortfolioValidationException(String message, Object data, Throwable cause) {
        super(message, cause);
        this.data = data;
    }
}
