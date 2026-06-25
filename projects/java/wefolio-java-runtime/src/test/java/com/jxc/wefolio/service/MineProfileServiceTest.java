package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MineProfileServiceTest {

    @Mock
    private UserEntityMapper userEntityMapper;

    @Test
    void profileContainsEditableBasicInformation() {
        UserEntity user = activeUser();
        user.setProfileTags("[\"高端婚礼\",\"双语主持\"]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileService service = new MineProfileService(userEntityMapper);

        MineProfileResponse response = service.getProfile(7L);

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
        when(userEntityMapper.updateById(any(UserEntity.class))).thenReturn(1);

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(7L, request);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userEntityMapper).updateById(captor.capture());
        UserEntity saved = captor.getValue();
        assertThat(saved.getNickname()).isEqualTo("林安");
        assertThat(saved.getAvatarUrl()).isEqualTo("https://example.com/new-avatar.jpg");
        assertThat(saved.getProfession()).isEqualTo("婚礼司仪");
        assertThat(saved.getCity()).isEqualTo("上海、杭州、苏州");
        assertThat(saved.getIntro()).isEqualTo("10 年婚礼主持经验");
        assertThat(JSON.parseArray(saved.getProfileTags(), String.class))
                .containsExactly("高端婚礼", "双语主持");
    }

    @Test
    void updateProfilePreservesOmittedFields() {
        UserEntity user = activeUser();
        user.setProfileTags("[\"高端婚礼\",\"双语主持\"]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.updateById(any(UserEntity.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("新名字");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(7L, request);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userEntityMapper).updateById(captor.capture());
        UserEntity saved = captor.getValue();
        assertThat(saved.getNickname()).isEqualTo("新名字");
        assertThat(saved.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        assertThat(saved.getProfession()).isEqualTo("婚礼司仪");
        assertThat(saved.getCity()).isEqualTo("上海、杭州、苏州");
        assertThat(saved.getIntro()).isEqualTo("10 年婚礼主持经验");
        assertThat(JSON.parseArray(saved.getProfileTags(), String.class))
                .containsExactly("高端婚礼", "双语主持");
    }

    @Test
    void updateProfileRefreshesUpdatedAt() {
        UserEntity user = activeUser();
        LocalDateTime oldUpdatedAt = LocalDateTime.of(2024, 1, 1, 10, 0);
        user.setUpdatedAt(oldUpdatedAt);
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        when(userEntityMapper.updateById(any(UserEntity.class))).thenReturn(1);

        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setNickname("林安");

        MineProfileService service = new MineProfileService(userEntityMapper);
        service.updateProfile(7L, request);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userEntityMapper).updateById(captor.capture());
        assertThat(captor.getValue().getUpdatedAt()).isAfter(oldUpdatedAt);
    }

    @Test
    void profileLogsMalformedTagsJson(CapturedOutput output) {
        UserEntity user = activeUser();
        user.setProfileTags("[,]");
        when(userEntityMapper.selectById(7L)).thenReturn(user);

        MineProfileService service = new MineProfileService(userEntityMapper);
        MineProfileResponse response = service.getProfile(7L);

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

        assertThatThrownBy(() -> service.updateProfile(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标签不能重复");
    }

    @Test
    void updateProfileRejectsTooManyTags() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        MineProfileUpdateRequest request = new MineProfileUpdateRequest();
        request.setTags(List.of("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一"));

        MineProfileService service = new MineProfileService(userEntityMapper);

        assertThatThrownBy(() -> service.updateProfile(7L, request))
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
