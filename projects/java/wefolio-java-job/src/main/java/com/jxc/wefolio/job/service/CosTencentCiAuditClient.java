package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameConfig;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ciModel.auditing.AudtingCommonInfo;
import com.qcloud.cos.model.ciModel.auditing.AuditingJobsDetail;
import com.qcloud.cos.model.ciModel.auditing.AudioAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.AudioAuditingResponse;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingResponse;
import com.qcloud.cos.model.ciModel.auditing.SnapshotInfo;
import com.qcloud.cos.model.ciModel.auditing.VideoAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.VideoAuditingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 COS SDK 的腾讯云数据万象审核客户端。
 */
@Slf4j
@Service
public class CosTencentCiAuditClient implements TencentCiAuditClient {

    /** 腾讯云审核结果：正常 */
    private static final String TENCENT_RESULT_PASS = "0";

    /** 腾讯云审核结果：违规 */
    private static final String TENCENT_RESULT_BLOCK = "1";

    /** 腾讯云审核结果：疑似 */
    private static final String TENCENT_RESULT_REVIEW = "2";

    /** 启用大图检测 */
    private static final String LARGE_IMAGE_DETECT_ENABLED = "1";

    /** 审核全部画面标签 */
    private static final String DETECT_TYPE_ALL = "all";

    /** 只审核视频画面截帧，不审核音频 */
    private static final String DETECT_CONTENT_VIDEO_FRAME = "0";

    /** 按时间间隔截帧 */
    private static final String SNAPSHOT_MODE_INTERVAL = "Interval";

    /** 平均截帧；首尾存在约半个采样间隔盲区。 */
    private static final String SNAPSHOT_MODE_AVERAGE = "Average";

    /** 腾讯云成功终态 */
    private static final String CI_STATE_SUCCESS = "Success";

    /** 腾讯云失败终态 */
    private static final String CI_STATE_FAILED = "Failed";

    /** 存储桶字段 */
    private static final String BUCKET_NAME_FIELD = "bucketName";

    /** 对象键字段 */
    private static final String OBJECT_KEY_FIELD = "objectKey";

    /** 审核类型字段 */
    private static final String DETECT_TYPE_FIELD = "detectType";

    /** 大图检测字段 */
    private static final String LARGE_IMAGE_DETECT_FIELD = "largeImageDetect";

    /** 腾讯云任务 ID 字段 */
    private static final String CI_JOB_ID_FIELD = "ciJobId";

    /** 新策略日志里的临时媒体地址占位。 */
    private static final String REDACTED_MEDIA_URL = "REDACTED_MEDIA_URL";

    /** 视频审核提交操作标识。 */
    private static final String VIDEO_SUBMIT_OPERATION = "SUBMIT";

    /** 视频审核查询操作标识。 */
    private static final String VIDEO_QUERY_OPERATION = "QUERY";

    private final COSClient cosClient;

    private final CosProperties cosProperties;

    /** 新审核无隐式重试的有界客户端，旧审核保持原行为。 */
    private final COSClient persistentVideoClient;

    /** 生产按模式选择专用客户端。 */
    @Autowired
    public CosTencentCiAuditClient(COSClient cosClient, CosProperties cosProperties,
                                   @Qualifier(VideoFrameConfig.COS_CLIENT_NAME) COSClient persistentVideoClient) {
        this.cosClient = cosClient;
        this.cosProperties = cosProperties;
        this.persistentVideoClient = persistentVideoClient;
    }

    /** 兼容原客户端测试构造。 */
    public CosTencentCiAuditClient(COSClient cosClient, CosProperties cosProperties) {
        this(cosClient, cosProperties, cosClient);
    }

    /** 复用异步审核结果结构，音频只提交 COS 对象地址。 */
    @Override
    public TencentCiAuditResult submitAudio(String objectKey) {
        AudioAuditingRequest request = new AudioAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.getInput().setObject(objectKey);
        request.getConf().setDetectType(DETECT_TYPE_ALL);
        logTencentPayload("腾讯云音频审核提交原始入参", JSON.toJSONString(request));
        AudioAuditingResponse response = cosClient.createAudioAuditingJobs(request);
        logTencentPayload("腾讯云音频审核提交原始出参", JSON.toJSONString(response));
        return toMediaResult(response.getJobsDetail(), JSON.toJSONString(response));
    }

