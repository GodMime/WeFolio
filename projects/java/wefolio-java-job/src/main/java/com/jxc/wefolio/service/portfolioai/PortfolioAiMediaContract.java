package com.jxc.wefolio.service.portfolioai;

/** 两独立工程共用的媒体冻结、摘要结构、持久帧及分流合同，版本和单作品上限固定。 */
public final class PortfolioAiMediaContract {
    /** 兼容已经入队的无转写媒体任务。 */
    public static final String LEGACY_PLAN_VERSION = "portfolio-media-v1";
    /** 冻结音频文字的新媒体合同。 */
    public static final String PLAN_VERSION = "portfolio-media-v2";
    /** 持久视频摘要优先、缺失时强制帧兜底的内部合同。 */
    public static final String STORED_SUMMARY_PLAN_VERSION = "portfolio-media-v3";
    /** 直接消费入队时冻结的原视频摘要。 */
    public static final String ROUTE_STORED_VIDEO_SUMMARY = "STORED_VIDEO_SUMMARY";
    /** 新合同冻结的审核轮次，重审使旧任务失效。 */
    public static final String KEY_AUDIT_ROUND = "auditRound";
    /** 固定媒体合同：video-frames-v1。 */
    public static final String FRAME_SCHEMA = "video-frames-v1";
    /** 固定媒体合同：media-summary-v1。 */
    public static final String SUMMARY_VERSION = "media-summary-v1";
    /** 固定媒体合同：VIDEO。 */
    public static final String ROUTE_VIDEO = "VIDEO";
    /** 固定媒体合同：FRAMES。 */
    public static final String ROUTE_FRAMES = "FRAMES";
    /** 固定媒体合同：IMAGE。 */
    public static final String ROUTE_IMAGE = "IMAGE";
    /** 固定媒体合同：USER。 */
    public static final String OWNER_USER = "USER";
    /** 固定媒体合同：TEAM。 */
    public static final String OWNER_TEAM = "TEAM";
    /** 固定媒体合同：WORK。 */
    public static final String SOURCE_WORK = "WORK";
    /** 固定媒体合同：image_url。 */
    public static final String TYPE_IMAGE_URL = "image_url";
    /** 固定媒体合同：video_url。 */
    public static final String TYPE_VIDEO_URL = "video_url";
    /** 固定媒体合同：summary。 */
    public static final String SUMMARY = "summary";
    /** 固定媒体合同：observations。 */
    public static final String OBSERVATIONS = "observations";
    /** 固定媒体合同：limitations。 */
    public static final String LIMITATIONS = "limitations";
    /** 固定媒体合同：MEDIA_INVALID。 */
    public static final String ERROR_MEDIA_INVALID = "MEDIA_INVALID";
    /** 固定媒体合同：SOURCE_UNAVAILABLE。 */
    public static final String ERROR_SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
    /** 固定媒体合同：MEDIA_NOT_PREPARED。 */
    public static final String ERROR_MEDIA_NOT_PREPARED = "MEDIA_NOT_PREPARED";
    /** 媒体对象服务短暂故障，保留已付费摘要并允许有限重试。 */
    public static final String ERROR_MEDIA_ACCESS_RETRY = "MEDIA_ACCESS_RETRY";
    /** 固定媒体合同：MEDIA_BUDGET_EXHAUSTED。 */
    public static final String ERROR_BUDGET_EXHAUSTED = "MEDIA_BUDGET_EXHAUSTED";
    /** 固定媒体合同：MEDIA_BUDGET_OVERRUN。 */
    public static final String ERROR_BUDGET_OVERRUN = "MEDIA_BUDGET_OVERRUN";
    /** 媒体费用账本异常，隔离该任务并由管理员核对。 */
    public static final String ERROR_BUDGET_INVALID = "MEDIA_BUDGET_INVALID";
    /** 固定媒体合同：INVALID_MEDIA_SUMMARY。 */
    public static final String ERROR_INVALID_SUMMARY = "INVALID_MEDIA_SUMMARY";
    /** 固定媒体合同：CONTRACT_UNAVAILABLE。 */
    public static final String ERROR_CONTRACT_UNAVAILABLE = "CONTRACT_UNAVAILABLE";
    /** 固定媒体合同：TIMEOUT。 */
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    /** 媒体字段：mediaPlan。 */
    public static final String KEY_MEDIA_PLAN = "mediaPlan";
    /** 媒体字段：version。 */
    public static final String KEY_VERSION = "version";
    /** 媒体字段：entries。 */
    public static final String KEY_ENTRIES = "entries";
    /** 媒体字段：sourceNo。 */
    public static final String KEY_SOURCE_NO = "sourceNo";
    /** 媒体字段：workId。 */
    public static final String KEY_WORK_ID = "workId";
    /** 媒体字段：userId。 */
    public static final String KEY_USER_ID = "userId";
    /** 媒体字段：sourceObjectKey。 */
    public static final String KEY_SOURCE_OBJECT_KEY = "sourceObjectKey";
    /** 媒体字段：sourceVersion。 */
    public static final String KEY_SOURCE_VERSION = "sourceVersion";
    /** 媒体字段：sourceEtag。 */
    public static final String KEY_SOURCE_ETAG = "sourceEtag";
    /** 媒体字段：sourceBytes。 */
    public static final String KEY_SOURCE_BYTES = "sourceBytes";
    /** 媒体字段：mediaSha256。 */
    public static final String KEY_MEDIA_SHA256 = "mediaSha256";
    /** 媒体字段：durationMs。 */
    public static final String KEY_DURATION_MS = "durationMs";
    /** 媒体字段：durationSource。 */
    public static final String KEY_DURATION_SOURCE = "durationSource";
    /** 媒体字段：schemaVersion。 */
    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    /** 媒体字段：policyVersion。 */
    public static final String KEY_POLICY_VERSION = "policyVersion";
    /** 媒体字段：coverage。 */
    public static final String KEY_COVERAGE = "coverage";
    /** 媒体字段：frameCount。 */
    public static final String KEY_FRAME_COUNT = "frameCount";
    /** 媒体字段：frames。 */
    public static final String KEY_FRAMES = "frames";
    /** 媒体字段：objectKey。 */
    public static final String KEY_OBJECT_KEY = "objectKey";
    /** 媒体字段：timeMs。 */
    public static final String KEY_TIME_MS = "timeMs";
    /** 媒体字段：bytes。 */
    public static final String KEY_BYTES = "bytes";
    /** 媒体字段：width。 */
    public static final String KEY_WIDTH = "width";
    /** 媒体字段：height。 */
    public static final String KEY_HEIGHT = "height";
    /** 媒体字段：route。 */
    public static final String KEY_ROUTE = "route";
    /** 媒体字段：estimatedInputTokens。 */
    public static final String KEY_ESTIMATED_INPUT_TOKENS = "estimatedInputTokens";
    /** 媒体字段：manifestHash。 */
    public static final String KEY_MANIFEST_HASH = "manifestHash";
    /** 媒体字段：facts。 */
    public static final String KEY_FACTS = "facts";
    /** 媒体字段：sources。 */
    public static final String KEY_SOURCES = "sources";
    /** 媒体字段：canBind。 */
    public static final String KEY_CAN_BIND = "canBind";
    /** 媒体字段：kind。 */
    public static final String KEY_KIND = "kind";
    /** 媒体字段：sourceId。 */
    public static final String KEY_SOURCE_ID = "sourceId";
    /** 媒体字段：mediaSummary。 */
    public static final String KEY_MEDIA_SUMMARY = "mediaSummary";
    /** 媒体字段：modelConfig。 */
    public static final String KEY_MODEL_CONFIG = "modelConfig";
    /** 媒体字段：model。 */
    public static final String KEY_MODEL = "model";
    /** 媒体字段：maxTokens。 */
    public static final String KEY_MAX_TOKENS = "maxTokens";
    /** 媒体字段：mediaUrl。 */
    public static final String KEY_MEDIA_URL = "mediaUrl";
    /** 媒体字段：coverUrl。 */
    public static final String KEY_COVER_URL = "coverUrl";
    /** 媒体字段：maxSourceCount。 */
    public static final String KEY_MAX_SOURCE_COUNT = "maxSourceCount";
    /** 媒体身份与读取的固定值：/work/。 */
    public static final String WORK_DIRECTORY = "/work/";
    /** 媒体身份与读取的固定值：/others/video-frames/。 */
    public static final String FRAME_DIRECTORY = "/others/video-frames/";
    /** 媒体身份与读取的固定值：image/jpeg。 */
    public static final String JPEG_CONTENT_TYPE = "image/jpeg";
    /** 媒体身份与读取的固定值：SHA-256。 */
    public static final String SHA256_ALGORITHM = "SHA-256";
    /** 用户状态值域的在用状态。 */
    public static final String USER_ACTIVE = "ACTIVE";
    /** 作品状态值域的在用状态，不复用用户值域。 */
    public static final String WORK_ACTIVE = "ACTIVE";
    /** 团队状态值域的在用状态，不复用用户值域。 */
    public static final String TEAM_ACTIVE = "ACTIVE";
    /** 媒体身份与读取的固定值：JOINED。 */
    public static final String JOINED_STATUS = "JOINED";
    /** 媒体身份与读取的固定值：OWNER。 */
    public static final String OWNER_ROLE = "OWNER";
    /** 媒体身份与读取的固定值：MANAGER。 */
    public static final String MANAGER_ROLE = "MANAGER";
    /** 持久清单允许的最少帧数，未知或非正时长同样使用此值。 */
    public static final int FRAME_MIN_COUNT = 8;
    /** 单作品持久帧及单次图片模型输入的固定上限，不是历史批次预算。 */
    public static final int FRAME_MAX_COUNT = 24;
    /** 采样数量计算的目标间隔，只用于数量计算，不改变供应商真实采样时刻。 */
    public static final int FRAME_TARGET_SPAN_MS = 10_000;
    /** 规范化持久 JPEG 的固定最长边。 */
    public static final int FRAME_MAX_EDGE = 960;
    /** 规范化持久 JPEG 的固定单张字节上限，与原始截图下载预算分开。 */
    public static final int FRAME_MAX_BYTES = 200 * 1024;
    /** 原作品直接输入模型的真实 HEAD 大小上限。 */
    public static final long DIRECT_MEDIA_MAX_BYTES = 20L * 1024 * 1024;
    /** 直视频允许的前端时长上限，超出后使用持久帧。 */
    public static final int DIRECT_VIDEO_MAX_DURATION_MS = 15_000;
    /** 直视频保守输入估计的固定指令与首尾裕量。 */
    public static final long VIDEO_INPUT_OVERHEAD_TOKENS = 3_000;
    /** 直视频每秒保守输入估计，与真实调用预算结算分开。 */
    public static final long VIDEO_TOKENS_PER_SECOND = 2_800;
    /** 直视频保守输入估计上限，所有分流与调用前复核使用同一值。 */
    public static final long DIRECT_VIDEO_MAX_INPUT_TOKENS = 45_000;
    /** 单张图片分流时的保守输入估计。 */
    public static final long IMAGE_ESTIMATED_INPUT_TOKENS = 8_192;
    /** 每张持久帧分流时的保守输入估计。 */
    public static final long FRAME_ESTIMATED_INPUT_TOKENS = 1_536;
    /** 持久帧列表分流时的固定指令裕量。 */
    public static final long FRAMES_INPUT_OVERHEAD_TOKENS = 4_096;
    /** 媒体事实摘要的实际输出限制，独立于最终布局生成的输出预算。 */
    public static final int SUMMARY_OUTPUT_MAX_TOKENS = 4_096;

    /** 未知或非正时长不可直传，按不可估计的最大值返回。 */
    public static long estimateVideoInputTokens(Integer durationMs) {
        if (durationMs == null || durationMs <= 0) { return Long.MAX_VALUE; }
        return VIDEO_INPUT_OVERHEAD_TOKENS + (((long) durationMs + 999) / 1000) * VIDEO_TOKENS_PER_SECOND;
    }

    /** Runtime 分流与 Job 调用前复核共用直视频边界，不能各自修改阈值。 */
    public static boolean withinDirectVideoLimits(long sourceBytes, Integer durationMs) {
        return sourceBytes > 0 && sourceBytes <= DIRECT_MEDIA_MAX_BYTES
                && durationMs != null && durationMs > 0 && durationMs <= DIRECT_VIDEO_MAX_DURATION_MS
                && estimateVideoInputTokens(durationMs) <= DIRECT_VIDEO_MAX_INPUT_TOKENS;
    }

    /** 生产者与两个媒体消费者接受同一固定帧数值域。 */
    public static boolean isSupportedFrameCount(int count) {
        return count >= FRAME_MIN_COUNT && count <= FRAME_MAX_COUNT;
    }

    /** 禁止构造静态合同。 */
    private PortfolioAiMediaContract() { }
}
