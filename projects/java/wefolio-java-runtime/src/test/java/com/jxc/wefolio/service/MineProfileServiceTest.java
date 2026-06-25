package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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

        MineProfileService service = new MineProfileService(userEntityMapper);

        MineProfileResponse response = service.getProfile();

        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getUniqueCode()).isEqualTo("WF8392");
        assertThat(response.getDisplayName()).isEqualTo("林安 · 婚礼司仪");
        assertThat(response.getNickname()).isEqualTo("林安");
        assertThat(response.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
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

        MineProfileService service = new MineProfileService(userEntityMapper);

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
        request.setAvatarUrl(" https://example.com/new-avatar.jpg ");
        request.setProfession(" 婚礼司仪 ");
        request.setCity(" 上海、杭州、苏州 ");
        request.setIntro(" 10 年婚礼主持经验 ");
        request.setTags((List) List.of(
                tag(" 高端婚礼 ", "#0F766E"),
                tag("双语主持", "#2d5f9a")
        ));
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("nickname", "avatar_url", "profession", "city", "intro", "profile_tags", "updated_at", "last_avatar_updated_at", "avatar_update_count");
        assertThat(user.getNickname()).isEqualTo("林安");
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/new-avatar.jpg");
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
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("新名字");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("nickname", "updated_at");
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
    void updateProfileRefreshesUpdatedAt() {
        UserEntity user = activeUser();
        LocalDateTime oldUpdatedAt = LocalDateTime.of(2024, 1, 1, 10, 0);
        user.setUpdatedAt(oldUpdatedAt);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("林安");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        verify(userEntityMapper).update(isNull(), any(Wrapper.class));
        assertThat(user.getUpdatedAt()).isAfter(oldUpdatedAt);
        verify(userEntityMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void profileLogsMalformedTagsJson(CapturedOutput output) {
        UserEntity user = activeUser();
        user.setProfileTags("[,]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileService service = new MineProfileService(userEntityMapper);
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

        MineProfileService service = new MineProfileService(userEntityMapper);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签不能重复");
    }

    @Test
    void updateProfileRejectsUnknownTagColor() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags((List) List.of(tag("高端婚礼", "#123456")));

        MineProfileService service = new MineProfileService(userEntityMapper);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择有效的标签颜色");
    }

    @Test
    void updateProfileAcceptsExpandedTagColor() {
        UserEntity user = activeUser();
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags((List) List.of(tag("舞台灯光", "#36516e")));

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        JSONArray tags = JSON.parseArray(user.getProfileTags());
        assertThat(tags.getJSONObject(0).getString("content")).isEqualTo("舞台灯光");
        assertThat(tags.getJSONObject(0).getString("color")).isEqualTo("#36516e");
    }

    // ── 头像月度更新次数限制 ──────────────────────────────

    @Test
    void avatarFirstUpdateSetsCountToOne() {
        UserEntity user = activeUser();
        // 首次更新：lastAvatarUpdatedAt 为 null，avatarUpdateCount 默认 0
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/new-avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("avatar_url", "last_avatar_updated_at", "avatar_update_count");
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/new-avatar.jpg");
        assertThat(user.getLastAvatarUpdatedAt()).isNotNull();
        assertThat(user.getAvatarUpdateCount()).isEqualTo(1);
    }

    @Test
    void avatarSameMonthUpdateIncrementsCount() {
        UserEntity user = activeUser();
        LocalDateTime lastUpdate = LocalDateTime.of(2026, 6, 10, 12, 0);
        user.setLastAvatarUpdatedAt(lastUpdate);
        user.setAvatarUpdateCount(3);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/another-avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(4);
        assertThat(user.getLastAvatarUpdatedAt()).isAfter(lastUpdate);
    }

    @Test
    void avatarCrossMonthResetsCountToOne() {
        UserEntity user = activeUser();
        // 上次更新在上个月
        user.setLastAvatarUpdatedAt(LocalDateTime.of(2026, 5, 28, 15, 0));
        user.setAvatarUpdateCount(8);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/new-month-avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(1);
    }

    @Test
    void avatarCrossYearResetsCountToOne() {
        UserEntity user = activeUser();
        // 上次更新在去年 12 月
        user.setLastAvatarUpdatedAt(LocalDateTime.of(2025, 12, 15, 10, 0));
        user.setAvatarUpdateCount(5);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/new-year-avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(1);
    }

    @Test
    void avatarSameUrlDoesNotIncrementCount() {
        UserEntity user = activeUser();
        // 当前头像地址与请求相同
        user.setAvatarUrl("https://example.com/avatar.jpg");
        user.setLastAvatarUpdatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        user.setAvatarUpdateCount(2);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        // 头像未变化，不应包含追踪字段
        assertThat(sqlSet).doesNotContain("last_avatar_updated_at", "avatar_update_count");
        assertThat(user.getAvatarUpdateCount()).isEqualTo(2);
    }

    @Test
    void avatarAtLimitNineSucceedsAndBecomesTen() {
        UserEntity user = activeUser();
        user.setLastAvatarUpdatedAt(LocalDateTime.of(2026, 6, 10, 10, 0));
        user.setAvatarUpdateCount(9);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/final-avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        assertThat(user.getAvatarUpdateCount()).isEqualTo(10);
    }

    @Test
    void avatarExceedLimitThrowsWhenCountAlreadyTen() {
        UserEntity user = activeUser();
        user.setLastAvatarUpdatedAt(LocalDateTime.of(2026, 6, 15, 9, 0));
        user.setAvatarUpdateCount(10);
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setAvatarUrl("https://example.com/exceed-avatar.jpg");

        MineProfileService service = new MineProfileService(userEntityMapper);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当月头像更新次数已达上限（10次），请下月再试");
    }

    @Test
    void avatarNullDoesNotTriggerLimitCheck() {
        UserEntity user = activeUser();
        user.setAvatarUpdateCount(11);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("新名字");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(isNull(), captor.capture());
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

        MineProfileService service = new MineProfileService(userEntityMapper);

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
