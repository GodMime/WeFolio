package com.jxc.wefolio.job.service;

/**
 * 已冻结的视频审核请求，重提不得读取新全局配置。
 *
 * @param mode 持久模式代码
 * @param intervalSeconds 旧间隔模式秒数，平均模式必须为空
 * @param count 本轮截图数量
 * @param detectType 审核场景
 * @param detectContent 审核内容
 * @param callbackType 回调截图范围，显式取全部
 */
public record TencentCiVideoAuditRequest(String mode, Integer intervalSeconds, int count,
                                        String detectType, String detectContent, String callbackType) {
    /** 旧时间间隔模式代码。 */
    public static final String INTERVAL = "INTERVAL";
    /** 近似均匀平均截帧模式代码。 */
    public static final String AVERAGE = "AVERAGE";
    /** 审核所有有效场景。 */
    public static final String DETECT_TYPE_ALL = "all";
    /** 仅审核视频画面。 */
    public static final String DETECT_VIDEO_FRAMES = "0";
    /** 全部截图回调范围。 */
    public static final String CALLBACK_ALL = "1";

    /** 创建原间隔模式合同。 */
    public static TencentCiVideoAuditRequest interval(int seconds, int count) {
        return new TencentCiVideoAuditRequest(INTERVAL, seconds, count,
                DETECT_TYPE_ALL, DETECT_VIDEO_FRAMES, CALLBACK_ALL);
    }

    /** 创建新平均模式合同，不携带时间间隔。 */
    public static TencentCiVideoAuditRequest average(int count) {
        return new TencentCiVideoAuditRequest(AVERAGE, null, count,
                DETECT_TYPE_ALL, DETECT_VIDEO_FRAMES, CALLBACK_ALL);
    }

    /** 请求参数在调用供应商前校验，避免平均和间隔模式串参。 */
    public TencentCiVideoAuditRequest {
        if ((!INTERVAL.equals(mode) && !AVERAGE.equals(mode)) || count <= 0
                || (AVERAGE.equals(mode) && intervalSeconds != null)
                || (INTERVAL.equals(mode) && (intervalSeconds == null || intervalSeconds <= 0))
                || detectType == null || detectType.isBlank()
                || !DETECT_VIDEO_FRAMES.equals(detectContent) || !CALLBACK_ALL.equals(callbackType)) {
            throw new IllegalArgumentException("视频审核冻结参数非法");
        }
    }
}
