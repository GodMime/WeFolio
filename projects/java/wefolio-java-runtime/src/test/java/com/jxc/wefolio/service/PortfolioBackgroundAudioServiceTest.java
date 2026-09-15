package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.BackgroundAudioConfigDto;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 背景音频渲染只读取有效授权作品；失效时静默隐藏，不改写持久化配置。 */
class PortfolioBackgroundAudioServiceTest {

    @Test
    void personalAudioShouldRenderCurrentWorkAndFallbackCover() {
        WorkEntityMapper mapper = mock(WorkEntityMapper.class);
        CosService cos = mock(CosService.class);
        when(cos.publicUrl(anyString())).thenAnswer(call -> "https://cdn/" + call.getArgument(0));
        WorkEntity work = new WorkEntity();
        work.setId(19L);
        work.setUserId(7L);
        work.setMediaType("AUDIO");
        work.setStatus("ACTIVE");
        work.setAuditStatus("PASSED");
        work.setMediaObjectKey("user/work/audio/music.mp3");
        work.setCoverObjectKey("missing.jpg");
        work.setTitle("音乐");
        work.setDurationMs(3000);
        when(mapper.selectById(19L)).thenReturn(work);
        PortfolioBackgroundAudioService service = new PortfolioBackgroundAudioService(mapper, cos,
                mock(TeamSingleWorkComponentValidator.class));
        BackgroundAudioConfigDto config = new BackgroundAudioConfigDto();
        config.setEnabled(true);
        config.setWorkId(19L);
        config.setDisplayStyle("SLEEVE");
        var render = service.renderPersonal(config, 7L);
        assertThat(render.getEnabled()).isTrue();
        assertThat(render.getMediaUrl()).isEqualTo("https://cdn/user/work/audio/music.mp3");
        assertThat(render.getCoverUrl()).endsWith(WorkUploadTransactionService.DEFAULT_AUDIO_COVER_KEY);
        assertThat(render.getDisplayStyle()).isEqualTo("SLEEVE");
        assertThat(render.getDurationMs()).isEqualTo(3000);
        assertThat(service.renderPersonal(config, 8L).getMediaUrl()).isNull();
        work.setAuditStatus("REJECTED");
        assertThat(service.renderPersonal(config, 7L).getEnabled()).isFalse();
        assertThat(config.getEnabled()).isTrue();
        assertThat(config.getWorkId()).isEqualTo(19L);
        assertThat(service.renderPersonal(null, 7L).getEnabled()).isFalse();
    }

    @Test
    void teamAudioMustNotExposeResourceAfterAuthorizationIsLost() {
        TeamSingleWorkComponentValidator validator = mock(TeamSingleWorkComponentValidator.class);
        doThrow(new BusinessException("成员不可用")).when(validator).validateAudio(any(), any());
        PortfolioBackgroundAudioService service = new PortfolioBackgroundAudioService(
                mock(WorkEntityMapper.class), mock(CosService.class), validator);
        BackgroundAudioConfigDto config = new BackgroundAudioConfigDto();
        config.setWorkId(19L);
        config.setEnabled(true);
        var render = service.renderTeam(config, new TeamPortfolioComponentContext(11L, 22L, 3));
        assertThat(render.getEnabled()).isFalse();
        assertThat(render.getMediaUrl()).isNull();
        assertThat(config.getWorkId()).isEqualTo(19L);
    }
}
