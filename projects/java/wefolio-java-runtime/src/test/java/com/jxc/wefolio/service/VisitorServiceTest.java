package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.VisitorMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 访客身份服务测试 — 覆盖 openid 全局访客、资料 token 和头像归属校验。
 */
@ExtendWith(MockitoExtension.class)
class VisitorServiceTest {

    /** 访客 Mapper 模拟 */
    @Mock
    private VisitorEntityMapper visitorEntityMapper;

    /** 微信小程序客户端模拟 */
    @Mock
    private WechatMiniappClient wechatMiniappClient;

    /** COS 服务模拟 */
    @Mock
    private CosService cosService;

    @BeforeEach
    void setUp() {
        VisitorContextHolder.set(new VisitorContext(1024L, "visitor-a", "Bearer wf-visitor-v1.test"));
    }

    @AfterEach
    void tearDown() {
        VisitorContextHolder.clear();
    }

    @Test
    void resolveByLoginCodeShouldCreateGlobalVisitorWithPlainOpenidAndStableVisitorKey() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-plain-123");
        session.setUnionid("union-1");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        when(visitorEntityMapper.insertIgnore(any(VisitorEntity.class))).thenAnswer(invocation -> {
            VisitorEntity entity = invocation.getArgument(0);
            entity.setId(1024L);
            return 1;
        });

        VisitorService.VisitorSession result = service().resolveByLoginCode(" wx-code ");

        ArgumentCaptor<VisitorEntity> visitorCaptor = ArgumentCaptor.forClass(VisitorEntity.class);
        verify(visitorEntityMapper).insertIgnore(visitorCaptor.capture());
        VisitorEntity inserted = visitorCaptor.getValue();
        assertThat(inserted.getOpenid()).isEqualTo("openid-plain-123");
        assertThat(inserted.getUnionid()).isEqualTo("union-1");
        assertThat(inserted.getVisitorKey()).matches("[a-f0-9]{32}");
        assertThat(inserted.getLastSeenAt()).isNotNull();
        assertThat(result.newVisitor()).isTrue();
        assertThat(result.visitor().getId()).isEqualTo(1024L);
        assertThat(VisitorService.VisitorSession.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("visitor", "newVisitor", "anonymous");
    }

    @Test
    void resolveForOpenShouldHashAnonymousSessionWithoutWechatExchange() {
        String anonymousSessionId = "timeline-abc123def456ghi789jkl012mno345pqr678";
        when(visitorEntityMapper.insertIgnore(any(VisitorEntity.class))).thenAnswer(invocation -> {
            VisitorEntity entity = invocation.getArgument(0);
            entity.setId(2048L);
            return 1;
        });

        VisitorService.VisitorSession result = service().resolveForOpen(
                null, anonymousSessionId, "PERSONAL:88", null);

        ArgumentCaptor<VisitorEntity> visitorCaptor = ArgumentCaptor.forClass(VisitorEntity.class);
        verify(visitorEntityMapper).insertIgnore(visitorCaptor.capture());
        assertThat(visitorCaptor.getValue().getOpenid())
                .matches("timeline:[a-f0-9]{64}")
                .doesNotContain(anonymousSessionId)
                .doesNotContain("PERSONAL:88");
        assertThat(visitorCaptor.getValue().getUnionid()).isNull();
        assertThat(result.newVisitor()).isTrue();
        assertThat(result.anonymous()).isTrue();
        verifyNoInteractions(wechatMiniappClient);
    }

    @Test
    void resolveForOpenShouldPreferWechatLoginCodeOverAnonymousSession() {
        WechatSessionResponse session = new WechatSessionResponse();
        session.setOpenid("openid-priority");
        when(wechatMiniappClient.exchangeCode("wx-code")).thenReturn(session);
        when(visitorEntityMapper.insertIgnore(any(VisitorEntity.class))).thenAnswer(invocation -> {
            VisitorEntity entity = invocation.getArgument(0);
            entity.setId(2048L);
            return 1;
        });

        VisitorService.VisitorSession result = service().resolveForOpen(
                " wx-code ",
                "timeline-abc123def456ghi789jkl012mno345pqr678",
                null,
                null
        );

        assertThat(result.visitor().getOpenid()).isEqualTo("openid-priority");
        assertThat(result.anonymous()).isFalse();
        verify(wechatMiniappClient).exchangeCode("wx-code");
    }

