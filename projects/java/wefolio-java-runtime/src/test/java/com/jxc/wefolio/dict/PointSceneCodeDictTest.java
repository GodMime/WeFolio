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
}
