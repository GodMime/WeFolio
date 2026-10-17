package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.repo.PortfolioAiReferenceRepository;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 根目录发现、24小时边界、锁内复核及跨轮游标补偿，不连接真实 COS。 */
class PortfolioAiReferenceCleanupServiceTest {
    private final PortfolioAiReferenceStorage storage = mock(PortfolioAiReferenceStorage.class);
    private final PortfolioAiReferenceRepository repository = mock(PortfolioAiReferenceRepository.class);
    private final PortfolioAiReferenceOwnerLock locks = mock(PortfolioAiReferenceOwnerLock.class);
    private final PortfolioAiReferenceRepository.Owner owner = new PortfolioAiReferenceRepository.Owner("USER", 2, "WFTEST");
    private final PortfolioAiReferenceCleanupService service = new PortfolioAiReferenceCleanupService(storage, repository, locks);
    private final Instant old = Instant.now().minusSeconds(90_000);

    private ObjectListing roots(String... codes) {
        ObjectListing page = new ObjectListing(); for (String code : codes) { page.getCommonPrefixes().add(code + "/"); } return page;
    }
    private String key(int i) { return "WFTEST/protfolio/ai-reference/" + String.format("%032x", i) + ".jpg"; }
    private COSObjectSummary object(int i, Instant modified) {
        COSObjectSummary item = new COSObjectSummary(); item.setKey(key(i)); item.setLastModified(Date.from(modified)); return item;
    }
    private ObjectMetadata head(Instant modified) { ObjectMetadata head = new ObjectMetadata(); head.setLastModified(Date.from(modified)); return head; }
    private void setup(ObjectListing objects) {
        when(storage.owners(null)).thenReturn(roots("WFTEST")); when(repository.owner("WFTEST")).thenReturn(Optional.of(owner));
        when(storage.objects("WFTEST", null)).thenReturn(objects); when(repository.protectedKeys(eq(owner), any())).thenReturn(Set.of());
        when(locks.tryAcquire("USER", 2)).thenAnswer(call -> Optional.of(mock(PortfolioAiReferenceOwnerLock.ExecutionLock.class)));
        when(storage.head(anyString())).thenReturn(head(old));
    }

    @Test void discoversNeverSavedObjectsAndHonorsCurrentReferencesAndActualWriteTime() {
        ObjectListing objects = new ObjectListing();
        objects.getObjectSummaries().add(object(1, old)); objects.getObjectSummaries().add(object(2, old));
        objects.getObjectSummaries().add(object(3, Instant.now())); objects.getObjectSummaries().add(object(4, old));
        setup(objects); when(repository.protectedKeys(eq(owner), any())).thenReturn(Set.of(key(2)));
        when(storage.head(key(4))).thenReturn(head(Instant.now()));
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage).delete(key(1)); verify(storage, never()).delete(key(2)); verify(storage, never()).head(key(3));
        verify(storage, never()).delete(key(4));
        var ordered = inOrder(locks, repository, storage);
        ordered.verify(locks).tryAcquire("USER", 2); ordered.verify(storage).requireVersioningOff();
        ordered.verify(repository).protectedKeys(eq(owner), any()); ordered.verify(storage).head(key(1)); ordered.verify(storage).delete(key(1));
    }

    @Test void failedDeleteDoesNotBlockOthersAndIsRediscoveredNextFullScan() {
        ObjectListing objects = new ObjectListing(); objects.getObjectSummaries().add(object(1, old)); objects.getObjectSummaries().add(object(2, old));
        setup(objects); doThrow(new IllegalStateException("temporary")).doNothing().when(storage).delete(key(1));
        assertThat(service.cleanup()).isEqualTo(1); assertThat(service.cleanup()).isEqualTo(2);
        verify(storage, times(2)).delete(key(1)); verify(storage, times(2)).delete(key(2));
    }

    @Test void unknownOwnerBadReferencesAndBusyLockNeverDelete() {
        ObjectListing objects = new ObjectListing(); objects.getObjectSummaries().add(object(1, old)); setup(objects);
        when(repository.owner("WFTEST")).thenReturn(Optional.empty()); service.cleanup(); verify(storage, never()).head(anyString());
        when(repository.owner("WFTEST")).thenReturn(Optional.of(owner));
        when(locks.tryAcquire("USER", 2)).thenReturn(Optional.empty()); service.cleanup(); verify(storage, never()).delete(anyString());
        when(locks.tryAcquire("USER", 2)).thenAnswer(call -> Optional.of(mock(PortfolioAiReferenceOwnerLock.ExecutionLock.class)));
        when(repository.protectedKeys(eq(owner), any())).thenThrow(new IllegalStateException("bad json"));
        service.cleanup(); verify(storage, never()).delete(anyString());
    }

    @Test void requestBudgetKeepsNestedCursorAcrossRunsAndEventuallyCoversWholeOwner() {
        ObjectListing objects = new ObjectListing(); for (int i = 1; i <= 250; i++) { objects.getObjectSummaries().add(object(i, old)); }
        setup(objects); int removed = 0;
        for (int run = 0; run < 4; run++) { removed += service.cleanup(); }
        assertThat(removed).isGreaterThanOrEqualTo(250);
        for (int i = 1; i <= 250; i++) { verify(storage, atLeastOnce()).delete(key(i)); }
        // 三轮即可覆盖全部，根页和 owner 子页没有在预算耗尽时丢失。
        verify(storage, atMost(2)).owners(null); verify(storage, atMost(2)).objects("WFTEST", null);
    }

    @Test void ownerListFailureContinuesLaterOwnerAndRootFailureRetainsMarker() {
        ObjectListing objects = new ObjectListing(); objects.getObjectSummaries().add(object(1, old)); setup(objects);
        when(storage.owners(null)).thenThrow(new IllegalStateException("root")).thenReturn(roots("BROKEN", "WFTEST"));
        when(repository.owner("BROKEN")).thenReturn(Optional.of(new PortfolioAiReferenceRepository.Owner("TEAM", 3, "BROKEN")));
        when(storage.objects("BROKEN", null)).thenThrow(new IllegalStateException("owner"));
        assertThat(service.cleanup()).isZero(); assertThat(service.cleanup()).isEqualTo(1);
        verify(storage, times(2)).owners(null); verify(storage).delete(key(1));
    }

    /** 锁竞争结束后应从同一页的未处理对象续跑，不能等待整轮回绕。 */
    @Test void busyLockRetainsCandidateAndStopsTheCurrentRunWithoutSpinning() {
        ObjectListing objects = new ObjectListing(); objects.getObjectSummaries().add(object(1, old)); setup(objects);
        when(locks.tryAcquire("USER", 2)).thenReturn(Optional.empty())
                .thenAnswer(call -> Optional.of(mock(PortfolioAiReferenceOwnerLock.ExecutionLock.class)));
        assertThat(service.cleanup()).isZero();
        verify(locks, times(1)).tryAcquire("USER", 2);
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage, times(1)).owners(null);
        verify(storage, times(1)).objects("WFTEST", null);
        verify(storage).delete(key(1));
    }

    @Test void versionedBucketStopsBeforeListingAndExactRetentionBoundaryExpires() {
        doThrow(new IllegalStateException("bucket versioned")).when(storage).requireVersioningOff();
        assertThat(service.cleanup()).isZero(); verify(storage, never()).owners(any());
        Instant wrote = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(PortfolioAiReferenceCleanupService.fresh(wrote, wrote.plusSeconds(86_399))).isTrue();
        assertThat(PortfolioAiReferenceCleanupService.fresh(wrote, wrote.plusSeconds(86_400))).isFalse();
    }
}
