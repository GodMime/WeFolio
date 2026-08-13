package com.jxc.wefolio.dict;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分场景字典测试。
 */
class PointSceneCodeDictTest {

    /**
     * 标准作品集场景应展示为发布标准作品集。
     */
    @Test
    void standardPortfolioShouldExposePublishDisplayName() {
        assertThat(PointSceneCodeDict.MAINTAIN_STANDARD_PORTFOLIO.getDisplayName())
                .isEqualTo("发布标准作品集");
    }

    /**
     * 高级作品集场景应展示为发布高级作品集。
     */
    @Test
    void advancedPortfolioShouldExposePublishDisplayName() {
        assertThat(PointSceneCodeDict.MAINTAIN_ADVANCED_PORTFOLIO.getDisplayName())
                .isEqualTo("发布高级作品集");
    }

    @Test
    void monthlyWorkStorageShouldExposeChineseDisplayName() {
        assertThat(PointSceneCodeDict.MONTHLY_WORK_STORAGE.getCode()).isEqualTo("MONTHLY_WORK_STORAGE");
        assertThat(PointSceneCodeDict.fromCode("MONTHLY_WORK_STORAGE"))
                .contains(PointSceneCodeDict.MONTHLY_WORK_STORAGE);
        assertThat(PointSceneCodeDict.MONTHLY_WORK_STORAGE.getDisplayName()).isEqualTo("作品存储月费");
    }

    /**
     * 动图上传应使用独立积分场景。
     */
    @Test
    void animationUploadShouldExposeIndependentScene() {
        assertThat(PointSceneCodeDict.UPLOAD_ANIMATION.getCode()).isEqualTo("UPLOAD_ANIMATION");
        assertThat(PointSceneCodeDict.UPLOAD_ANIMATION.getDisplayName()).isEqualTo("上传动图作品");
        assertThat(PointSceneCodeDict.fromCode("UPLOAD_ANIMATION"))
                .contains(PointSceneCodeDict.UPLOAD_ANIMATION);
        assertThat(PointSceneCodeDict.fromCode("UNKNOWN")).isEmpty();
    }

    /** 查档与留资必须使用独立访客积分场景。 */
    @Test
    void visitorIntentActionsShouldExposeIndependentScenes() {
        assertThat(PointSceneCodeDict.QUERY_PORTFOLIO_SCHEDULE.getCode())
                .isEqualTo("QUERY_PORTFOLIO_SCHEDULE");
        assertThat(PointSceneCodeDict.QUERY_PORTFOLIO_SCHEDULE.getDisplayName())
                .isEqualTo("访客查询档期");
        assertThat(PointSceneCodeDict.SUBMIT_CONTACT_LEAD.getCode())
                .isEqualTo("SUBMIT_CONTACT_LEAD");
        assertThat(PointSceneCodeDict.SUBMIT_CONTACT_LEAD.getDisplayName())
                .isEqualTo("访客预留联系信息");
        assertThat(PointSceneCodeDict.fromCode("QUERY_PORTFOLIO_SCHEDULE"))
                .contains(PointSceneCodeDict.QUERY_PORTFOLIO_SCHEDULE);
        assertThat(PointSceneCodeDict.fromCode("SUBMIT_CONTACT_LEAD"))
                .contains(PointSceneCodeDict.SUBMIT_CONTACT_LEAD);
    }
}
