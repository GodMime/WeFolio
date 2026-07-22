package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ciModel.auditing.AuditingJobsDetail;
import com.qcloud.cos.model.ciModel.auditing.AdsInfo;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingResponse;
import com.qcloud.cos.model.ciModel.auditing.PornInfo;
import com.qcloud.cos.model.ciModel.auditing.SnapshotInfo;
import com.qcloud.cos.model.ciModel.auditing.VideoAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.VideoAuditingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 腾讯云数据万象审核客户端测试。
 */
class CosTencentCiAuditClientTest {

    /** 测试用存储桶名称 */
    private static final String BUCKET_NAME = "test-bucket";

    /** 测试用对象键 */
    private static final String OBJECT_KEY = "WFA3B1E7A2/work/video/demo.mp4";

    @Test
    void auditImageShouldEnableLargeImageAndMapConfirmedViolationToBlock() {
        COSClient cosClient = mock(COSClient.class);
        ImageAuditingResponse response = new ImageAuditingResponse();
        response.setJobId("image-job-id");
        response.setResult("1");
        response.setLabel("Porn");
        response.setScore("88");
        when(cosClient.imageAuditing(org.mockito.ArgumentMatchers.any(ImageAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditClient client = new CosTencentCiAuditClient(cosClient, testCosProperties());
        TencentCiAuditResult result = client.auditImage(OBJECT_KEY);

        ArgumentCaptor<ImageAuditingRequest> captor = ArgumentCaptor.forClass(ImageAuditingRequest.class);
        verify(cosClient).imageAuditing(captor.capture());
        ImageAuditingRequest request = captor.getValue();

        assertThat(request.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(request.getObjectKey()).isEqualTo(OBJECT_KEY);
        assertThat(request.getLargeImageDetect()).isEqualTo("1");
        assertThat(result.auditResult()).isEqualTo(AuditResultDict.BLOCK);
        assertThat(result.ciJobId()).isEqualTo("image-job-id");
        assertThat(result.ciResult()).isEqualTo(1);
        assertThat(result.ciLabel()).isEqualTo("Porn");
        assertThat(result.ciScore()).isEqualTo(88);
        assertThat(result.terminal()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "0,PASS",
            "1,BLOCK",
            "2,REVIEW"
    })
    void auditImageShouldMapAllOfficialResultValues(String ciResult, AuditResultDict expectedResult) {
        COSClient cosClient = mock(COSClient.class);
        ImageAuditingResponse response = new ImageAuditingResponse();
        response.setResult(ciResult);
        when(cosClient.imageAuditing(org.mockito.ArgumentMatchers.any(ImageAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditResult result = new CosTencentCiAuditClient(cosClient, testCosProperties())
                .auditImage(OBJECT_KEY);

        assertThat(result.auditResult()).isEqualTo(expectedResult);
        assertThat(result.ciResult()).isEqualTo(Integer.valueOf(ciResult));
    }

    @Test
    void auditImageShouldReturnEveryHitAuditScene() {
        COSClient cosClient = mock(COSClient.class);
        ImageAuditingResponse response = new ImageAuditingResponse();
        response.setResult("1");
        response.setLabel("Porn");
        PornInfo pornInfo = new PornInfo();
        pornInfo.setHitFlag("1");
        pornInfo.setScore("95");
        response.setPornInfo(pornInfo);
        AdsInfo adsInfo = new AdsInfo();
        adsInfo.setHitFlag("2");
        adsInfo.setScore("80");
        response.setAdsInfo(adsInfo);
        when(cosClient.imageAuditing(org.mockito.ArgumentMatchers.any(ImageAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditResult result = new CosTencentCiAuditClient(cosClient, testCosProperties())
                .auditImage(OBJECT_KEY);

        assertThat(result.risks()).containsExactly(
                new TencentCiAuditRisk("Porn", AuditResultDict.BLOCK, 95),
                new TencentCiAuditRisk("Ads", AuditResultDict.REVIEW, 80));
    }

    @Test
    void submitVideoShouldUseIntervalSnapshotAndNotCallback() {
        COSClient cosClient = mock(COSClient.class);
        VideoAuditingResponse response = new VideoAuditingResponse();
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setJobId("video-job-id");
        detail.setState("Submitted");
        response.setJobsDetail(detail);
        when(cosClient.createVideoAuditingJob(org.mockito.ArgumentMatchers.any(VideoAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditClient client = new CosTencentCiAuditClient(cosClient, testCosProperties());
        TencentCiAuditResult result = client.submitVideo(OBJECT_KEY, 60, 5);

        ArgumentCaptor<VideoAuditingRequest> captor = ArgumentCaptor.forClass(VideoAuditingRequest.class);
        verify(cosClient).createVideoAuditingJob(captor.capture());
        VideoAuditingRequest request = captor.getValue();

        assertThat(request.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(request.getInput().getObject()).isEqualTo(OBJECT_KEY);
        assertThat(request.getConf().getDetectType()).isEqualTo("all");
        assertThat(request.getConf().getDetectContent()).isEqualTo("0");
        assertThat(request.getConf().getCallback()).isNull();
        assertThat(request.getConf().getSnapshot().getMode()).isEqualTo("Interval");
        assertThat(request.getConf().getSnapshot().getTimeInterval()).isEqualTo("60");
        assertThat(request.getConf().getSnapshot().getCount()).isEqualTo("5");
        assertThat(result.ciJobId()).isEqualTo("video-job-id");
        assertThat(result.ciState()).isEqualTo("Submitted");
        assertThat(result.terminal()).isFalse();
    }

    @Test
    void queryVideoShouldMapSuspectedViolationToReview() {
        COSClient cosClient = mock(COSClient.class);
        VideoAuditingResponse response = new VideoAuditingResponse();
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setJobId("video-job-id");
        detail.setState("Success");
        detail.setResult("2");
        detail.setLabel("Ads");
        response.setJobsDetail(detail);
        when(cosClient.describeAuditingJob(org.mockito.ArgumentMatchers.any(VideoAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditClient client = new CosTencentCiAuditClient(cosClient, testCosProperties());
        TencentCiAuditResult result = client.queryVideo("video-job-id");

        ArgumentCaptor<VideoAuditingRequest> captor = ArgumentCaptor.forClass(VideoAuditingRequest.class);
        verify(cosClient).describeAuditingJob(captor.capture());
        VideoAuditingRequest request = captor.getValue();

        assertThat(request.getBucketName()).isEqualTo(BUCKET_NAME);
        assertThat(request.getJobId()).isEqualTo("video-job-id");
        assertThat(result.auditResult()).isEqualTo(AuditResultDict.REVIEW);
        assertThat(result.ciState()).isEqualTo("Success");
        assertThat(result.ciResult()).isEqualTo(2);
        assertThat(result.ciLabel()).isEqualTo("Ads");
        assertThat(result.terminal()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "0,PASS",
            "1,BLOCK",
            "2,REVIEW"
    })
    void queryVideoShouldMapAllOfficialResultValues(String ciResult, AuditResultDict expectedResult) {
        COSClient cosClient = mock(COSClient.class);
        VideoAuditingResponse response = new VideoAuditingResponse();
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setState("Success");
        detail.setResult(ciResult);
        response.setJobsDetail(detail);
        when(cosClient.describeAuditingJob(org.mockito.ArgumentMatchers.any(VideoAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditResult result = new CosTencentCiAuditClient(cosClient, testCosProperties())
                .queryVideo("video-job-id");

        assertThat(result.auditResult()).isEqualTo(expectedResult);
        assertThat(result.ciResult()).isEqualTo(Integer.valueOf(ciResult));
    }

    @Test
    void queryVideoShouldReturnRisksAcrossSnapshots() {
        COSClient cosClient = mock(COSClient.class);
        VideoAuditingResponse response = new VideoAuditingResponse();
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setState("Success");
        detail.setResult("1");
        detail.setLabel("Porn");
        SnapshotInfo first = new SnapshotInfo();
        PornInfo pornInfo = new PornInfo();
        pornInfo.setHitFlag("2");
        pornInfo.setScore("70");
        first.setPornInfo(pornInfo);
        SnapshotInfo second = new SnapshotInfo();
        AdsInfo adsInfo = new AdsInfo();
        adsInfo.setHitFlag("1");
        adsInfo.setScore("91");
        second.setAdsInfo(adsInfo);
        detail.setSnapshotList(List.of(first, second));
        response.setJobsDetail(detail);
        when(cosClient.describeAuditingJob(org.mockito.ArgumentMatchers.any(VideoAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditResult result = new CosTencentCiAuditClient(cosClient, testCosProperties())
                .queryVideo("video-job-id");

        assertThat(result.risks()).containsExactly(
                new TencentCiAuditRisk("Porn", AuditResultDict.REVIEW, 70),
                new TencentCiAuditRisk("Ads", AuditResultDict.BLOCK, 91));
    }

    @Test
    void queryVideoShouldTreatSubmittedAsRunning() {
        COSClient cosClient = mock(COSClient.class);
        VideoAuditingResponse response = new VideoAuditingResponse();
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setJobId("video-job-id");
        detail.setState("Submitted");
        response.setJobsDetail(detail);
        when(cosClient.describeAuditingJob(org.mockito.ArgumentMatchers.any(VideoAuditingRequest.class)))
                .thenReturn(response);

        TencentCiAuditClient client = new CosTencentCiAuditClient(cosClient, testCosProperties());
        TencentCiAuditResult result = client.queryVideo("video-job-id");

        assertThat(result.auditResult()).isEqualTo(AuditResultDict.UNKNOWN);
        assertThat(result.terminal()).isFalse();
        assertThat(result.ciState()).isEqualTo("Submitted");
    }

    private CosProperties testCosProperties() {
        CosProperties properties = new CosProperties();
        properties.setBucketName(BUCKET_NAME);
        return properties;
    }
}
