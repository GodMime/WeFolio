package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.CosService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 团队作品集素材基础服务测试。 */
class TeamPortfolioAssetServiceTest {

    private static final long USER_ID = 7L;
    private static final long OWNER_USER_ID = 8L;
    private static final long TEAM_ID = 11L;
    private static final long PORTFOLIO_ID = 13L;
    private static final String OWNER_UNIQUE_CODE = "WFOWNER";
    private static final String IMAGE_INVALID_MESSAGE = "团队作品集图片未完成上传或不可用";
    private static final String UUID_FILE = "123e4567-e89b-12d3-a456-426614174000.jpg";

    @Test
    void managerUploadUsesTeamOwnerStableUniqueCodeAndExactPortfolioPrefix() {
        TestContext context = contextWithOwner();
        when(context.cosService.createPostUploadTicket(any(), eq("image/png"), eq(300L * 1024L), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://upload.example.com", invocation.getArgument(0), "image/png", 300L * 1024L,
                        invocation.getArgument(3), Map.of("key", invocation.getArgument(0))));
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));

        var response = context.service.createUploadTicket(
                PORTFOLIO_ID, request("QR_CONTACT", "image/png", 1024L), USER_ID);

        verify(context.access).requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID);
        assertThat(response.getObjectKey())
                .startsWith("WFOWNER/protfolio/team-11/portfolio-13/")
                .matches("WFOWNER/protfolio/team-11/portfolio-13/[0-9a-f-]{36}\\.png");
        assertThat(response.getAssetType()).isEqualTo("QR_CONTACT");
    }

    @Test
    void uploadedImageValidationAcceptsOnlyExactOwnedPublicUrlAndVerifiedObjectHead() {
        TestContext context = contextWithOwner();
        String objectKey = ownedObjectKey();
        String publicUrl = cdnUrl(objectKey);
        when(context.cosService.publicUrl(objectKey)).thenReturn(publicUrl);
        when(context.cosService.headObject(objectKey))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 300L * 1024L));

        context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, publicUrl);

        verify(context.cosService).headObject(objectKey);
    }

    @Test
    void uploadedImageValidationRejectsExternalCrossScopeQueryAndPseudoPrefixBeforeHead() {
        TestContext context = contextWithOwner();
        List<String> invalidUrls = List.of(
                "https://external.example.com/" + ownedObjectKey(),
                cdnUrl("WFMEMBER/work/image/" + UUID_FILE),
                cdnUrl("WFOTHER/protfolio/team-11/portfolio-13/" + UUID_FILE),
                cdnUrl("WFOWNER/protfolio/team-12/portfolio-13/" + UUID_FILE),
                cdnUrl("WFOWNER/protfolio/team-11/portfolio-14/" + UUID_FILE),
                cdnUrl(ownedObjectKey()) + "?download=1",
                cdnUrl("prefix-" + ownedObjectKey()),
                cdnUrl("WFOWNER/protfolio/team-11/portfolio-13/not-a-uuid.jpg"));

        for (String invalidUrl : invalidUrls) {
            assertThatThrownBy(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, invalidUrl))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(IMAGE_INVALID_MESSAGE);
        }

        verify(context.cosService, never()).headObject(any());
    }

    @Test
    void uploadedImageValidationConvertsRemoteFailureAndRejectsBadMetadataWithoutLeakingMessage() {
        TestContext context = contextWithOwner();
        String objectKey = ownedObjectKey();
        String publicUrl = cdnUrl(objectKey);
        when(context.cosService.publicUrl(objectKey)).thenReturn(publicUrl);
        doThrow(new IllegalStateException("secret-cos-message"))
                .when(context.cosService).headObject(objectKey);
        assertImageInvalid(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, publicUrl));

        doReturn(null).when(context.cosService).headObject(objectKey);
        assertImageInvalid(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, publicUrl));
        doReturn(new CosService.ObjectHead("image/gif", 100L))
                .when(context.cosService).headObject(objectKey);
        assertImageInvalid(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, publicUrl));
        doReturn(new CosService.ObjectHead("image/png", 0L))
                .when(context.cosService).headObject(objectKey);
        assertImageInvalid(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, publicUrl));
        doReturn(new CosService.ObjectHead("image/jpg", 300L * 1024L + 1L))
                .when(context.cosService).headObject(objectKey);
        assertImageInvalid(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, publicUrl));
    }

    @Test
    void invalidTicketTypeMimeAndSizeHaveZeroCosSideEffects() {
        TestContext context = contextWithOwner();

        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("VIDEO", "video/mp4", 10L), USER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/gif", 10L), USER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/jpeg", 300L * 1024L + 1L), USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(context.cosService, never()).createPostUploadTicket(any(), any(), anyLong(), any());
        verify(context.cosService, never()).publicUrl(any());
    }

    @Test
    void failedAccessStopsOwnerLookupAndCosCalls() {
        TestContext context = context();
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenThrow(new BusinessException("无权限"));

        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/jpeg", 10L), USER_ID)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(context.teamMapper, context.userMapper, context.cosService);
    }

    @Test
    void conservativeDeleteOnlyRemovesCurrentOwnerExactTeamPortfolioObjects() {
        TestContext context = contextWithOwner();
        String owned = ownedObjectKey();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String draft = JSON_STRING.formatted(
                cdnUrl(owned),
                cdnUrl("WFOTHER/protfolio/team-11/portfolio-13/" + UUID_FILE),
                cdnUrl("WFOWNER/protfolio/team-11/portfolio-14/" + UUID_FILE),
                cdnUrl("WFMEMBER/work/image/" + UUID_FILE));

        context.service.deletePortfolioAssetsAfterCommit(TEAM_ID, PORTFOLIO_ID, draft, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(context.cosService).delete(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(owned);
    }

    @Test
    void ownerTransferSafelyIgnoresOldOwnerRootInsteadOfTrustingArbitraryPrefix() {
        TestContext context = contextWithOwner();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String oldOwnerObject = "WFOLDOWNER/protfolio/team-11/portfolio-13/" + UUID_FILE;
        String config = "{\"share\":{\"coverUrl\":\"" + cdnUrl(oldOwnerObject) + "\"}}";

        context.service.deletePortfolioAssetsAfterCommit(TEAM_ID, PORTFOLIO_ID, config, null);

        verify(context.cosService, never()).delete(any());
    }

    @Test
    void replacedPublishedCleanupUsesTheSameCurrentOwnerAnchor() {
        TestContext context = contextWithOwner();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String owned = ownedObjectKey();
        String otherOwner = "WFOLDOWNER/protfolio/team-11/portfolio-13/" + UUID_FILE;
        String oldConfig = "{\"owned\":\"" + cdnUrl(owned) + "\","
                + "\"oldOwner\":\"" + cdnUrl(otherOwner) + "\"}";

        context.service.deleteReplacedPublishedAssetsAfterCommit(
                TEAM_ID, PORTFOLIO_ID, oldConfig, "{}");

        verify(context.cosService).delete(owned);
        verify(context.cosService, never()).delete(otherOwner);
    }

    private static final String JSON_STRING = """
            {"owned":"%s","otherOwner":"%s","otherPortfolio":"%s","memberWork":"%s"}
            """;

    private static TestContext contextWithOwner() {
        TestContext context = context();
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        team.setOwnerUserId(OWNER_USER_ID);
        UserEntity owner = new UserEntity();
        owner.setId(OWNER_USER_ID);
        owner.setUniqueCode(OWNER_UNIQUE_CODE);
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team);
        when(context.userMapper.selectById(OWNER_USER_ID)).thenReturn(owner);
        return context;
    }

    private static TestContext context() {
        TeamPortfolioAccessService access = mock(TeamPortfolioAccessService.class);
        TeamEntity accessTeam = new TeamEntity();
        accessTeam.setId(TEAM_ID);
        when(access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID)).thenReturn(
                new TeamPortfolioAccessService.TeamPortfolioAccess(null, accessTeam, null, true, true));
        TeamEntityMapper teamMapper = mock(TeamEntityMapper.class);
        UserEntityMapper userMapper = mock(UserEntityMapper.class);
        CosService cosService = mock(CosService.class);
        return new TestContext(new TeamPortfolioAssetService(access, teamMapper, userMapper, cosService),
                access, teamMapper, userMapper, cosService);
    }

    private static TeamPortfolioAssetUploadTicketRequest request(String type, String mime, long size) {
        TeamPortfolioAssetUploadTicketRequest request = new TeamPortfolioAssetUploadTicketRequest();
        request.setClientId("client-1");
        request.setAssetType(type);
        request.setMimeType(mime);
        request.setFileSize(size);
        return request;
    }

    private static String ownedObjectKey() {
        return OWNER_UNIQUE_CODE + "/protfolio/team-" + TEAM_ID + "/portfolio-" + PORTFOLIO_ID + "/" + UUID_FILE;
    }

    private static String cdnUrl(String objectKey) {
        return "https://cdn.example.com/" + objectKey;
    }

    private static void assertImageInvalid(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage(IMAGE_INVALID_MESSAGE)
                .hasMessageNotContaining("secret-cos-message");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    private record TestContext(
            TeamPortfolioAssetService service,
            TeamPortfolioAccessService access,
            TeamEntityMapper teamMapper,
            UserEntityMapper userMapper,
            CosService cosService
    ) {
    }
}