    @Test
    void resolveForOpenShouldKeepLegacyMissingLoginCodeErrorForInvalidAnonymousSession() {
        assertThatThrownBy(() -> service().resolveForOpen(null, null, "PERSONAL:88", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        assertThatThrownBy(() -> service().resolveForOpen(null, "timeline-short", "PERSONAL:88", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        assertThatThrownBy(() -> service().resolveForOpen(
                null,
                "timeline-abc123def456ghi789jkl012mno345pqr678",
                "PERSONAL:invalid",
                null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
    }

    @Test
    void createAvatarUploadTicketShouldUseGlobalVisitFolderAndVisitorIdNaming() {
        VisitorService service = service();
        String token = service.createProfileToken(1024L, 88L, 33L);
        when(cosService.createPostUploadTicket(any(), eq("image/jpeg"), eq(200L * 1024L), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://cos-upload.example.com",
                        invocation.getArgument(0),
                        "image/jpeg",
                        200L * 1024L,
                        invocation.getArgument(3),
                        Map.of("key", invocation.getArgument(0))
                ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));
        VisitorAvatarUploadTicketRequest request = new VisitorAvatarUploadTicketRequest();
        request.setVisitorProfileToken(token);
        request.setMimeType("image/jpeg");
        request.setFileSize(120_000L);

        VisitorAvatarUploadTicketResponse response = service.createAvatarUploadTicket(88L, request);

        assertThat(response.getObjectKey()).matches("visit/visitor-avatar-1024-\\d{14}-[a-f0-9]{8}\\.jpg");
        assertThat(response.getPublicUrl()).isEqualTo("https://cdn.example.com/" + response.getObjectKey());
        assertThat(response.getUploadUrl()).isEqualTo("https://cos-upload.example.com");
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getMaxBytes()).isEqualTo(200L * 1024L);
    }

    @Test
    void saveProfileShouldRejectAvatarUrlOwnedByAnotherVisitor() {
        VisitorService service = service();
        String token = service.createProfileToken(1024L, 88L, 33L);
        VisitorProfileUpdateRequest request = new VisitorProfileUpdateRequest();
        request.setVisitorProfileToken(token);
        request.setNickname("小陈");
        request.setAvatarUrl("https://cdn.example.com/visit/visitor-avatar-2048-20260705093000-a1b2c3d4.jpg");

        assertThatThrownBy(() -> service.saveProfile(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访客头像归属不正确");
    }

    @Test
    void saveProfileShouldRequireNicknameAndAvatarUrl() {
        VisitorService service = service();
        String token = service.createProfileToken(1024L, 88L, 33L);
        VisitorProfileUpdateRequest request = new VisitorProfileUpdateRequest();
        request.setVisitorProfileToken(token);
        request.setNickname(" ");
        request.setAvatarUrl(" ");

        assertThatThrownBy(() -> service.saveProfile(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请填写访客昵称");

        request.setNickname("小陈");
        assertThatThrownBy(() -> service.saveProfile(88L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择访客头像");
    }

    @Test
    void profileActionsShouldRejectTokenOwnedByAnotherAuthenticatedVisitor() {
        VisitorService service = service();
        String token = service.createProfileToken(1024L, 88L, 33L);
        VisitorContextHolder.set(new VisitorContext(2048L, "visitor-b", "Bearer wf-visitor-v1.other"));
        VisitorAvatarUploadTicketRequest ticketRequest = new VisitorAvatarUploadTicketRequest();
        ticketRequest.setVisitorProfileToken(token);
        ticketRequest.setMimeType("image/jpeg");
        ticketRequest.setFileSize(120_000L);
        VisitorProfileUpdateRequest profileRequest = new VisitorProfileUpdateRequest();
        profileRequest.setVisitorProfileToken(token);
        profileRequest.setNickname("小陈");
        profileRequest.setAvatarUrl("https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg");

        assertThatThrownBy(() -> service.createAvatarUploadTicket(88L, ticketRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage(VisitorMessage.VISITOR_PROFILE_TOKEN_INVALID_MESSAGE);
        assertThatThrownBy(() -> service.saveProfile(88L, profileRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage(VisitorMessage.VISITOR_PROFILE_TOKEN_INVALID_MESSAGE);
    }


    @Test
    void saveProfileShouldValidateCosObjectAndUpdateVisitorProfileWithCurrentVersion() {
        VisitorService service = service();
        String token = service.createProfileToken(1024L, 88L, 33L);
        String avatarUrl = "https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg";
        VisitorEntity current = new VisitorEntity();
        current.setId(1024L);
        current.setVersion(7);
        when(cosService.headObject("visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 120_000L));
        when(visitorEntityMapper.selectById(1024L)).thenReturn(current);
        when(visitorEntityMapper.updateById(any(VisitorEntity.class))).thenReturn(1);
        VisitorProfileUpdateRequest request = new VisitorProfileUpdateRequest();
        request.setVisitorProfileToken(token);
        request.setNickname(" 小陈 ");
        request.setAvatarUrl(avatarUrl);

        service.saveProfile(88L, request);

        ArgumentCaptor<VisitorEntity> visitorCaptor = ArgumentCaptor.forClass(VisitorEntity.class);
        verify(visitorEntityMapper).selectById(1024L);
        verify(visitorEntityMapper).updateById(visitorCaptor.capture());
        VisitorEntity updated = visitorCaptor.getValue();
        assertThat(updated.getId()).isEqualTo(1024L);
        assertThat(updated.getVersion()).isEqualTo(7);
        assertThat(updated.getNickname()).isEqualTo("小陈");
        assertThat(updated.getAvatarUrl()).isEqualTo(avatarUrl);
        assertThat(updated.getProfileAuthorizedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    private VisitorService service() {
        return new VisitorService(
                visitorEntityMapper,
                wechatMiniappClient,
                new LocalCacheService(new LocalCacheProperties()),
                cosService,
                new VisitorIdentityPersistenceService(visitorEntityMapper));
    }
}
