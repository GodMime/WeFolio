package com.jxc.wefolio.dict;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分场景字典测试。
 */
class PointSceneCodeDictTest {

    @Test
    void monthlyWorkStorageShouldExposeChineseDisplayName() {
        assertThat(PointSceneCodeDict.MONTHLY_WORK_STORAGE.getCode()).isEqualTo("MONTHLY_WORK_STORAGE");
        assertThat(PointSceneCodeDict.fromCode("MONTHLY_WORK_STORAGE"))
                .contains(PointSceneCodeDict.MONTHLY_WORK_STORAGE);
        assertThat(PointSceneCodeDict.MONTHLY_WORK_STORAGE.getDisplayName()).isEqualTo("作品存储月费");
    }
}
