package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 历史用户目录修复异步协调器测试。
 */
class UserStorageFolderRepairExecutionCoordinatorTest {

    @Test
    void submitShouldRejectLocalReentryAndReleaseAfterCompletion() {
        UserStorageFolderRepairService repairService =
                mock(UserStorageFolderRepairService.class);
        ManualExecutor executor = new ManualExecutor();
        UserStorageFolderRepairExecutionCoordinator coordinator =
                new UserStorageFolderRepairExecutionCoordinator(repairService, executor);

        var accepted = coordinator.submit();
        var running = coordinator.submit();

        assertThat(accepted.accepted()).isTrue();
        assertThat(running.accepted()).isFalse();
        assertThat(running.executionId()).isEqualTo(accepted.executionId());
        executor.runNext();
        verify(repairService).run(accepted.executionId());
        assertThat(coordinator.submit().accepted()).isTrue();
    }

    @Test
    void rejectedSubmissionShouldClearRunningStateAndReportUnavailable() {
        TaskExecutor rejectedExecutor = task -> {
            throw new TaskRejectedException("busy");
        };
        UserStorageFolderRepairExecutionCoordinator coordinator =
                new UserStorageFolderRepairExecutionCoordinator(
                        mock(UserStorageFolderRepairService.class), rejectedExecutor);

        assertThatThrownBy(coordinator::submit)
                .isInstanceOf(UserStorageFolderRepairUnavailableException.class);
        assertThatThrownBy(coordinator::submit)
                .isInstanceOf(UserStorageFolderRepairUnavailableException.class);
    }

    private static final class ManualExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}
