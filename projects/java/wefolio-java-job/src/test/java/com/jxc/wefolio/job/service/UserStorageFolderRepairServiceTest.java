package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.UserStorageFolderRepairProperties;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.Summary;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.UserStorageUser;
import com.jxc.wefolio.job.repo.UserStorageFolderRepairRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 历史用户目录串行修复服务测试。
 */
class UserStorageFolderRepairServiceTest {

    @Test
    void runShouldProcessUsersSeriallyIsolateFailuresAndAdvanceIdCursor() {
        UserStorageFolderRepairRepository repository =
                mock(UserStorageFolderRepairRepository.class);
        UserStorageFolderCosService cosService = mock(UserStorageFolderCosService.class);
        UserStorageFolderRepairProperties properties =
                new UserStorageFolderRepairProperties();
        properties.setBatchSize(200);
        UserStorageUser first = new UserStorageUser(1L, "WFAAAAAA01");
        UserStorageUser second = new UserStorageUser(2L, "WFBBBBBB02");
        when(repository.findActiveUsersAfter(0L, 200))
                .thenReturn(List.of(first, second));
        when(repository.findActiveUsersAfter(2L, 200)).thenReturn(List.of());
        when(cosService.exists(first.uniqueCode(), "work/animation/")).thenReturn(false);
        when(cosService.exists(second.uniqueCode(), "work/animation/")).thenReturn(false);
        doThrow(new IllegalStateException("COS failed"))
                .when(cosService).create(first.uniqueCode(), "work/animation/");

        Summary summary = new UserStorageFolderRepairService(
                repository, cosService, properties).run("execution-1");

        assertThat(summary).isEqualTo(new Summary(2, 0, 1, 1));
        InOrder inOrder = inOrder(repository, cosService);
        inOrder.verify(repository).findActiveUsersAfter(0L, 200);
        inOrder.verify(cosService).exists(first.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).create(first.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).exists(second.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).create(second.uniqueCode(), "work/animation/");
        inOrder.verify(repository).findActiveUsersAfter(2L, 200);
    }
}
