package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ciModel.xml.CIMediaXmlFactory;
import java.nio.charset.StandardCharsets;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ciModel.job.MediaJobResponse;
import com.qcloud.cos.model.ciModel.queue.MediaListQueueResponse;
import com.qcloud.cos.model.ciModel.queue.MediaQueueObject;
import com.qcloud.cos.model.ciModel.queue.MediaQueueRequest;
import com.qcloud.cos.model.ciModel.job.MediaJobsRequest;
import com.qcloud.cos.model.ciModel.job.MediaListJobResponse;
import com.qcloud.cos.model.ciModel.job.MediaJobObject;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.stream.IntStream;
import java.time.Instant;
import java.time.Duration;
import java.util.Date;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 历史 Snapshot 的有界队列选择、冻结参数和真实时间文件名解析。 */
class VideoFrameSnapshotClientTest {
    private COSClient cos;
    private VideoFrameSnapshotClient client;
    private VideoFrameProperties frames;
    /** 捕获安全诊断字段，防止供应商任意响应进入日志。 */
    private final Logger logger = (Logger) LoggerFactory.getLogger(VideoFrameSnapshotClient.class);
    private ListAppender<ILoggingEvent> events;
    private final VideoFrameSource source = new VideoFrameSource("OWNER/work/video/a.mp4","a".repeat(64),"etag",1000);
    private final String prefix = "OWNER/others/video-frames/1/source/policy/history/raw/";
    @BeforeEach
    void setup() {
        cos = mock(COSClient.class); CosProperties properties = new CosProperties();
        properties.setBucketName("bucket-1"); properties.setRegion("ap-guangzhou");
        frames = new VideoFrameProperties();
        client = new VideoFrameSnapshotClient(cos,properties,frames);
        events = new ListAppender<>(); events.start(); logger.addAppender(events);
    }
    /** 移除测试日志监听，避免其它用例受到影响。 */
    @AfterEach
    void detachLogger() { logger.detachAppender(events); events.stop(); }
    @Test
    void averageRequestNeverSetsTimeIntervalOrMediaInfoAndHasTimePlaceholder() {
        var request = client.buildRequest(source,8,prefix,"queue-1");
        assertEquals("Snapshot",request.getTag()); assertEquals("queue-1",request.getQueueId());
        assertEquals(source.objectKey(),request.getInput().getObject());
        var snapshot = request.getOperation().getSnapshot();
        assertEquals("Average",snapshot.getMode()); assertEquals("8",snapshot.getCount());
        assertNull(snapshot.getTimeInterval());
        String xml = new String(CIMediaXmlFactory.convertToXmlByteArray(request),StandardCharsets.UTF_8);
        assertFalse(xml.contains("<TimeInterval>")); assertFalse(xml.contains("<MediaInfo>"));
        assertTrue(xml.contains("<Mode>Average</Mode>"),xml);
        assertEquals(prefix + "raw-${number}-${time}.jpg",request.getOperation().getOutput().getObject());
        assertEquals("bucket-1",request.getOperation().getOutput().getBucket());
    }
    /** 显式队列配置直接复用，不增加查询请求。 */
    @Test
    void explicitQueueIdIsTrimmedWithoutQueryingVendorQueues() {
        frames.setCiQueueId(" queue-explicit ");
        assertEquals("queue-explicit", client.resolveQueueId());
        verifyNoInteractions(cos);
    }
    /** 默认自动选择仅查询当前桶的运行中普通媒体队列。 */
    @Test
    void defaultQueueUsesAutoAndOnlyQueriesExistingActiveOrdinaryQueue() {
        assertEquals(VideoFrameProperties.AUTO_QUEUE_ID, frames.getCiQueueId());
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 1, List.of(queue("queue-real", "Active", "Transcoding"))));
        assertEquals("queue-real", client.resolveQueueId());
        ArgumentCaptor<MediaQueueRequest> captor = ArgumentCaptor.forClass(MediaQueueRequest.class);
        verify(cos).describeMediaQueues(captor.capture());
        MediaQueueRequest request = captor.getValue();
        assertEquals("bucket-1", request.getBucketName());
        assertEquals("Active", request.getState());
        assertEquals("Transcoding", request.getCategory());
        assertEquals("1", request.getPageNumber());
        assertEquals("10", request.getPageSize());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 兼容旧空白配置，并将自动模式解析为真实队列。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "auto", " auto ", "AUTO"})
    void blankAndAutoConfigResolveSameRealQueue(String configured) {
        frames.setCiQueueId(configured);
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 1, List.of(queue("queue-real", "Active", "Transcoding"))));
        assertEquals("queue-real", client.resolveQueueId());
        verify(cos).describeMediaQueues(any());
    }
    /** 响应必须再次过滤暂停状态与其它类型，避免误用转码队列。 */
    @ParameterizedTest
    @CsvSource({"Paused,Transcoding", "Active,SpeedTranscoding", "Running,Transcoding", "Active,Transcode", "Active,''"})
    void automaticChoiceRejectsPausedAndOtherQueueCategories(String state, String category) {
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 1, List.of(queue("queue-other", state, category))));
        VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class, client::resolveQueueId);
        assertEquals("FRAME_QUEUE", failure.getStage());
        assertTrue(failure.getMessage().contains("没有可用"));
        verify(cos, never()).createMediaJobs(any());
    }
    /** 没有可用队列时在产生付费截图任务前拒绝执行。 */
    @Test
    void zeroAvailableQueuesFailsWithoutSubmittingPaidTask() {
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 0, List.of()));
        VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class, client::resolveQueueId);
        assertEquals("FRAME_QUEUE", failure.getStage());
        assertTrue(failure.getMessage().contains("没有可用"));
        verify(cos, never()).createMediaJobs(any());
    }
    /** 候选位于下一页时必须完整查询再作唯一判断。 */
    @Test
    void allPagesAreReadBeforeReturningUniqueUsableQueue() {
        List<MediaQueueObject> firstPage = IntStream.range(0, 10)
                .mapToObj(i -> queue("paused-" + i, "Paused", "Transcoding")).toList();
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 11, firstPage),
                queuePage(2, 11, List.of(queue("queue-real", "Active", "Transcoding"))));
        assertEquals("queue-real", client.resolveQueueId());
        ArgumentCaptor<MediaQueueRequest> captor = ArgumentCaptor.forClass(MediaQueueRequest.class);
        verify(cos, times(2)).describeMediaQueues(captor.capture());
        assertEquals(List.of("1", "2"), captor.getAllValues().stream().map(MediaQueueRequest::getPageNumber).toList());
    }
    /** 前一页有候选不能提前返回，后续歧义必须显式配置。 */
    @Test
    void secondUsableQueueOnLaterPageIsAmbiguousAndNeverSubmits() {
        List<MediaQueueObject> firstPage = IntStream.range(0, 10)
                .mapToObj(i -> queue("queue-" + i, i == 0 ? "Active" : "Paused", "Transcoding")).toList();
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 11, firstPage),
                queuePage(2, 11, List.of(queue("queue-extra", "Active", "Transcoding"))));
        VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class, client::resolveQueueId);
        assertEquals("FRAME_QUEUE", failure.getStage());
        assertTrue(failure.getMessage().contains("多个"));
        verify(cos, times(2)).describeMediaQueues(any());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 队列异常保留安全阶段原因，不能泄露远端错误原文或提交。 */
    @Test
    void queueQueryFailureDoesNotLeakVendorMessageOrSubmit() {
        when(cos.describeMediaQueues(any())).thenThrow(new IllegalStateException("secret=https://private.invalid"));
        VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class, client::resolveQueueId);
        assertEquals("FRAME_QUEUE", failure.getStage());
        assertTrue(failure.getMessage().contains("查询权限"));
        assertFalse(failure.getMessage().contains("private.invalid"));
        verify(cos, never()).createMediaJobs(any());
    }
    /** 缺失响应或分页元数据不能作为唯一队列依据。 */
    @Test
    void nullResponseOrMissingPageMetadataCannotBeUsedAsUniqueResult() {
        when(cos.describeMediaQueues(any())).thenReturn(null);
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        MediaListQueueResponse incomplete = new MediaListQueueResponse();
        incomplete.setQueueList(List.of(queue("queue-real", "Active", "Transcoding")));
        when(cos.describeMediaQueues(any())).thenReturn(incomplete);
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 非法或超出查询上限的总数直接中止，避免无界分页。 */
    @ParameterizedTest
    @ValueSource(strings = {"-1", "unknown", "9999999999999999999999", "201"})
    void illegalOrUnboundedTotalCountStopsAfterFirstRequest(String total) {
        MediaListQueueResponse response = queuePage(1, 1, List.of(queue("queue-real", "Active", "Transcoding")));
        response.setTotalCount(total);
        when(cos.describeMediaQueues(any())).thenReturn(response);
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos).describeMediaQueues(any());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 分页缺项必须拒绝，不能把截断列表误认成唯一候选。 */
    @Test
    void truncatedListPageIsRejectedRatherThanSelectingFirstQueue() {
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 2, List.of(queue("queue-real", "Active", "Transcoding"))));
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 服务器重复返回旧页时及时停止。 */
    @Test
    void repeatedPageIsRejectedWithoutUnboundedPolling() {
        List<MediaQueueObject> firstPage = IntStream.range(0, 10)
                .mapToObj(i -> queue("paused-" + i, "Paused", "Transcoding")).toList();
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 11, firstPage), queuePage(1, 11, firstPage));
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos, times(2)).describeMediaQueues(any());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 跨页重复队列身份意味着列表不可靠，不能选择。 */
    @Test
    void duplicateQueueIdentityAcrossPagesIsRejected() {
        List<MediaQueueObject> firstPage = IntStream.range(0, 10)
                .mapToObj(i -> queue("paused-" + i, "Paused", "Transcoding")).toList();
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 11, firstPage),
                queuePage(2, 11, List.of(queue("paused-0", "Active", "Transcoding"))));
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 分页期间队列总数变化时必须重新显式处理，不能使用混合快照。 */
    @Test
    void changingTotalBetweenPagesCannotProduceReliableUniqueQueue() {
        List<MediaQueueObject> firstPage = IntStream.range(0, 10)
                .mapToObj(i -> queue("paused-" + i, "Paused", "Transcoding")).toList();
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 11, firstPage),
                queuePage(2, 12, List.of(queue("queue-real", "Active", "Transcoding"))));
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 拒绝与当前存储桶不匹配的供应商队列。 */
    @Test
    void queueFromAnotherBucketIsRejected() {
        MediaQueueObject otherBucket = queue("queue-real", "Active", "Transcoding");
        otherBucket.setBucketId("other-bucket");
        when(cos.describeMediaQueues(any())).thenReturn(queuePage(1, 1, List.of(otherBucket)));
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class, client::resolveQueueId).getStage());
        verify(cos, never()).createMediaJobs(any());
    }
    /** 提交入口必须接收真实队列，不能将自动标识发送给供应商。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "auto", " auto ", "AUTO"})
    void submitRequiresResolvedQueueAndNeverSendsAutoToVendor(String queueId) {
        assertEquals("FRAME_QUEUE", assertThrows(VideoFrameProcessingException.class,
                () -> client.submit(source, 8, prefix, queueId)).getStage());
        verifyNoInteractions(cos);
    }
    /** 整批解析的队列沿提交透传，每作品不再重复查队列。 */
    @Test
    void submitUsesBatchResolvedQueueWithoutLookingItUpAgain() {
        MediaJobResponse response = new MediaJobResponse();
        response.getJobsDetail().setJobId("job-real");
        when(cos.createMediaJobs(any())).thenReturn(response);
        assertEquals("job-real", client.submit(source, 8, prefix, " queue-batch "));
        ArgumentCaptor<MediaJobsRequest> captor = ArgumentCaptor.forClass(MediaJobsRequest.class);
        verify(cos).createMediaJobs(captor.capture());
        assertEquals("queue-batch", captor.getValue().getQueueId());
        verify(cos, never()).describeMediaQueues(any());
    }
    @Test
    void readsSupplierRealMillisecondsAndDoesNotInventFrontendMidpoints() {
        ObjectListing listing = new ObjectListing();
        for (int i=0;i<8;i++) { COSObjectSummary object = new COSObjectSummary();
            object.setKey(prefix+"raw-"+i+"-"+(933+i*1900)+".jpg"); listing.getObjectSummaries().add(object); }
        when(cos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        List<VideoFrameInput> result = client.outputFrames(prefix,8);
        assertEquals(933L,result.getFirst().timeMs()); assertEquals(14233L,result.getLast().timeMs());
        assertTrue(result.stream().allMatch(frame -> frame.url()==null && frame.objectKey().startsWith(prefix)));
    }
    @Test
    void missingTimeAndTruncatedListFailRatherThanPublishingPartialAsset() {
        ObjectListing listing = new ObjectListing(); listing.setTruncated(true);
        when(cos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        assertThrows(VideoFrameProcessingException.class, () -> client.outputFrames(prefix,8));
        listing.setTruncated(false);
        IntStream.range(0,8).forEach(i -> { COSObjectSummary object = new COSObjectSummary();
            object.setKey(prefix+"raw-"+i+".jpg"); listing.getObjectSummaries().add(object); });
        assertThrows(VideoFrameProcessingException.class, () -> client.outputFrames(prefix,8));
    }
    @Test
    void definitiveSupplierFailureIsDistinguishedFromQueryNetworkFailure() {
        MediaJobResponse response = new MediaJobResponse(); response.getJobsDetail().setJobId("j-known");
        response.getJobsDetail().setState("Failed"); response.getJobsDetail().setCode("DecodeFailed");
        when(cos.describeMediaJob(any())).thenReturn(response);
        assertThrows(VideoFrameSnapshotTerminalException.class,() -> client.ready("j-known"));
        doThrow(new IllegalStateException("timeout")).when(cos).describeMediaJob(any());
        VideoFrameProcessingException failure=assertThrows(VideoFrameProcessingException.class,() -> client.ready("j-known"));
        assertEquals("FRAME_QUERY",failure.getStage());
    }
    @Test
    void pollsOriginalJobAndDoesNotAutomaticallyResubmitWhileRunning() {
        MediaJobResponse response = new MediaJobResponse(); response.getJobsDetail().setJobId("j-known");
        response.getJobsDetail().setState("Running");
        when(cos.describeMediaJob(any())).thenReturn(response);
        assertFalse(client.ready("j-known"));
        verify(cos,never()).createMediaJobs(any());
        response.getJobsDetail().setState("Success"); assertTrue(client.ready("j-known"));
    }

    /** 不根据单个前缀删除；任务必须来自同作者的源且输出到本桶、本地域。 */
    @Test
    void terminalCleanupRejectsForeignSourceBucketAndNonHistoricalPath() {
        MediaJobObject foreignOwner = terminalJob(); foreignOwner.getInput().setObject("OTHER/work/video/a.mp4");
        MediaJobObject foreignBucket = terminalJob(); foreignBucket.getOperation().getOutput().setBucket("other-bucket");
        MediaJobObject foreignRegion = terminalJob(); foreignRegion.getOperation().getOutput().setRegion("ap-other");
        MediaJobObject persistent = terminalJob(); persistent.getOperation().getOutput().setObject(historyPrefix() + "0001.jpg");
        MediaJobObject auditTask = terminalJob(); auditTask.getOperation().getOutput().setObject(
                historyPrefix().replace("history-00000000-0000-0000-0000-000000000001", "audit-1") + "raw-${number}-${time}.jpg");
        MediaListJobResponse page = new MediaListJobResponse();
        page.setJobsDetailList(List.of(foreignOwner, foreignBucket, foreignRegion, persistent, auditTask));
        when(cos.describeMediaJobs(any())).thenReturn(page);
        client.cleanupCompletedOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
        verify(cos, never()).listObjects(any(ListObjectsRequest.class));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 每轮只查一页，下一轮沿冻结范围推进，末页完成后重新从第一页扫描。 */
    @Test
    void cleanupPaginationIsBoundedAndContinuesAcrossMaintenanceRounds() {
        MediaListJobResponse first = new MediaListJobResponse(); first.setNextToken("next-1");
        MediaListJobResponse last = new MediaListJobResponse();
        when(cos.describeMediaJobs(any())).thenReturn(first, last, last);
        var lock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        client.cleanupCompletedOutputs(lock);
        client.cleanupCompletedOutputs(lock);
        client.cleanupCompletedOutputs(lock);
        var requests = ArgumentCaptor.forClass(MediaJobsRequest.class);
        verify(cos, times(3)).describeMediaJobs(requests.capture());
        assertNull(requests.getAllValues().get(0).getNextToken());
        assertEquals("next-1", requests.getAllValues().get(1).getNextToken());
        assertNull(requests.getAllValues().get(2).getNextToken());
        assertEquals(requests.getAllValues().get(0).getEndCreationTime(), requests.getAllValues().get(1).getEndCreationTime());
        assertEquals(20, requests.getAllValues().get(0).getSize());
        assertEquals("Success,Failed,Cancel", requests.getAllValues().get(0).getStates());
    }

    /** 媒体任务列表只接受数字时区，UTC 的 Z 表达会被供应商拒绝。 */
    @Test
    void cleanupQueryUsesWholeSecondUtcNumericOffsetAndUnchangedThirtyDayRange() {
        when(cos.describeMediaJobs(any())).thenReturn(new MediaListJobResponse());
        client.cleanupCompletedOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class));
        var requests = ArgumentCaptor.forClass(MediaJobsRequest.class);
        verify(cos).describeMediaJobs(requests.capture());
        var request = requests.getValue();
        assertTrue(request.getStartCreationTime().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\+0000"), request.getStartCreationTime());
        assertTrue(request.getEndCreationTime().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\+0000"), request.getEndCreationTime());
        Instant start = Instant.parse(request.getStartCreationTime().replace("+0000", "Z"));
        Instant end = Instant.parse(request.getEndCreationTime().replace("+0000", "Z"));
        assertEquals(Duration.ofDays(30), Duration.between(start, end));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 查询失败只记录可检索的安全字段，拒绝将任意响应或错误码链接写入日志。 */
    @ParameterizedTest
    @CsvSource({"InvalidArgument,InvalidArgument", "https://private.invalid?signature=secret,UNKNOWN"})
    void cleanupQueryFailureLogsSafeStageStatusAndCodeWithoutRemoteMessage(String providerCode, String safeCode) {
        var unavailable = new CosServiceException("startCreationTime is illegal https://private.invalid?signature=secret");
        unavailable.setStatusCode(400); unavailable.setErrorCode(providerCode);
        when(cos.describeMediaJobs(any())).thenThrow(unavailable);

        var failure = assertThrows(VideoFrameProcessingException.class,
                () -> client.cleanupCompletedOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class)));

        assertEquals("FRAME_CLEANUP", failure.getStage());
        assertNull(failure.getCause());
        assertTrue(events.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.contains("stage=FRAME_CLEANUP_QUERY") && message.contains("httpStatus=400")
                        && message.contains("errorCode=" + safeCode) && message.contains("exceptionType=CosServiceException")));
        assertTrue(events.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        assertTrue(events.list.stream().map(ILoggingEvent::getFormattedMessage).noneMatch(message ->
                message.contains("https://") || message.contains("secret") || message.contains("startCreationTime is illegal")));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 区分已完成查询后的输出清理错误，仍保留原有失败与不删除行为。 */
    @Test
    void cleanupOutputFailureIsDistinguishedFromProviderQueryFailure() {
        MediaListJobResponse page = new MediaListJobResponse(); page.setJobsDetailList(List.of(terminalJob()));
        when(cos.describeMediaJobs(any())).thenReturn(page);
        var unavailable = new CosServiceException("https://private.invalid?signature=secret");
        unavailable.setStatusCode(503); unavailable.setErrorCode("ServiceUnavailable");
        when(cos.listObjects(any(ListObjectsRequest.class))).thenThrow(unavailable);

        assertThrows(VideoFrameProcessingException.class,
                () -> client.cleanupCompletedOutputs(mock(VideoFrameBackfillLock.ExecutionLock.class)));

        assertTrue(events.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.contains("stage=FRAME_CLEANUP_OUTPUT") && message.contains("httpStatus=503")
                        && message.contains("errorCode=ServiceUnavailable")));
        assertTrue(events.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 截断原始对象列表时保留整页，不能删除部分后把剩余对象失去追踪。 */
    @Test
    void terminalCleanupNeverDeletesTruncatedOutputOrContinuesAfterLockLoss() {
        MediaListJobResponse page = new MediaListJobResponse(); page.setJobsDetailList(List.of(terminalJob()));
        when(cos.describeMediaJobs(any())).thenReturn(page);
        ObjectListing listing = new ObjectListing(); listing.setTruncated(true);
        when(cos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        var lock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        assertThrows(VideoFrameProcessingException.class, () -> client.cleanupCompletedOutputs(lock));
        verify(cos, never()).deleteObject(anyString(), anyString());
        listing.setTruncated(false);
        COSObjectSummary frame = new COSObjectSummary(); frame.setKey(historyPrefix() + "raw-1-1000.jpg");
        listing.getObjectSummaries().add(frame);
        doNothing().doNothing().doThrow(new IllegalStateException("lock lost")).when(lock).checkHeld();
        assertThrows(VideoFrameProcessingException.class, () -> client.cleanupCompletedOutputs(lock));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 旧列表生成后供应商重新写入时，HEAD 的新时间保护新对象。 */
    @Test
    void ageFallbackRechecksCloudModificationTimeAndMovesBoundedObjectCursor() {
        String key = historyPrefix() + "raw-1-1000.jpg";
        COSObjectSummary frame = new COSObjectSummary(); frame.setKey(key);
        frame.setLastModified(Date.from(Instant.now().minus(Duration.ofDays(2))));
        ObjectListing first = new ObjectListing(); first.setTruncated(true); first.getObjectSummaries().add(frame);
        ObjectMetadata current = new ObjectMetadata(); current.setLastModified(new Date());
        when(cos.getObjectMetadata("bucket-1", key)).thenReturn(current);
        when(cos.listObjects(any(ListObjectsRequest.class))).thenReturn(first, new ObjectListing());
        var lock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        client.cleanupExpiredOutputs(lock);
        client.cleanupExpiredOutputs(lock);
        var requests = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(cos, times(2)).listObjects(requests.capture());
        assertNull(requests.getAllValues().getFirst().getMarker());
        assertEquals(key, requests.getAllValues().getLast().getMarker());
        assertEquals(1000, requests.getAllValues().getFirst().getMaxKeys());
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 精确历史目录包含服务端随机任务身份。 */
    private String historyPrefix() {
        return "OWNER/others/video-frames/1/" + source.sourceVersion() + "/v1/history-00000000-0000-0000-0000-000000000001/raw/";
    }

    /** 保留供应商输入与输出，供扫尾复核所有权。 */
    private MediaJobObject terminalJob() {
        var request = client.buildRequest(source, 8, historyPrefix(), "queue-1");
        MediaJobObject job = new MediaJobObject();
        job.setJobId("terminal-1"); job.setState("Success"); job.setTag("Snapshot");
        job.setInput(request.getInput()); job.setOperation(request.getOperation());
        return job;
    }
    /** 构造不包含回调地址的队列响应，覆盖厂商分页边界。 */
    private MediaListQueueResponse queuePage(int page, int total, List<MediaQueueObject> queues) {
        MediaListQueueResponse response = new MediaListQueueResponse();
        response.setPageNumber(Integer.toString(page));
        response.setPageSize("10");
        response.setTotalCount(Integer.toString(total));
        response.setQueueList(queues);
        return response;
    }
    /** 普通可用状态必须依据队列 API 的官方枚举。 */
    private MediaQueueObject queue(String id, String state, String category) {
        MediaQueueObject queue = new MediaQueueObject();
        queue.setQueueId(id);
        queue.setState(state);
        queue.setCategory(category);
        queue.setBucketId("bucket-1");
        return queue;
    }
}
