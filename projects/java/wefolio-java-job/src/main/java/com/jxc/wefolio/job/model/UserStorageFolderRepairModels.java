package com.jxc.wefolio.job.model;

/**
 * 历史用户 COS 目录修复模型。
 */
public final class UserStorageFolderRepairModels {

    private UserStorageFolderRepairModels() {
    }

    /**
     * 待修复用户最小视图。
     *
     * @param id 用户 ID
     * @param uniqueCode 用户唯一码
     */
    public record UserStorageUser(long id, String uniqueCode) {
    }

    /**
     * 单次修复汇总。
     *
     * @param scannedUsers 扫描用户数
     * @param existingFolders 已存在目录数
     * @param createdFolders 新建目录数
     * @param failedFolders 失败目录数
     */
    public record Summary(
            long scannedUsers,
            long existingFolders,
            long createdFolders,
            long failedFolders
    ) {
    }
}
