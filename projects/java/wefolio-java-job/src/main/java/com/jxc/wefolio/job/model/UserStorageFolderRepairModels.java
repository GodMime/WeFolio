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

    /** 待修复团队最小视图，唯一码取团队自身。 */
    public record TeamStorageTeam(long id, String uniqueCode) {
    }

    /**
     * 单次修复汇总。
     *
     * @param scannedUsers 扫描用户数
     * @param existingFolders 用户已存在目录数
     * @param createdFolders 用户新建目录数
     * @param failedFolders 用户失败目录数
     * @param scannedTeams 扫描团队数
     * @param existingTeamFolders 团队已存在目录数
     * @param createdTeamFolders 团队新建目录数
     * @param failedTeamFolders 团队失败目录数
     */
    public record Summary(
            long scannedUsers,
            long existingFolders,
            long createdFolders,
            long failedFolders,
            long scannedTeams,
            long existingTeamFolders,
            long createdTeamFolders,
            long failedTeamFolders
    ) {
        /** 保留仅有用户统计的构造方式。 */
        public Summary(long scannedUsers, long existingFolders, long createdFolders, long failedFolders) {
            this(scannedUsers, existingFolders, createdFolders, failedFolders, 0, 0, 0, 0);
        }
    }
}
