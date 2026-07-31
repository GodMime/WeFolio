package com.jxc.wefolio.job.service;

/**
 * 历史用户目录修复执行器不可用异常。
 */
public class UserStorageFolderRepairUnavailableException extends RuntimeException {

    public UserStorageFolderRepairUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
