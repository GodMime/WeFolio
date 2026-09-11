package com.jxc.wefolio.common;

import com.jxc.wefolio.dto.BackgroundAudioConfigDto;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;

import java.util.Set;

/** 两类作品集共用的背景配置默认值、字段校验和引用构建。 */
public final class PortfolioBackgroundAudioSupport {

    /** 支持的三种展示样式。 */
    private static final Set<String> STYLES = Set.of(BackgroundAudioConfigDto.DISC,
            BackgroundAudioConfigDto.SLEEVE, BackgroundAudioConfigDto.MINI_PLAYER);

    /** 工具类不允许实例化。 */
    private PortfolioBackgroundAudioSupport() {
    }

    /** 复制三个配置字段并补默认值；作品授权由各自现有校验器负责。 */
    public static BackgroundAudioConfigDto normalize(BackgroundAudioConfigDto source) {
        BackgroundAudioConfigDto result = new BackgroundAudioConfigDto();
        result.setEnabled(source != null && Boolean.TRUE.equals(source.getEnabled()));
        result.setWorkId(source == null ? null : source.getWorkId());
        result.setDisplayStyle(source == null || source.getDisplayStyle() == null
                ? BackgroundAudioConfigDto.DISC : source.getDisplayStyle());
        if (!STYLES.contains(result.getDisplayStyle())
                || (result.getWorkId() != null && result.getWorkId() <= 0)) {
            throw new BusinessException(PortfolioMessage.BACKGROUND_AUDIO_CONFIG_INVALID);
        }
        return result;
    }

    /** 开启但未选择允许存草稿，不允许发布。 */
    public static void validateForPublish(BackgroundAudioConfigDto config) {
        if (config != null && Boolean.TRUE.equals(config.getEnabled()) && config.getWorkId() == null) {
            throw new BusinessException(PortfolioMessage.BACKGROUND_AUDIO_REQUIRED);
        }
    }

    /** 构建全局作品引用，不因关闭开关而释放；作品集和作用域由调用方填充。 */
    public static PortfolioReferenceEntity reference(BackgroundAudioConfigDto config) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setReferenceType(ReferenceTypeDict.WORK.getCode());
        reference.setReferenceId(config.getWorkId());
        reference.setComponentKey(BackgroundAudioConfigDto.CONFIG_KEY);
        reference.setComponentPath(BackgroundAudioConfigDto.WORK_PATH);
        reference.setSortOrder(0);
        reference.setIsValid(1);
        return reference;
    }
}
