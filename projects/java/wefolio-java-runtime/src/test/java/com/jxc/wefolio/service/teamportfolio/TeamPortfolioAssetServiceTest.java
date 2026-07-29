package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
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
    private static final long TEAM_ID = 11L;
    private static final long PORTFOLIO_ID = 13L;
    private static final String OWNER_UNIQUE_CODE = "WFOWNER";
    private static final String TEAM_UNIQUE_CODE = "TM2048";
    private static final String IMAGE_INVALID_MESSAGE = "团队作品集图片未完成上传或不可用";
    private static final String COVER_FILE = "cover-13-20260712153020-a1b2c3d4.jpg";
    private static final String QR_CONTACT_FILE = "qr-contact-13-20260712153120-b2c3d4e5.png";

    @Test
    void managerUploadUsesTeamUniqueCodeAndPersonalPortfolioFilenameConvention() {
        TestContext context = contextWithTeam();
        when(context.cosService.createPostUploadTicket(any(), any(), eq(300L * 1024L), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://upload.example.com", invocation.getArgument(0), invocation.getArgument(1), 300L * 1024L,
                        invocation.getArgument(3), Map.of("key", invocation.getArgument(0))));
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));

        var coverResponse = context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/jpeg", 1024L), USER_ID);
        var qrResponse = context.service.createUploadTicket(
                PORTFOLIO_ID, request("QR_CONTACT", "image/png", 1024L), USER_ID);
        var profileAvatarResponse = context.service.createUploadTicket(
                PORTFOLIO_ID, request("TEAM_PROFILE_AVATAR", "image/jpeg", 1024L), USER_ID);

        verify(context.access, org.mockito.Mockito.times(3)).requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID);
        assertThat(coverResponse.getObjectKey())
                .matches("TM2048/protfolio/cover-13-\\d{14}-[0-9a-f]{8}\\.jpg");
        assertThat(qrResponse.getObjectKey())
                .matches("TM2048/protfolio/qr-contact-13-\\d{14}-[0-9a-f]{8}\\.png");
        assertThat(qrResponse.getAssetType()).isEqualTo("QR_CONTACT");
        assertThat(profileAvatarResponse.getObjectKey())
                .matches("TM2048/protfolio/team-profile-avatar-13-\\d{14}-[0-9a-f]{8}\\.jpg");
        assertThat(profileAvatarResponse.getAssetType()).isEqualTo("TEAM_PROFILE_AVATAR");
    }

    @Test
    void uploadedImageValidationAcceptsOnlyExactOwnedPublicUrlAndVerifiedObjectHead() {
        TestContext context = contextWithTeam();
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
        TestContext context = contextWithTeam();
        List<String> invalidUrls = List.of(
                "https://external.example.com/" + ownedObjectKey(),
                cdnUrl("WFOWNER/protfolio/" + COVER_FILE),
                cdnUrl("TMOTHER/protfolio/" + COVER_FILE),
                cdnUrl("TM2048/protfolio/cover-14-20260712153020-a1b2c3d4.jpg"),
                cdnUrl("TM2048/protfolio/team-image-13-20260712153020-a1b2c3d4.jpg"),
                cdnUrl("TM2048/protfolio/cover-13-2026071215302-a1b2c3d4.jpg"),
                cdnUrl("TM2048/protfolio/cover-13-20260712153020-a1b2c3d.jpg"),
                cdnUrl(ownedObjectKey()) + "?download=1",
                cdnUrl("prefix-" + ownedObjectKey()),
                cdnUrl("TM2048/protfolio/not-an-asset.jpg"));

        for (String invalidUrl : invalidUrls) {
            assertThatThrownBy(() -> context.service.validateUploadedImageUrl(TEAM_ID, PORTFOLIO_ID, invalidUrl))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(IMAGE_INVALID_MESSAGE);
        }

        verify(context.cosService, never()).headObject(any());
    }

    @Test
    void uploadedImageValidationConvertsRemoteFailureAndRejectsBadMetadataWithoutLeakingMessage() {
        TestContext context = contextWithTeam();
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
        TestContext context = contextWithTeam();

        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("VIDEO", "video/mp4", 10L), USER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("TEAM_IMAGE", "image/jpeg", 10L), USER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/gif", 10L), USER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/jpeg", 300L * 1024L + 1L), USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(context.cosService, never()).createPostUploadTicket(any(), any(), anyLong(), any());
        verify(context.cosService, never()).publicUrl(any());
    }

    @Test
    void failedAccessStopsTeamLookupAndCosCalls() {
        TestContext context = context();
        when(context.access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID))
                .thenThrow(new BusinessException("无权限"));

        assertThatThrownBy(() -> context.service.createUploadTicket(
                PORTFOLIO_ID, request("COVER", "image/jpeg", 10L), USER_ID)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(context.teamMapper, context.cosService);
    }

    @Test
    void conservativeDeleteOnlyRemovesCurrentTeamExactPortfolioObjects() {
        TestContext context = contextWithTeam();
        String owned = ownedObjectKey();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String draft = JSON_STRING.formatted(
                cdnUrl(owned),
                cdnUrl("TMOTHER/protfolio/" + COVER_FILE),
                cdnUrl("TM2048/protfolio/cover-14-20260712153020-a1b2c3d4.jpg"),
                cdnUrl("WFMEMBER/work/image/" + COVER_FILE));

        context.service.deletePortfolioAssetsAfterCommit(TEAM_ID, PORTFOLIO_ID, draft, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(context.cosService).delete(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(owned);
    }

    @Test
    void cleanupIgnoresHistoricalPersonalUniqueCodeRoot() {
        TestContext context = contextWithTeam();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String personalRootObject = OWNER_UNIQUE_CODE + "/protfolio/" + COVER_FILE;
        String config = "{\"share\":{\"coverUrl\":\"" + cdnUrl(personalRootObject) + "\"}}";

        context.service.deletePortfolioAssetsAfterCommit(TEAM_ID, PORTFOLIO_ID, config, null);

        verify(context.cosService, never()).delete(any());
    }

    @Test
    void replacedPublishedCleanupUsesTheSameCurrentTeamAnchor() {
        TestContext context = contextWithTeam();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String owned = ownedObjectKey();
        String otherTeam = "TMOTHER/protfolio/" + COVER_FILE;
        String oldConfig = "{\"owned\":\"" + cdnUrl(owned) + "\","
                + "\"otherTeam\":\"" + cdnUrl(otherTeam) + "\"}";

        context.service.deleteUnreferencedAssetsAfterCommit(
                TEAM_ID, PORTFOLIO_ID, oldConfig, "{}");

        verify(context.cosService).delete(owned);
        verify(context.cosService, never()).delete(otherTeam);
    }

    @Test
    void stateDifferenceCleanupDeletesOnlyAssetsMissingFromBothCurrentStates() {
        TestContext context = contextWithTeam();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String cover = TEAM_UNIQUE_CODE + "/protfolio/" + COVER_FILE;
        String qrContact = TEAM_UNIQUE_CODE + "/protfolio/" + QR_CONTACT_FILE;
        String beforeState = "{\"draft\":{\"cover\":\"" + cdnUrl(cover) + "\"},"
                + "\"published\":{\"qr\":\"" + cdnUrl(qrContact) + "\"}}";
        String afterState = "{\"draft\":{},\"published\":{\"cover\":\""
                + cdnUrl(cover) + "\"}}";

        context.service.deleteUnreferencedAssetsAfterCommit(
                TEAM_ID, PORTFOLIO_ID, beforeState, afterState);

        verify(context.cosService).delete(qrContact);
        verify(context.cosService, never()).delete(cover);
    }

    @Test
    void movingAssetFromFirstMenuToSecondaryMenuDoesNotDeleteIt() {
        TestContext context = contextWithTeam();
        when(context.cosService.publicUrl(any())).thenAnswer(invocation -> cdnUrl(invocation.getArgument(0)));
        String qrContact = TEAM_UNIQUE_CODE + "/protfolio/" + QR_CONTACT_FILE;
        String publicUrl = cdnUrl(qrContact);
        String beforeState = """
                {"components":[{"componentKey":"component-qr","componentType":"QR_CONTACT",
                "config":{"qrUrl":"%s"}}]}
                """.formatted(publicUrl);
        String afterState = """
                {"components":[],"bottomNav":{"enabled":true,"items":[
                {"key":"nav_home","title":"主页"},
                {"key":"nav_contact","title":"联系","components":[
                {"componentKey":"component-qr","componentType":"QR_CONTACT",
                "config":{"qrUrl":"%s"}}]}]}}
                """.formatted(publicUrl);

        context.service.deleteUnreferencedAssetsAfterCommit(
                TEAM_ID, PORTFOLIO_ID, beforeState, afterState);

        verify(context.cosService, never()).delete(any());
    }

    private static final String JSON_STRING = """
            {"owned":"%s","otherTeam":"%s","otherPortfolio":"%s","memberWork":"%s"}
            """;

    private static TestContext contextWithTeam() {
        TestContext context = context();
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        team.setUniqueCode(TEAM_UNIQUE_CODE);
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team);
        return context;
    }

    private static TestContext context() {
        TeamPortfolioAccessService access = mock(TeamPortfolioAccessService.class);
        TeamEntity accessTeam = new TeamEntity();
        accessTeam.setId(TEAM_ID);
        when(access.requireMaintainablePortfolio(PORTFOLIO_ID, USER_ID)).thenReturn(
                new TeamPortfolioAccessService.TeamPortfolioAccess(null, accessTeam, null, true, true));
        TeamEntityMapper teamMapper = mock(TeamEntityMapper.class);
        CosService cosService = mock(CosService.class);
        return new TestContext(new TeamPortfolioAssetService(access, teamMapper, cosService),
                access, teamMapper, cosService);
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
        return TEAM_UNIQUE_CODE + "/protfolio/" + COVER_FILE;
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
            CosService cosService
    ) {
    }
}
