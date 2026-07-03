package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * “我的”基础信息资料读取与更新逻辑测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MineProfileServiceTest {

    @Mock
    private UserEntityMapper userEntityMapper;

    @Mock
    private CosService cosService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void profileContainsEditableBasicInformation() {
        UserEntity user = activeUser();
        user.setProfileTags("""
                [{"content":"高端婚礼","color":"#0f766e"},{"content":"双语主持","color":"#2d5f9a"}]
                """);
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        MineProfileResponse response = service.getProfile();

        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getUniqueCode()).isEqualTo("WF8392");
        assertThat(response.getDisplayName()).isEqualTo("林安 · 婚礼司仪");
        assertThat(response.getNickname()).isEqualTo("林安");
        assertThat(response.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        assertThat(response.getWechatQrUrl()).isEqualTo("https://example.com/wechat-qr.png");
        assertThat(response.getProfession()).isEqualTo("婚礼司仪");
        assertThat(response.getCity()).isEqualTo("上海、杭州、苏州");
        assertThat(response.getIntro()).isEqualTo("10 年婚礼主持经验");
        Object firstTag = response.getTags().get(0);
        Object secondTag = response.getTags().get(1);
        assertThat(firstTag)
                .hasFieldOrPropertyWithValue("content", "高端婚礼")
                .hasFieldOrPropertyWithValue("color", "#0f766e");
        assertThat(secondTag)
                .hasFieldOrPropertyWithValue("content", "双语主持")
                .hasFieldOrPropertyWithValue("color", "#2d5f9a");
    }

    @Test
    void profileReadsLegacyStringTagsWithDefaultColor() {
        UserEntity user = activeUser();
        user.setProfileTags("[\"高端婚礼\",\"双语主持\"]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        MineProfileResponse response = service.getProfile();

        Object firstTag = response.getTags().get(0);
        assertThat(firstTag)
                .hasFieldOrPropertyWithValue("content", "高端婚礼")
                .hasFieldOrPropertyWithValue("color", "#0f766e");
    }

    @Test
    void updateProfileTrimsFieldsAndPersistsTagsAsJson() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname(" 林安 ");
        request.setAvatarUrl(" https://cos.example.com/WF8392/others/avatar-20260703141000-a1b2c3d4.jpg ");
        request.setProfession(" 婚礼司仪 ");
        request.setCity(" 上海、杭州、苏州 ");
        request.setIntro(" 10 年婚礼主持经验 ");
        request.setTags((List) List.of(
                tag(" 高端婚礼 ", "#0F766E"),
                tag("双语主持", "#2d5f9a")
        ));
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/avatar-20260703141000-a1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("nickname", "avatar_url", "profession", "city", "intro", "profile_tags", "last_avatar_updated_at", "avatar_update_count");
        assertThat(user.getNickname()).isEqualTo("林安");
        assertThat(user.getAvatarUrl()).isEqualTo("https://cos.example.com/WF8392/others/avatar-20260703141000-a1b2c3d4.jpg");
        assertThat(user.getProfession()).isEqualTo("婚礼司仪");
        assertThat(user.getCity()).isEqualTo("上海、杭州、苏州");
        assertThat(user.getIntro()).isEqualTo("10 年婚礼主持经验");
        JSONArray tags = JSON.parseArray(user.getProfileTags());
        assertThat(tags).hasSize(2);
        assertThat(tags.getJSONObject(0).getString("content")).isEqualTo("高端婚礼");
        assertThat(tags.getJSONObject(0).getString("color")).isEqualTo("#0f766e");
        assertThat(tags.getJSONObject(1).getString("content")).isEqualTo("双语主持");
        assertThat(tags.getJSONObject(1).getString("color")).isEqualTo("#2d5f9a");
        verify(userEntityMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void updateProfilePreservesOmittedFields() {
        UserEntity user = activeUser();
        user.setProfileTags("[\"高端婚礼\",\"双语主持\"]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("新名字");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("nickname");
        assertThat(sqlSet).doesNotContain("updated_at");
        assertThat(sqlSet).doesNotContain("avatar_url", "profession", "city", "intro", "profile_tags");
        assertThat(user.getNickname()).isEqualTo("新名字");
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        assertThat(user.getProfession()).isEqualTo("婚礼司仪");
        assertThat(user.getCity()).isEqualTo("上海、杭州、苏州");
        assertThat(user.getIntro()).isEqualTo("10 年婚礼主持经验");
        assertThat(JSON.parseArray(user.getProfileTags(), String.class))
                .containsExactly("高端婚礼", "双语主持");
        verify(userEntityMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void updateProfilePersistsWechatQrUrlWhenPresent() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl(" https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.png ");
        when(cosService.headObject("WF8392/others/wechat-qr-20260703140512-a1b2c3d4.png"))
                .thenReturn(new CosService.ObjectHead("image/png", 1200L));

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        MineProfileResponse response = service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("wechat_qr_url");
        assertThat(user.getWechatQrUrl()).isEqualTo("https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.png");
        assertThat(response.getWechatQrUrl()).isEqualTo("https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.png");
    }

    @Test
    void updateProfileDelegatesUpdatedAtToAutoFill() {
        UserEntity user = activeUser();
        LocalDateTime oldUpdatedAt = LocalDateTime.of(2024, 1, 1, 10, 0);
        user.setUpdatedAt(oldUpdatedAt);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("林安");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<UserEntity> entityCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userEntityMapper).update(entityCaptor.capture(), any(Wrapper.class));
        assertThat(entityCaptor.getValue()).isNotNull();
        assertThat(user.getUpdatedAt()).isEqualTo(oldUpdatedAt);
        verify(userEntityMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void profileLogsMalformedTagsJson(CapturedOutput output) {
        UserEntity user = activeUser();
        user.setProfileTags("[,]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        MineProfileResponse response = service.getProfile();

        assertThat(response.getTags()).isEmpty();
        assertThat(output).contains("解析资料标签 JSON 失败");
        assertThat(output).contains("[,]");
    }

    @Test
    void updateProfileRejectsDuplicateTags() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags((List) List.of(
                tag("高端婚礼", "#0f766e"),
                tag(" 高端婚礼 ", "#2d5f9a")
        ));

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签不能重复");
    }

    @Test
    void updateProfileRejectsUnknownTagColor() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags((List) List.of(tag("高端婚礼", "#123456")));

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择有效的标签颜色");
    }

    /**
     * 资料图片报错文案应集中在 MineProfileMessage，避免服务层散落硬编码文案。
     *
     * @throws Exception 读取源码失败时抛出异常
     */
    @Test
    void profileAssetErrorMessagesUseDedicatedMessageInterface() throws Exception {
        String serviceSource = Files.readString(Path.of("src/main/java/com/jxc/wefolio/service/MineProfileService.java"));
        String messageSource = Files.readString(Path.of("src/main/java/com/jxc/wefolio/message/MineProfileMessage.java"));
        String entitySource = Files.readString(Path.of("src/main/java/com/jxc/wefolio/entity/UserEntity.java"));

        assertThat(messageSource)
                .contains("PROFILE_ASSET_REQUIRED_MESSAGE = \"资料图片不能为空\"")
                .contains("PROFILE_ASSET_TYPE_UNSUPPORTED_MESSAGE = \"资料图片类型不支持\"")
                .contains("WECHAT_QR_FORMAT_UNSUPPORTED_MESSAGE = \"微信二维码格式仅支持 JPG、PNG\"")
                .contains("PROFILE_ASSET_SIZE_INVALID_MESSAGE = \"资料图片大小异常\"")
                .contains("WECHAT_QR_SIZE_LIMIT_MESSAGE = \"微信二维码不能超过 300KB\"")
                .contains("AVATAR_SIZE_LIMIT_MESSAGE = \"头像文件不能超过 200KB\"")
                .contains("AVATAR_OWNERSHIP_INVALID_MESSAGE = \"头像地址不属于当前用户\"")
                .contains("WECHAT_QR_OWNERSHIP_INVALID_MESSAGE = \"微信二维码地址不属于当前用户\"")
                .contains("PROFILE_ASSET_EXPIRED_MESSAGE = \"资料图片不存在或已过期，请重新上传\"")
                .contains("WECHAT_QR_UPDATE_LIMIT_TEMPLATE = \"微信二维码当月更换次数已达上限（%d次），请下月再试\"");
        assertThat(serviceSource)
                .contains("MineProfileMessage.PROFILE_ASSET_REQUIRED_MESSAGE")
                .contains("MineProfileMessage.WECHAT_QR_UPDATE_LIMIT_TEMPLATE")
                .contains("UserEntity.WECHAT_QR_MONTHLY_MAX_COUNT")
                .doesNotContain("private static final int WECHAT_QR_MONTHLY_MAX_COUNT")
                .doesNotContain("\"资料图片不能为空\"")
                .doesNotContain("\"资料图片类型不支持\"")
                .doesNotContain("\"微信二维码格式仅支持 JPG、PNG\"")
                .doesNotContain("\"资料图片大小异常\"")
                .doesNotContain("\"微信二维码不能超过 300KB\"")
                .doesNotContain("\"头像文件不能超过 200KB\"")
                .doesNotContain("\"头像地址不属于当前用户\"")
                .doesNotContain("\"微信二维码地址不属于当前用户\"")
                .doesNotContain("\"资料图片不存在或已过期，请重新上传\"")
                .doesNotContain("\"微信二维码当月更换次数已达上限（\"");
        assertThat(entitySource)
                .contains("public static final int AVATAR_MONTHLY_MAX_COUNT = 10")
                .contains("public static final int WECHAT_QR_MONTHLY_MAX_COUNT = 3");
    }

    @Test
    void updateProfileAcceptsExpandedTagColor() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags((List) List.of(tag("舞台灯光", "#36516e")));

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        JSONArray tags = JSON.parseArray(user.getProfileTags());
        assertThat(tags.getJSONObject(0).getString("content")).isEqualTo("舞台灯光");
        assertThat(tags.getJSONObject(0).getString("color")).isEqualTo("#36516e");
    }

    @Test
    void createAvatarUploadTicketUsesNormalizedAvatarObjectKey() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(cosService.createPostUploadTicket(any(), any(), any(Long.class), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://upload.example.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        LocalDateTime.now().plusMinutes(15),
                        Map.of("key", invocation.getArgument(0))
                ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0));
        MineProfileAssetUploadTicketRequest request = new MineProfileAssetUploadTicketRequest();
        request.setAssetType("AVATAR");
        request.setMimeType("image/jpeg");
        request.setFileSize(1024L);

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        MineProfileAssetUploadTicketResponse response = service.createProfileAssetUploadTicket(request);

        assertThat(response.getAssetType()).isEqualTo("AVATAR");
        assertThat(response.getObjectKey()).startsWith("WF8392/others/avatar-");
        assertThat(response.getObjectKey()).matches("WF8392/others/avatar-\\d{14}-[a-f0-9]{8}\\.jpg");
        assertThat(response.getPublicUrl()).isEqualTo("https://cos.example.com/" + response.getObjectKey());
    }

    @Test
    void createWechatQrUploadTicketUsesNormalizedQrObjectKey() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(cosService.createPostUploadTicket(any(), any(), any(Long.class), any()))
                .thenAnswer(invocation -> new CosService.PostUploadTicket(
                        "https://upload.example.com",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        LocalDateTime.now().plusMinutes(15),
                        Map.of("key", invocation.getArgument(0))
                ));
        when(cosService.publicUrl(any())).thenAnswer(invocation -> "https://cos.example.com/" + invocation.getArgument(0));
        MineProfileAssetUploadTicketRequest request = new MineProfileAssetUploadTicketRequest();
        request.setAssetType("WECHAT_QR");
        request.setMimeType("image/png");
        request.setFileSize(200L * 1024L);

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        MineProfileAssetUploadTicketResponse response = service.createProfileAssetUploadTicket(request);

        assertThat(response.getAssetType()).isEqualTo("WECHAT_QR");
        assertThat(response.getObjectKey()).startsWith("WF8392/others/wechat-qr-");
        assertThat(response.getObjectKey()).matches("WF8392/others/wechat-qr-\\d{14}-[a-f0-9]{8}\\.png");
        assertThat(response.getMaxBytes()).isEqualTo(300L * 1024L - 1L);
    }

    @Test
    void createWechatQrUploadTicketRejectsOversizedFile() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileAssetUploadTicketRequest request = new MineProfileAssetUploadTicketRequest();
        request.setAssetType("WECHAT_QR");
        request.setMimeType("image/png");
        request.setFileSize(300L * 1024L);

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.createProfileAssetUploadTicket(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信二维码不能超过 300KB");
    }

    @Test
    void wechatQrSameUrlDoesNotIncrementCount() {
        UserEntity user = activeUser();
        user.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260701120000-a1b2c3d4.png");
        user.setLastWechatQrUpdatedAt(LocalDateTime.now().toLocalDate().atStartOfDay());
        user.setWechatQrUpdateCount(2);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260701120000-a1b2c3d4.png");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).isNull();
        assertThat(user.getWechatQrUpdateCount()).isEqualTo(2);
    }

    @Test
    void wechatQrAtLimitTwoSucceedsAndBecomesThree() {
        UserEntity user = activeUser();
        LocalDateTime lastUpdate = LocalDateTime.now().toLocalDate().atStartOfDay();
        user.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260701120000-a1b2c3d4.png");
        user.setLastWechatQrUpdatedAt(lastUpdate);
        user.setWechatQrUpdateCount(2);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png"))
                .thenReturn(new CosService.ObjectHead("image/png", 1200L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        assertThat(user.getWechatQrUpdateCount()).isEqualTo(3);
        assertThat(user.getLastWechatQrUpdatedAt()).isAfter(lastUpdate);
    }

    @Test
    void wechatQrExceedLimitThrowsWhenCountAlreadyThree() {
        UserEntity user = activeUser();
        user.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260701120000-a1b2c3d4.png");
        user.setLastWechatQrUpdatedAt(LocalDateTime.now().toLocalDate().atStartOfDay());
        user.setWechatQrUpdateCount(3);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(cosService.headObject("WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png"))
                .thenReturn(new CosService.ObjectHead("image/png", 1200L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信二维码当月更换次数已达上限（3次），请下月再试");
    }

    @Test
    void wechatQrRejectsEmptyCosObject() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(cosService.headObject("WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png"))
                .thenReturn(new CosService.ObjectHead("image/png", 0L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信二维码不能超过 300KB");
        verify(userEntityMapper, never()).update(any(UserEntity.class), any(Wrapper.class));
    }

    @Test
    void wechatQrCrossMonthResetsCountToOne() {
        UserEntity user = activeUser();
        user.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260701120000-a1b2c3d4.png");
        user.setLastWechatQrUpdatedAt(LocalDateTime.now().minusMonths(1).withDayOfMonth(1));
        user.setWechatQrUpdateCount(3);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png"))
                .thenReturn(new CosService.ObjectHead("image/png", 1200L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl("https://cos.example.com/WF8392/others/wechat-qr-20260703140512-f6e7d8c9.png");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        assertThat(user.getWechatQrUpdateCount()).isEqualTo(1);
    }

    @Test
    void wechatQrRejectsObjectKeyOutsideCurrentUserFolder() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setWechatQrUrl("https://cos.example.com/WF9999/others/wechat-qr-20260703140512-f6e7d8c9.png");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信二维码地址不属于当前用户");
    }

    // ── 头像月度更新次数限制 ──────────────────────────────

    @Test
    void avatarFirstUpdateSetsCountToOne() {
        UserEntity user = activeUser();
        // 首次更新：lastAvatarUpdatedAt 为 null，avatarUpdateCount 默认 0
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/avatar-20260703141000-a1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF8392/others/avatar-20260703141000-a1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("avatar_url", "last_avatar_updated_at", "avatar_update_count");
        assertThat(user.getAvatarUrl()).isEqualTo("https://cos.example.com/WF8392/others/avatar-20260703141000-a1b2c3d4.jpg");
        assertThat(user.getLastAvatarUpdatedAt()).isNotNull();
        assertThat(user.getAvatarUpdateCount()).isEqualTo(1);
        verify(cosService).headObject("WF8392/others/avatar-20260703141000-a1b2c3d4.jpg");
    }

    @Test
    void avatarSameMonthUpdateIncrementsCount() {
        UserEntity user = activeUser();
        LocalDateTime lastUpdate = LocalDateTime.now().toLocalDate().atStartOfDay();
        user.setLastAvatarUpdatedAt(lastUpdate);
        user.setAvatarUpdateCount(3);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/avatar-20260703141100-b1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF8392/others/avatar-20260703141100-b1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(4);
        assertThat(user.getLastAvatarUpdatedAt()).isAfter(lastUpdate);
    }

    @Test
    void avatarCrossMonthResetsCountToOne() {
        UserEntity user = activeUser();
        // 上次更新在上个月
        user.setLastAvatarUpdatedAt(LocalDateTime.now()
                .minusMonths(1)
                .withDayOfMonth(1)
                .withHour(15)
                .withMinute(0)
                .withSecond(0)
                .withNano(0));
        user.setAvatarUpdateCount(8);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/avatar-20260703141200-c1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF8392/others/avatar-20260703141200-c1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(1);
    }

    @Test
    void avatarCrossYearResetsCountToOne() {
        UserEntity user = activeUser();
        // 上次更新在去年
        user.setLastAvatarUpdatedAt(LocalDateTime.now()
                .minusYears(1)
                .withDayOfMonth(15)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0));
        user.setAvatarUpdateCount(5);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/avatar-20260703141300-d1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF8392/others/avatar-20260703141300-d1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(1);
    }

    @Test
    void avatarSameUrlDoesNotIncrementCount() {
        UserEntity user = activeUser();
        // 当前头像地址与请求相同
        user.setAvatarUrl("https://example.com/avatar.jpg");
        user.setLastAvatarUpdatedAt(LocalDateTime.now().toLocalDate().atStartOfDay());
        user.setAvatarUpdateCount(2);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        // 头像未变化，不应生成业务字段更新片段。
        assertThat(sqlSet).isNull();
        assertThat(user.getAvatarUpdateCount()).isEqualTo(2);
    }

    @Test
    void avatarBlankMatchesNullUrlAndDoesNotIncrementCount() {
        UserEntity user = activeUser();
        user.setAvatarUrl(null);
        user.setAvatarUpdateCount(2);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).isNull();
        assertThat(user.getAvatarUpdateCount()).isEqualTo(2);
    }

    @Test
    void avatarAtLimitNineSucceedsAndBecomesTen() {
        UserEntity user = activeUser();
        user.setLastAvatarUpdatedAt(LocalDateTime.now().toLocalDate().atStartOfDay());
        user.setAvatarUpdateCount(9);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);
        when(cosService.headObject("WF8392/others/avatar-20260703141400-e1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF8392/others/avatar-20260703141400-e1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(10);
    }

    @Test
    void avatarExceedLimitThrowsWhenCountAlreadyTen() {
        UserEntity user = activeUser();
        user.setLastAvatarUpdatedAt(LocalDateTime.now().toLocalDate().atStartOfDay());
        user.setAvatarUpdateCount(10);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(cosService.headObject("WF8392/others/avatar-20260703141500-f1b2c3d4.jpg"))
                .thenReturn(new CosService.ObjectHead("image/jpeg", 1024L));

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF8392/others/avatar-20260703141500-f1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当月头像更新次数已达上限（10次），请下月再试");
    }

    @Test
    void avatarRejectsObjectKeyOutsideCurrentUserFolder() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://cos.example.com/WF9999/others/avatar-20260703141600-a1b2c3d4.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("头像地址不属于当前用户");
    }

    @Test
    void avatarNullDoesNotTriggerLimitCheck() {
        UserEntity user = activeUser();
        user.setAvatarUpdateCount(11);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(any(UserEntity.class), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("新名字");

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(any(UserEntity.class), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        // 未传头像字段，不应包含追踪字段
        assertThat(sqlSet).doesNotContain("last_avatar_updated_at", "avatar_update_count");
    }

    @Test
    void updateProfileRejectsTooManyTags() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags((List) List.of(
                tag("一", "#0f766e"),
                tag("二", "#0f766e"),
                tag("三", "#0f766e"),
                tag("四", "#0f766e"),
                tag("五", "#0f766e"),
                tag("六", "#0f766e"),
                tag("七", "#0f766e"),
                tag("八", "#0f766e"),
                tag("九", "#0f766e"),
                tag("十", "#0f766e"),
                tag("第十一个超长标签", "#0f766e")
        ));

        MineProfileService service = new MineProfileService(userEntityMapper, cosService);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签最多保留 10 个");
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUniqueCode("WF8392");
        user.setNickname("林安");
        user.setAvatarUrl("https://example.com/avatar.jpg");
        user.setWechatQrUrl("https://example.com/wechat-qr.png");
        user.setProfession("婚礼司仪");
        user.setCity("上海、杭州、苏州");
        user.setIntro("10 年婚礼主持经验");
        user.setStatus("ACTIVE");
        return user;
    }

    private JSONObject tag(String content, String color) {
        JSONObject tag = new JSONObject();
        tag.put("content", content);
        tag.put("color", color);
        return tag;
    }
}