    /** 音频查询沿用视频的终态与风险映射，不进行媒体信息探测。 */
    @Override
    public TencentCiAuditResult queryAudio(String ciJobId) {
        AudioAuditingRequest request = new AudioAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.setJobId(ciJobId);
        logTencentPayload("腾讯云音频审核查询原始入参", JSON.toJSONString(request));
        AudioAuditingResponse response = cosClient.describeAudioAuditingJob(request);
        logTencentPayload("腾讯云音频审核查询原始出参", JSON.toJSONString(response));
        return toMediaResult(response.getJobsDetail(), JSON.toJSONString(response));
    }

    @Override
    public TencentCiAuditResult auditImage(String objectKey) {
        ImageAuditingRequest request = new ImageAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.setObjectKey(objectKey);
        request.setDetectType(DETECT_TYPE_ALL);
        request.setLargeImageDetect(LARGE_IMAGE_DETECT_ENABLED);

        logTencentPayload("腾讯云图片审核原始入参", imageAuditRequestPayload(objectKey));
        log.info("腾讯云图片审核请求: bucketName={}, objectKey={}", cosProperties.getBucketName(), objectKey);
        ImageAuditingResponse response = cosClient.imageAuditing(request);
        log.info("腾讯云图片审核响应: objectKey={}, jobId={}, result={}, label={}, score={}",
                objectKey, response.getJobId(), response.getResult(), response.getLabel(), response.getScore());
        logTencentPayload("腾讯云图片审核原始出参", JSON.toJSONString(response));

        TencentCiAuditResult result = new TencentCiAuditResult(
                response.getJobId(),
                response.getState(),
                mapAuditResult(response.getResult()),
                parseInteger(response.getResult()),
                response.getLabel(),
                parseInteger(response.getScore()),
                true,
                false,
                JSON.toJSONString(response),
                collectImageRisks(response)
        );
        logAuditSummary("腾讯云图片审核简洁结果", objectKey, result);
        return result;
    }

    @Override
    public TencentCiAuditResult submitVideo(String objectKey, int snapshotIntervalSeconds, int snapshotCount) {
        return submitVideo(objectKey, TencentCiVideoAuditRequest.interval(snapshotIntervalSeconds, snapshotCount));
    }

    /** 冻结参数决定腾讯请求体；Average 严格不设置 TimeInterval。 */
    @Override
    public TencentCiAuditResult submitVideo(String objectKey, TencentCiVideoAuditRequest frozen) {
        VideoAuditingRequest request = new VideoAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.getInput().setObject(objectKey);
        request.getConf().setDetectType(frozen.detectType());
        request.getConf().setDetectContent(frozen.detectContent());
        request.getConf().setCallbackType(frozen.callbackType());
        boolean average = TencentCiVideoAuditRequest.AVERAGE.equals(frozen.mode());
        request.getConf().getSnapshot().setMode(average ? SNAPSHOT_MODE_AVERAGE : SNAPSHOT_MODE_INTERVAL);
        if (!average) {
            request.getConf().getSnapshot().setTimeInterval(String.valueOf(frozen.intervalSeconds()));
        }
        request.getConf().getSnapshot().setCount(String.valueOf(frozen.count()));

        logTencentPayload("腾讯云视频审核提交原始入参", request);
        log.info("腾讯云视频审核提交请求: bucketName={}, objectKey={}, mode={}, snapshotIntervalSeconds={}, snapshotCount={}",
                cosProperties.getBucketName(), objectKey, frozen.mode(), frozen.intervalSeconds(), frozen.count());
        VideoAuditingResponse response;
        try {
            // JSON 日志会调用 SDK 懒加载 getter 重建空 Mask，必须在日志之后清除，避免输出地域校验失败。
            request.getConf().setMask(null);
            response = (average ? persistentVideoClient : cosClient).createVideoAuditingJob(request);
        } catch (CosServiceException exception) {
            logVideoFailure(VIDEO_SUBMIT_OPERATION, objectKey, exception);
            throw exception;
        }
        AuditingJobsDetail detail = response.getJobsDetail();
        log.info("腾讯云视频审核提交响应: objectKey={}, jobId={}, state={}",
                objectKey, detail == null ? null : detail.getJobId(), detail == null ? null : detail.getState());
        logTencentPayload("腾讯云视频审核提交原始出参", safePersistentPayload(JSON.toJSONString(response), average));

        TencentCiAuditResult result = toVideoResult(response);
        logAuditSummary("腾讯云视频审核提交简洁结果", objectKey, result);
        return result;
    }

