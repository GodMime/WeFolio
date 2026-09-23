package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.UserStorageFolderRepairProperties;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.Summary;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.UserStorageUser;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.TeamStorageTeam;
import com.jxc.wefolio.job.repo.UserStorageFolderRepairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 历史用户 COS 目录串行修复服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserStorageFolderRepairService {

    /** 个人及团队均需补建的字体目录。 */
    private static final String FONT_FOLDER = "others/fonts/";

    /** 当前代码版本需要确保存在的用户目录 */
    private static final List<String> REQUIRED_USER_FOLDERS = List.of("work/animation/", "work/audio/", FONT_FOLDER);

    private final UserStorageFolderRepairRepository repository;

    private final UserStorageFolderCosService cosService;

    private final UserStorageFolderRepairProperties properties;

    /**
     * 使用 ID 游标逐页、逐用户串行修复。
     *
     * @param executionId 执行 ID
     * @return 执行汇总
     */
    public Summary run(String executionId) {
        MutableSummary summary = new MutableSummary();
        long cursorUserId = 0L;
        while (true) {
            List<UserStorageUser> users = repository.findActiveUsersAfter(
                    cursorUserId, properties.getBatchSize());
            if (users.isEmpty()) {
                break;
            }
            for (UserStorageUser user : users) {
                summary.scannedUsers++;
                repairOneUser(executionId, user, summary);
            }
            long nextCursor = users.getLast().id();
            if (nextCursor <= cursorUserId) {
                throw new IllegalStateException("历史用户目录修复游标未推进");
            }
            cursorUserId = nextCursor;
            log.info("历史用户 COS 目录修复批次完成: executionId={}, cursorUserId={}, batchUsers={}, "
                            + "scannedUsers={}, existingFolders={}, createdFolders={}, failedFolders={}",
                    executionId, cursorUserId, users.size(), summary.scannedUsers,
                    summary.existingFolders, summary.createdFolders, summary.failedFolders);
        }
        repairTeams(executionId, summary);
        Summary result = summary.snapshot();
        log.info("历史用户 COS 目录修复完成: executionId={}, scannedUsers={}, existingFolders={}, "
                        + "createdFolders={}, failedFolders={}, scannedTeams={}, existingTeamFolders={}, "
                        + "createdTeamFolders={}, failedTeamFolders={}",
                executionId, result.scannedUsers(), result.existingFolders(),
                result.createdFolders(), result.failedFolders(), result.scannedTeams(),
                result.existingTeamFolders(), result.createdTeamFolders(), result.failedTeamFolders());
        return result;
    }

    /** 用户阶段后以独立游标扫描团队，仅修复字体空目录。 */
    private void repairTeams(String executionId, MutableSummary summary) {
        long cursorTeamId = 0L;
        while (true) {
            List<TeamStorageTeam> teams = repository.findActiveTeamsAfter(cursorTeamId, properties.getBatchSize());
            if (teams.isEmpty()) {
                return;
            }
            for (TeamStorageTeam team : teams) {
                summary.scannedTeams++;
                try {
                    if (cosService.exists(team.uniqueCode(), FONT_FOLDER)) {
                        summary.existingTeamFolders++;
                    } else {
                        cosService.create(team.uniqueCode(), FONT_FOLDER);
                        summary.createdTeamFolders++;
                    }
                } catch (RuntimeException exception) {
                    summary.failedTeamFolders++;
                    log.warn("历史团队 COS 目录修复失败: executionId={}, teamId={}, uniqueCode={}, relativeFolder={}",
                            executionId, team.id(), maskUniqueCode(team.uniqueCode()), FONT_FOLDER, exception);
                }
            }
            long nextCursor = teams.getLast().id();
            if (nextCursor <= cursorTeamId) {
                throw new IllegalStateException("历史团队目录修复游标未推进");
            }
            cursorTeamId = nextCursor;
            log.info("历史团队 COS 目录修复批次完成: executionId={}, cursorTeamId={}, batchTeams={}, "
                            + "scannedTeams={}, existingTeamFolders={}, createdTeamFolders={}, failedTeamFolders={}",
                    executionId, cursorTeamId, teams.size(), summary.scannedTeams,
                    summary.existingTeamFolders, summary.createdTeamFolders, summary.failedTeamFolders);
        }
    }

    /** 隔离单目录失败，继续处理该用户其他目录。 */
    private void repairOneUser(String executionId, UserStorageUser user, MutableSummary summary) {
        for (String relativeFolder : REQUIRED_USER_FOLDERS) {
            try {
                if (cosService.exists(user.uniqueCode(), relativeFolder)) {
                    summary.existingFolders++;
                } else {
                    cosService.create(user.uniqueCode(), relativeFolder);
                    summary.createdFolders++;
                }
            } catch (RuntimeException exception) {
                summary.failedFolders++;
                log.warn("历史用户 COS 目录修复失败: executionId={}, userId={}, uniqueCode={}, "
                                + "relativeFolder={}, errorType={}",
                        executionId, user.id(), maskUniqueCode(user.uniqueCode()), relativeFolder,
                        exception.getClass().getSimpleName(), exception);
            }
        }
    }

    private String maskUniqueCode(String uniqueCode) {
        if (uniqueCode == null || uniqueCode.length() <= 4) {
            return "****";
        }
        return uniqueCode.substring(0, 2) + "****"
                + uniqueCode.substring(uniqueCode.length() - 2);
    }

    /** 单次执行可变计数器，仅在当前串行线程内使用。 */
    private static final class MutableSummary {
        /** 用户统计保持原含义。 */
        private long scannedUsers;
        private long existingFolders;
        private long createdFolders;
        private long failedFolders;

        /** 团队统计独立于用户字段。 */
        private long scannedTeams;
        /** 团队已存在的字体目录数。 */
        private long existingTeamFolders;
        /** 团队本次补建的字体目录数。 */
        private long createdTeamFolders;
        /** 团队目录检查或补建失败数。 */
        private long failedTeamFolders;

        /** 转换为不可变汇总。 */
        private Summary snapshot() {
            return new Summary(scannedUsers, existingFolders, createdFolders, failedFolders,
                    scannedTeams, existingTeamFolders, createdTeamFolders, failedTeamFolders);
        }
    }
}
