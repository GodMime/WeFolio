package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.repo.PortfolioAiReferenceRepository;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static com.jxc.wefolio.job.service.PortfolioAiReferenceTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 owner 与原件版本在每个媒体调用前重验，不使用回执代替当前授权。 */
class PortfolioAiReferenceAccessServiceTest {
    private final PortfolioAiMediaAccessService permissions = mock(PortfolioAiMediaAccessService.class);
    private final PortfolioAiReferenceRepository repository = mock(PortfolioAiReferenceRepository.class);
    private final PortfolioAiReferenceStorage storage = mock(PortfolioAiReferenceStorage.class);
    private final PortfolioAiReferenceAccessService service = new PortfolioAiReferenceAccessService(permissions, repository, storage, new PortfolioAiProperties());

    @Test void validatesObjectVersionBeforeSigningAndRejectsChangedOrMissingObject() {
        var task = task(item(1, false)); var item = item(1, false);
        when(repository.currentOwner(task)).thenReturn(new PortfolioAiReferenceRepository.Owner("USER", 2, "WFTEST"));
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentLength(4_000_000); metadata.setContentType("image/jpeg");
        metadata.setLastModified(new Date(1_700_000_000_000L)); metadata.setHeader("ETag", "version1");
        when(storage.head(item.path("objectKey").asText())).thenReturn(metadata);
        when(storage.sign(anyString(), any())).thenReturn("https://private.example/signed");
        assertThat(service.signed(task, List.of(item))).hasSize(1);
        metadata.setLastModified(new Date(1_700_000_000_001L));
        assertThatThrownBy(() -> service.signed(task, List.of(item))).isInstanceOf(PortfolioAiCallException.class);
        when(storage.head(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.signed(task, List.of(item))).isInstanceOf(PortfolioAiCallException.class);
        verify(storage, times(1)).sign(anyString(), any());
    }
    @Test void teamDowngradeAndCrossOwnerNeverReachStorage() {
        var task = task(item(1, false)); task.setOwnerType("TEAM");
        doThrow(new PortfolioAiCallException("SOURCE_UNAVAILABLE", "无权", false)).when(permissions).requireOwner(task);
        assertThatThrownBy(() -> service.verify(task, List.of(item(1, false)))).isInstanceOf(PortfolioAiCallException.class);
        verifyNoInteractions(storage, repository);
        reset(permissions);
        when(repository.currentOwner(task)).thenReturn(new PortfolioAiReferenceRepository.Owner("TEAM", 2, "TEAMCODE"));
        assertThatThrownBy(() -> service.verify(task, List.of(item(1, false)))).isInstanceOf(PortfolioAiCallException.class);
        verifyNoInteractions(storage);
    }
}