    @Override
    public TencentCiAuditResult queryVideo(String ciJobId) {
        return queryVideo(ciJobId, false);
    }

    /** 原 job 查询选择与提交相同的客户端合同。 */
    @Override
    public TencentCiAuditResult queryVideo(String ciJobId, boolean persistentFrames) {
        VideoAuditingRequest request = new VideoAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.setJobId(ciJobId);

        logTencentPayload("腾讯云视频审核查询原始入参", videoQueryRequestPayload(ciJobId));
        log.info("腾讯云视频审核查询请求: bucketName={}, ciJobId={}", cosProperties.getBucketName(), ciJobId);
        VideoAuditingResponse response;
        try {
            response = (persistentFrames ? persistentVideoClient : cosClient).describeAuditingJob(request);
        } catch (CosServiceException exception) {
            logVideoFailure(VIDEO_QUERY_OPERATION, ciJobId, exception);
            throw exception;
        }
        AuditingJobsDetail detail = response.getJobsDetail();
        log.info("腾讯云视频审核查询响应: ciJobId={}, state={}, result={}, label={}",
                ciJobId, detail == null ? null : detail.getState(),
                detail == null ? null : detail.getResult(), detail == null ? null : detail.getLabel());
        logTencentPayload("腾讯云视频审核查询原始出参", safePersistentPayload(JSON.toJSONString(response), persistentFrames));

        TencentCiAuditResult result = toVideoResult(response);
        logAuditSummary("腾讯云视频审核查询简洁结果", ciJobId, result);
        return result;
    }

    /** 在供应商边界记录原始错误字段，上层仍按原异常处理审核状态。 */
    private void logVideoFailure(String operation, String target, CosServiceException exception) {
        log.warn("腾讯云视频审核调用失败: operation={}, target={}, httpStatus={}, errorCode={}, requestId={}, errorMessage={}",
                operation, target, exception.getStatusCode(), exception.getErrorCode(),
                exception.getRequestId(), exception.getErrorMessage());
    }

    private TencentCiAuditResult toVideoResult(VideoAuditingResponse response) {
        return toMediaResult(response.getJobsDetail(), JSON.toJSONString(response));
    }

    /** 音频、视频共享腾讯异步结果结构。 */
    private TencentCiAuditResult toMediaResult(AuditingJobsDetail detail, String rawPayload) {
        if (detail == null) {
            return new TencentCiAuditResult(null, null, AuditResultDict.UNKNOWN,
                    null, null, null, false, false, rawPayload);
        }
        String ciState = detail.getState();
        boolean failed = CI_STATE_FAILED.equalsIgnoreCase(ciState);
        boolean terminal = failed || CI_STATE_SUCCESS.equalsIgnoreCase(ciState);
        return new TencentCiAuditResult(
                detail.getJobId(),
                ciState,
                failed ? AuditResultDict.UNKNOWN : mapAuditResult(detail.getResult()),
                parseInteger(detail.getResult()),
                detail.getLabel(),
                null,
                terminal,
                failed,
                rawPayload,
                collectVideoRisks(detail),
                collectVideoSnapshots(detail),
                parseInteger(detail.getSnapshotCount())
        );
    }

    /** 保留非法时间为 null 交由帧合同明确失败，禁止静默丢弃或伪造采样时刻。 */
    private List<VideoFrameInput> collectVideoSnapshots(AuditingJobsDetail detail) {
        if (detail.getSnapshotList() == null) {
            return List.of();
        }
        List<VideoFrameInput> frames = new ArrayList<>();
        for (SnapshotInfo snapshot : detail.getSnapshotList()) {
            Long timeMs = null;
            if (snapshot != null && snapshot.getSnapshotTime() != null) {
                try {
                    timeMs = Long.valueOf(snapshot.getSnapshotTime());
                } catch (NumberFormatException ignored) {
                    // 真实时间无法解析，完整帧校验会将本次审核置为技术失败。
                }
            }
            frames.add(new VideoFrameInput(timeMs, snapshot == null ? null : snapshot.getUrl(), null));
        }
        return List.copyOf(frames);
    }

