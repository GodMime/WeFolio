package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.PortfolioBackgroundAudioSupport;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.BackgroundAudioConfigDto;
import com.jxc.wefolio.dto.BackgroundAudioRenderDto;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从当前作品记录生成背景音频展示数据，不保存地址，也不读取媒体文件。 */
@Service
@RequiredArgsConstructor
public class PortfolioBackgroundAudioService {
    /** 作品查询。 */
    private final WorkEntityMapper workMapper;
    /** 公开地址生成。 */
    private final CosService cosService;
    /** 复用团队成员授权检查。 */
    private final TeamSingleWorkComponentValidator teamWorkValidator;

    /** 个人作品集只展示作者自己的可用音频。 */
    public BackgroundAudioRenderDto renderPersonal(BackgroundAudioConfigDto config, Long ownerId) {
        BackgroundAudioRenderDto result = base(config);
        WorkEntity work = result.getWorkId() == null ? null : workMapper.selectById(result.getWorkId());
        if (work != null && ownerId != null && ownerId.equals(work.getUserId())) {
            populate(result, config, work);
        }
        return result;
    }

    /** 团队展示重新检查成员授权；失效只隐藏播放器，不清空配置与引用。 */
    public BackgroundAudioRenderDto renderTeam(BackgroundAudioConfigDto config, TeamPortfolioComponentContext context) {
        BackgroundAudioRenderDto result = base(config);
        if (result.getWorkId() == null) return result;
        try {
            teamWorkValidator.validateAudio(result.getWorkId(), context);
        } catch (BusinessException exception) {
            return result;
        }
        populate(result, config, workMapper.selectById(result.getWorkId()));
        return result;
    }

    /** 历史无配置时返回关闭默认值。 */
    private BackgroundAudioRenderDto base(BackgroundAudioConfigDto source) {
        BackgroundAudioConfigDto config = PortfolioBackgroundAudioSupport.normalize(source);
        BackgroundAudioRenderDto result = new BackgroundAudioRenderDto();
        result.setWorkId(config.getWorkId());
        result.setDisplayStyle(config.getDisplayStyle());
        return result;
    }

    /** 只为当前可用音频生成地址；关闭时仍提供选中作品信息供编辑器回显。 */
    private void populate(BackgroundAudioRenderDto result, BackgroundAudioConfigDto config, WorkEntity work) {
        if (work == null || !MediaTypeDict.AUDIO.getCode().equals(work.getMediaType())
                || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())
                || !StringUtils.hasText(work.getMediaObjectKey())) return;
        String coverKey = work.getCoverObjectKey();
        if (!WorkUploadTransactionService.DEFAULT_AUDIO_COVER_KEY.equals(coverKey)) {
            WorkEntity cover = StringUtils.hasText(coverKey) ? workMapper.selectOne(Wrappers.lambdaQuery(WorkEntity.class)
                    .eq(WorkEntity::getUserId, work.getUserId())
                    .eq(WorkEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                    .eq(WorkEntity::getStatus, WorkStatusDict.ACTIVE.getCode())
                    .eq(WorkEntity::getAuditStatus, WorkAuditStatusDict.PASSED.getCode())
                    .eq(WorkEntity::getMediaObjectKey, coverKey)) : null;
            if (cover == null) coverKey = WorkUploadTransactionService.DEFAULT_AUDIO_COVER_KEY;
        }
        result.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
        result.setTitle(work.getTitle());
        result.setDurationMs(work.getDurationMs());
        result.setMediaUrl(cosService.publicUrl(work.getMediaObjectKey()));
        result.setCoverUrl(cosService.publicUrl(coverKey));
    }
}
