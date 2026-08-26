package com.jxc.wefolio.service;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 反馈业务统一时间源，确保写入数据库的无时区时间与后台任务口径一致。
 */
final class FeedbackTimeSource {

    /** 反馈业务时区标识。 */
    private static final String BUSINESS_ZONE_ID = "Asia/Shanghai";

    /** 反馈业务时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of(BUSINESS_ZONE_ID);

    /** 工具类不允许实例化。 */
    private FeedbackTimeSource() {
    }

    /** 创建反馈业务生产系统时钟。 */
    static Clock systemClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
