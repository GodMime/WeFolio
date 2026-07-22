package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ciModel.auditing.AudtingCommonInfo;
import com.qcloud.cos.model.ciModel.auditing.AuditingJobsDetail;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingResponse;
import com.qcloud.cos.model.ciModel.auditing.SnapshotInfo;
import com.qcloud.cos.model.ciModel.auditing.VideoAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.VideoAuditingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 COS SDK 的腾讯云数据万象审核客户端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
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

    /** 审核内容字段 */
    private static final String DETECT_CONTENT_FIELD = "detectContent";

    /** 截帧模式字段 */
    private static final String SNAPSHOT_MODE_FIELD = "snapshotMode";

    /** 截帧间隔字段 */
    private static final String SNAPSHOT_INTERVAL_SECONDS_FIELD = "snapshotIntervalSeconds";

    /** 截帧数量字段 */
    private static final String SNAPSHOT_COUNT_FIELD = "snapshotCount";

    /** 腾讯云任务 ID 字段 */
    private static final String CI_JOB_ID_FIELD = "ciJobId";

    private final COSClient cosClient;

    private final CosProperties cosProperties;

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
        VideoAuditingRequest request = new VideoAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.getInput().setObject(objectKey);
        request.getConf().setDetectType(DETECT_TYPE_ALL);
        request.getConf().setDetectContent(DETECT_CONTENT_VIDEO_FRAME);
        request.getConf().getSnapshot().setMode(SNAPSHOT_MODE_INTERVAL);
        request.getConf().getSnapshot().setTimeInterval(String.valueOf(snapshotIntervalSeconds));
        request.getConf().getSnapshot().setCount(String.valueOf(snapshotCount));

        logTencentPayload("腾讯云视频审核提交原始入参",
                videoSubmitRequestPayload(objectKey, snapshotIntervalSeconds, snapshotCount));
        log.info("腾讯云视频审核提交请求: bucketName={}, objectKey={}, snapshotIntervalSeconds={}, snapshotCount={}",
                cosProperties.getBucketName(), objectKey, snapshotIntervalSeconds, snapshotCount);
        VideoAuditingResponse response = cosClient.createVideoAuditingJob(request);
        AuditingJobsDetail detail = response.getJobsDetail();
        log.info("腾讯云视频审核提交响应: objectKey={}, jobId={}, state={}",
                objectKey, detail == null ? null : detail.getJobId(), detail == null ? null : detail.getState());
        logTencentPayload("腾讯云视频审核提交原始出参", JSON.toJSONString(response));

        TencentCiAuditResult result = toVideoResult(response);
        logAuditSummary("腾讯云视频审核提交简洁结果", objectKey, result);
        return result;
    }

    @Override
    public TencentCiAuditResult queryVideo(String ciJobId) {
        VideoAuditingRequest request = new VideoAuditingRequest();
        request.setBucketName(cosProperties.getBucketName());
        request.setJobId(ciJobId);

        logTencentPayload("腾讯云视频审核查询原始入参", videoQueryRequestPayload(ciJobId));
        log.info("腾讯云视频审核查询请求: bucketName={}, ciJobId={}", cosProperties.getBucketName(), ciJobId);
        VideoAuditingResponse response = cosClient.describeAuditingJob(request);
        AuditingJobsDetail detail = response.getJobsDetail();
        log.info("腾讯云视频审核查询响应: ciJobId={}, state={}, result={}, label={}",
                ciJobId, detail == null ? null : detail.getState(),
                detail == null ? null : detail.getResult(), detail == null ? null : detail.getLabel());
        logTencentPayload("腾讯云视频审核查询原始出参", JSON.toJSONString(response));

        TencentCiAuditResult result = toVideoResult(response);
        logAuditSummary("腾讯云视频审核查询简洁结果", ciJobId, result);
        return result;
    }

    private TencentCiAuditResult toVideoResult(VideoAuditingResponse response) {
        AuditingJobsDetail detail = response.getJobsDetail();
        if (detail == null) {
            return new TencentCiAuditResult(null, null, AuditResultDict.UNKNOWN,
                    null, null, null, false, false, JSON.toJSONString(response));
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
                JSON.toJSONString(response),
                collectVideoRisks(detail)
        );
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

    private Map<String, Object> videoSubmitRequestPayload(String objectKey, int snapshotIntervalSeconds,
                                                          int snapshotCount) {
        return Map.of(
                BUCKET_NAME_FIELD, cosProperties.getBucketName(),
                OBJECT_KEY_FIELD, objectKey,
                DETECT_TYPE_FIELD, DETECT_TYPE_ALL,
                DETECT_CONTENT_FIELD, DETECT_CONTENT_VIDEO_FRAME,
                SNAPSHOT_MODE_FIELD, SNAPSHOT_MODE_INTERVAL,
                SNAPSHOT_INTERVAL_SECONDS_FIELD, snapshotIntervalSeconds,
                SNAPSHOT_COUNT_FIELD, snapshotCount
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

    private void logAuditSummary(String title, String target, TencentCiAuditResult result) {
        log.info("{}: target={}, ciJobId={}, ciState={}, auditResult={}, ciResult={}, ciLabel={}, ciScore={}, "
                        + "terminal={}, providerFailed={}",
                title, target, result.ciJobId(), result.ciState(), result.auditResult().getCode(), result.ciResult(),
                result.ciLabel(), result.ciScore(), result.terminal(), result.providerFailed());
    }
}
