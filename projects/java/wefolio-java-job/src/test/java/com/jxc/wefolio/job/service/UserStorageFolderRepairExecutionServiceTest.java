package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionCoordinator.Submission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史用户目录修复应用服务测试。
 */
class UserStorageFolderRepairExecutionServiceTest {

    @Test
    void executeShouldAuthorizeAndMapSubmissionWithoutBusinessParameters() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        UserStorageFolderRepairExecutionCoordinator coordinator =
                mock(UserStorageFolderRepairExecutionCoordinator.class);
        when(validator.isValid("secret")).thenReturn(true);
        when(coordinator.submit()).thenReturn(
                new Submission(true, "execution-1", "ACCEPTED"));
        UserStorageFolderRepairExecutionService service =
                new UserStorageFolderRepairExecutionService(validator, coordinator);

        var result = service.execute("secret");

        assertThat(result.outcome())
                .isEqualTo(UserStorageFolderRepairExecutionService.ExecutionOutcome.ACCEPTED);
        assertThat(result.data().getExecutionId()).isEqualTo("execution-1");
        verify(coordinator).submit();
    }

    @Test
    void executeShouldRejectInvalidSecretBeforeSubmission() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        UserStorageFolderRepairExecutionCoordinator coordinator =
                mock(UserStorageFolderRepairExecutionCoordinator.class);
        UserStorageFolderRepairExecutionService service =
                new UserStorageFolderRepairExecutionService(validator, coordinator);

        var result = service.execute("wrong");

        assertThat(result.outcome())
                .isEqualTo(UserStorageFolderRepairExecutionService.ExecutionOutcome.UNAUTHORIZED);
        verify(coordinator, never()).submit();
    }
}