    private List<TencentCiAuditRisk> collectImageRisks(ImageAuditingResponse response) {
        List<TencentCiAuditRisk> risks = new ArrayList<>();
        addRisk(risks, "Porn", response.getPornInfo());
        addRisk(risks, "Ads", response.getAdsInfo());
        addRisk(risks, "Politics", response.getPoliticsInfo());
        addRisk(risks, "Terrorism", response.getTerroristInfo());
        addRisk(risks, "Teenager", response.getTeenagerInfo());
        return List.copyOf(risks);
    }

    private List<TencentCiAuditRisk> collectVideoRisks(AuditingJobsDetail detail) {
        List<TencentCiAuditRisk> risks = new ArrayList<>();
        addRisk(risks, "Porn", detail.getPornInfo());
        addRisk(risks, "Ads", detail.getAdsInfo());
        addRisk(risks, "Politics", detail.getPoliticsInfo());
        addRisk(risks, "Terrorism", detail.getTerroristInfo());
        addRisk(risks, "Teenager", detail.getTeenagerInfo());
        addRisk(risks, "Meaningless", detail.getMeaninglessInfo());
        addRisk(risks, "Abuse", detail.getAbuseInfo());
        addRisk(risks, "Illegal", detail.getIllegalInfo());
        if (detail.getSnapshotList() != null) {
            for (SnapshotInfo snapshot : detail.getSnapshotList()) {
                if (snapshot == null) {
                    continue;
                }
                addRisk(risks, "Porn", snapshot.getPornInfo());
                addRisk(risks, "Ads", snapshot.getAdsInfo());
                addRisk(risks, "Politics", snapshot.getPoliticsInfo());
                addRisk(risks, "Terrorism", snapshot.getTerroristInfo());
                addRisk(risks, "Teenager", snapshot.getTeenagerInfo());
            }
        }
        return List.copyOf(risks);
    }

    private void addRisk(List<TencentCiAuditRisk> risks, String ciLabel, AudtingCommonInfo info) {
        if (info == null) {
            return;
        }
        AuditResultDict result = mapAuditResult(info.getHitFlag());
        if (result != AuditResultDict.BLOCK && result != AuditResultDict.REVIEW) {
            return;
        }
        risks.add(new TencentCiAuditRisk(ciLabel, result, parseInteger(info.getScore())));
    }

    private AuditResultDict mapAuditResult(String result) {
        if (TENCENT_RESULT_PASS.equals(result)) {
            return AuditResultDict.PASS;
        }
        if (TENCENT_RESULT_REVIEW.equals(result)) {
            return AuditResultDict.REVIEW;
        }
        if (TENCENT_RESULT_BLOCK.equals(result)) {
            return AuditResultDict.BLOCK;
        }
        return AuditResultDict.UNKNOWN;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> imageAuditRequestPayload(String objectKey) {
        return Map.of(
                BUCKET_NAME_FIELD, cosProperties.getBucketName(),
                OBJECT_KEY_FIELD, objectKey,
                DETECT_TYPE_FIELD, DETECT_TYPE_ALL,
                LARGE_IMAGE_DETECT_FIELD, LARGE_IMAGE_DETECT_ENABLED
        );
    }

    private Map<String, Object> videoQueryRequestPayload(String ciJobId) {
        return Map.of(
                BUCKET_NAME_FIELD, cosProperties.getBucketName(),
                CI_JOB_ID_FIELD, ciJobId
        );
    }

    private void logTencentPayload(String title, Object payload) {
        String rawPayload = payload instanceof String text ? text : JSON.toJSONString(payload);
        log.info("{}: {}", title, AuditLogSanitizer.sanitize(rawPayload, cosProperties));
    }

    /** 新帧日志不保留临时 URL，内容结论和实际截图数量仍可排查。 */
    private String safePersistentPayload(String payload, boolean persistent) {
        return persistent ? payload.replaceAll("https?://[^\\\"\\s]+", REDACTED_MEDIA_URL) : payload;
    }

    private void logAuditSummary(String title, String target, TencentCiAuditResult result) {
        log.info("{}: target={}, ciJobId={}, ciState={}, auditResult={}, ciResult={}, ciLabel={}, ciScore={}, "
                        + "terminal={}, providerFailed={}",
                title, target, result.ciJobId(), result.ciState(), result.auditResult().getCode(), result.ciResult(),
                result.ciLabel(), result.ciScore(), result.terminal(), result.providerFailed());
    }
}
