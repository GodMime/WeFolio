package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.UserStorageFolderRepairProperties;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.Summary;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.UserStorageUser;
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

    /** 当前代码版本需要确保存在的用户目录 */
    private static final List<String> REQUIRED_USER_FOLDERS = List.of("work/animation/");

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
        Summary result = summary.snapshot();
        log.info("历史用户 COS 目录修复完成: executionId={}, scannedUsers={}, existingFolders={}, "
                        + "createdFolders={}, failedFolders={}",
                executionId, result.scannedUsers(), result.existingFolders(),
                result.createdFolders(), result.failedFolders());
        return result;
    }

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
        private long scannedUsers;
        private long existingFolders;
        private long createdFolders;
        private long failedFolders;

        private Summary snapshot() {
            return new Summary(scannedUsers, existingFolders, createdFolders, failedFolders);
        }
    }
}
