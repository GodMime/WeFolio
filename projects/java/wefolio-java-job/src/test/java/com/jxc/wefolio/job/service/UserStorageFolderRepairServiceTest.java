package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.UserStorageFolderRepairProperties;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.Summary;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.UserStorageUser;
import com.jxc.wefolio.job.model.UserStorageFolderRepairModels.TeamStorageTeam;
import com.jxc.wefolio.job.repo.UserStorageFolderRepairRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 历史用户目录串行修复服务测试。
 */
class UserStorageFolderRepairServiceTest {

    /** 原个人修复继续串行隔离失败，且加入字体目录。 */
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

        assertThat(summary).isEqualTo(new Summary(2, 0, 5, 1));
        InOrder inOrder = inOrder(repository, cosService);
        inOrder.verify(repository).findActiveUsersAfter(0L, 200);
        inOrder.verify(cosService).exists(first.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).create(first.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).exists(first.uniqueCode(), "work/audio/");
        inOrder.verify(cosService).create(first.uniqueCode(), "work/audio/");
        inOrder.verify(cosService).exists(second.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).create(second.uniqueCode(), "work/animation/");
        inOrder.verify(cosService).exists(second.uniqueCode(), "work/audio/");
        inOrder.verify(cosService).create(second.uniqueCode(), "work/audio/");
        inOrder.verify(repository).findActiveUsersAfter(2L, 200);
    }

    /** 两阶段独立分页，重复运行跳过已有目录，团队失败不计入用户统计。 */
    @Test
    void runShouldUseIndependentTeamCursorAndOnlyRepairMissingFontFolders() {
        var repository = mock(UserStorageFolderRepairRepository.class);
        var cosService = mock(UserStorageFolderCosService.class);
        var properties = new UserStorageFolderRepairProperties();
        properties.setBatchSize(1);
        when(repository.findActiveUsersAfter(0, 1)).thenReturn(List.of(new UserStorageUser(20, "WF20")));
        when(repository.findActiveUsersAfter(20, 1)).thenReturn(List.of(new UserStorageUser(30, "WF30")));
        when(repository.findActiveTeamsAfter(0, 1)).thenReturn(List.of(new TeamStorageTeam(1, "TM1")));
        when(repository.findActiveTeamsAfter(1, 1)).thenReturn(List.of(new TeamStorageTeam(2, "TM2")));
        when(repository.findActiveTeamsAfter(2, 1)).thenReturn(List.of(new TeamStorageTeam(3, "TM3")));
        when(cosService.exists(eq("WF20"), anyString())).thenReturn(true);
        when(cosService.exists(eq("WF30"), anyString())).thenReturn(true);
        when(cosService.exists("TM1", "others/fonts/")).thenReturn(true);
        when(cosService.exists("TM2", "others/fonts/")).thenReturn(false, true);
        doThrow(new IllegalStateException("COS failed")).when(cosService).create("TM3", "others/fonts/");
        var service = new UserStorageFolderRepairService(repository, cosService, properties);

        assertThat(service.run("first")).isEqualTo(new Summary(2, 6, 0, 0, 3, 1, 1, 1));
        assertThat(service.run("second")).isEqualTo(new Summary(2, 6, 0, 0, 3, 2, 0, 1));
        verify(cosService).create("TM2", "others/fonts/");
        verify(cosService, never()).create(eq("TM1"), anyString());
        for (String team : List.of("TM1", "TM2", "TM3")) {
            verify(cosService, never()).exists(team, "work/animation/");
            verify(cosService, never()).exists(team, "work/audio/");
        }
    }

}
