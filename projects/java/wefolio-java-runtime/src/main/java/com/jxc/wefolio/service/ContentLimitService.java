package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.ContentLimitProperties;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.MineWorkMessage;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 内容数量上限服务 — 统一校验作品和作品集的未删除记录容量。
 */
@Service
@RequiredArgsConstructor
public class ContentLimitService {

    private final ContentLimitProperties contentLimitProperties;
    private final WorkEntityMapper workEntityMapper;
    private final PortfolioEntityMapper portfolioEntityMapper;

    public void ensureWorkCapacity(Long userId, String mediaType, long additionalCount) {
        if (additionalCount <= 0L) {
            return;
        }
        int maxCount = resolveWorkMaxCount(mediaType);
        long currentCount = workEntityMapper.selectCount(
                Wrappers.lambdaQuery(WorkEntity.class)
                        .eq(WorkEntity::getUserId, userId)
                        .eq(WorkEntity::getMediaType, mediaType)
                        .eq(WorkEntity::getDeleted, 0L));
        if (currentCount > maxCount - additionalCount) {
            throw new BusinessException(String.format(resolveWorkLimitMessageTemplate(mediaType), maxCount));
        }
    }

    public void ensurePersonalPortfolioCapacity(Long userId) {
        ensurePortfolioCapacity(
                PortfolioOwnerTypeDict.USER.getCode(),
                userId,
                contentLimitProperties.getPersonalPortfolioMaxCount(),
                PortfolioMessage.PERSONAL_PORTFOLIO_COUNT_LIMIT_TEMPLATE);
    }

    public void ensureTeamPortfolioCapacity(Long teamId) {
        ensurePortfolioCapacity(
                PortfolioOwnerTypeDict.TEAM.getCode(),
                teamId,
                contentLimitProperties.getTeamPortfolioMaxCount(),
                TeamPortfolioMessage.PORTFOLIO_COUNT_LIMIT_TEMPLATE);
    }

    private void ensurePortfolioCapacity(String ownerType, Long ownerId, int maxCount, String messageTemplate) {
        long currentCount = portfolioEntityMapper.selectCount(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getOwnerType, ownerType)
                        .eq(PortfolioEntity::getOwnerId, ownerId)
                        .eq(PortfolioEntity::getDeleted, 0L));
        if (currentCount >= maxCount) {
            throw new BusinessException(String.format(messageTemplate, maxCount));
        }
    }

    private int resolveWorkMaxCount(String mediaType) {
        if (MediaTypeDict.IMAGE.getCode().equals(mediaType)) {
            return contentLimitProperties.getWorkImageMaxCount();
        }
        if (MediaTypeDict.VIDEO.getCode().equals(mediaType)) {
            return contentLimitProperties.getWorkVideoMaxCount();
        }
        throw new BusinessException(MineWorkMessage.WORK_MEDIA_TYPE_UNSUPPORTED_MESSAGE);
    }

    private String resolveWorkLimitMessageTemplate(String mediaType) {
        if (MediaTypeDict.IMAGE.getCode().equals(mediaType)) {
            return MineWorkMessage.IMAGE_WORK_COUNT_LIMIT_TEMPLATE;
        }
        return MineWorkMessage.VIDEO_WORK_COUNT_LIMIT_TEMPLATE;
    }
}
