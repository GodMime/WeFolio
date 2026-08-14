package com.jxc.wefolio.job.service;

/**
 * 历史用户目录修复执行器不可用异常。
 */
public class UserStorageFolderRepairUnavailableException extends RuntimeException {

    /**
     * 创建无底层异常的不可用结果。
     *
     * @param message 安全错误消息
     */
    public UserStorageFolderRepairUnavailableException(String message) {
        super(message);
    }

    /**
     * 创建包含底层异常的不可用结果。
     *
     * @param message 安全错误消息
     * @param cause 原始异常
     */
    public UserStorageFolderRepairUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
