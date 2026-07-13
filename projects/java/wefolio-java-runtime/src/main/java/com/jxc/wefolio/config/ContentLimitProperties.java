package com.jxc.wefolio.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容数量上限配置 — 控制作品和作品集可保留的未删除记录数量。
 */
@Getter
@Component
@ConfigurationProperties(prefix = "wefolio.content-limit")
public class ContentLimitProperties {

    /** 图片作品数量上限非法提示。 */
    private static final String WORK_IMAGE_MAX_COUNT_INVALID_MESSAGE = "图片作品数量上限必须大于 0";

    /** 视频作品数量上限非法提示。 */
    private static final String WORK_VIDEO_MAX_COUNT_INVALID_MESSAGE = "视频作品数量上限必须大于 0";

    /** 个人作品集数量上限非法提示。 */
    private static final String PERSONAL_PORTFOLIO_MAX_COUNT_INVALID_MESSAGE = "个人作品集数量上限必须大于 0";

    /** 团队作品集数量上限非法提示。 */
    private static final String TEAM_PORTFOLIO_MAX_COUNT_INVALID_MESSAGE = "团队作品集数量上限必须大于 0";

    /** 单用户图片作品最大数量。 */
    private int workImageMaxCount = 500;

    /** 单用户视频作品最大数量。 */
    private int workVideoMaxCount = 100;

    /** 单用户个人作品集最大数量。 */
    private int personalPortfolioMaxCount = 10;

    /** 单团队团队作品集最大数量。 */
    private int teamPortfolioMaxCount = 10;

    public void setWorkImageMaxCount(int workImageMaxCount) {
        this.workImageMaxCount = requirePositive(workImageMaxCount, WORK_IMAGE_MAX_COUNT_INVALID_MESSAGE);
    }

    public void setWorkVideoMaxCount(int workVideoMaxCount) {
        this.workVideoMaxCount = requirePositive(workVideoMaxCount, WORK_VIDEO_MAX_COUNT_INVALID_MESSAGE);
    }

    public void setPersonalPortfolioMaxCount(int personalPortfolioMaxCount) {
        this.personalPortfolioMaxCount = requirePositive(
                personalPortfolioMaxCount,
                PERSONAL_PORTFOLIO_MAX_COUNT_INVALID_MESSAGE);
    }

    public void setTeamPortfolioMaxCount(int teamPortfolioMaxCount) {
        this.teamPortfolioMaxCount = requirePositive(teamPortfolioMaxCount, TEAM_PORTFOLIO_MAX_COUNT_INVALID_MESSAGE);
    }

    private int requirePositive(int value, String errorMessage) {
        if (value <= 0) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }
}
