package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.alibaba.fastjson2.JSON;
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
        user.setProfileTags("[\"高端婚礼\",\"双语主持\"]");
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
        assertThat(response.getTags()).containsExactly("高端婚礼", "双语主持");
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
        request.setTags(List.of(" 高端婚礼 ", "双语主持"));
        when(userEntityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(request);

        ArgumentCaptor<Wrapper<UserEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userEntityMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<UserEntity>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("nickname", "avatar_url", "profession", "city", "intro", "profile_tags", "updated_at");
        assertThat(user.getNickname()).isEqualTo("林安");
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/new-avatar.jpg");
        assertThat(user.getProfession()).isEqualTo("婚礼司仪");
        assertThat(user.getCity()).isEqualTo("上海、杭州、苏州");
        assertThat(user.getIntro()).isEqualTo("10 年婚礼主持经验");
        assertThat(JSON.parseArray(user.getProfileTags(), String.class))
                .containsExactly("高端婚礼", "双语主持");
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
        request.setTags(List.of("高端婚礼", " 高端婚礼 "));

        MineProfileService service = new MineProfileService(userEntityMapper);

        assertThatThrownBy(() -> service.updateProfile(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签不能重复");
    }

    @Test
    void updateProfileRejectsTooManyTags() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags(List.of("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "第十一个超长标签"));

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
}
