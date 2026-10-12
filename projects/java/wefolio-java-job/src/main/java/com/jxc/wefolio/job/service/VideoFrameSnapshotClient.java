package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameConfig;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.repo.VideoFrameOrphanRepository;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ciModel.common.MediaInputObject;
import com.qcloud.cos.model.ciModel.common.MediaOutputObject;
import com.qcloud.cos.model.ciModel.job.MediaJobsRequest;
import com.qcloud.cos.model.ciModel.job.MediaJobOperation;
import com.qcloud.cos.model.ciModel.job.MediaJobResponse;
import com.qcloud.cos.model.ciModel.job.MediaJobObject;
import com.qcloud.cos.model.ciModel.job.MediaListJobResponse;
import com.qcloud.cos.model.ciModel.queue.MediaListQueueResponse;
import com.qcloud.cos.model.ciModel.queue.MediaQueueObject;
import com.qcloud.cos.model.ciModel.queue.MediaQueueRequest;
import com.qcloud.cos.model.ciModel.template.MediaSnapshotObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** 历史视频数据万象 Snapshot 适配，仅提交与查询原 job，不读原视频。 */
@Service
@Slf4j
public class VideoFrameSnapshotClient {
    /** SDK 请求的固定媒体语义。 */
    private static final String SNAPSHOT_TAG = "Snapshot";
    private static final String AVERAGE_MODE = "Average";
    private static final String SNAPSHOT_OUT_MODE = "OnlySnapshot";
    private static final String OUTPUT_TEMPLATE = "raw-${number}-${time}.jpg";
    private static final String SUCCESS_STATE = "Success";
    private static final String FAILED_STATE = "Failed";
    private static final String CANCEL_STATE = "Cancel";
    private static final String SUBMITTED_STATE = "Submitted";
    private static final String RUNNING_STATE = "Running";
    private static final Pattern OUTPUT_NAME = Pattern.compile("raw-(\\d+)-(\\d+)\\.jpg");
    /** 仅补偿本服务随机历史任务目录，不触及审核帧或已规范化持久帧。 */
    private static final Pattern HISTORY_OUTPUT = Pattern.compile("([A-Za-z0-9_-]+)/others/video-frames/[1-9][0-9]*/"
            + "[a-f0-9]{64}/[A-Za-z0-9_-]{1,96}/history-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/raw/");
    private static final Pattern SOURCE_KEY = Pattern.compile("([A-Za-z0-9_-]+)/work/video/[^/]+");
    /** 规范帧只接收本服务数值审核任务或随机历史任务目录，禁止泛化任意 audit-*。 */
    private static final Pattern PERSISTENT_FRAME = Pattern.compile("([A-Za-z0-9_-]+)/others/video-frames/([1-9][0-9]*)/"
            + "([a-f0-9]{64})/([A-Za-z0-9_-]{1,96})/(audit-([1-9][0-9]*)|history-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})/([0-9]{4})\\.jpg");
    /** 每轮只读一页，远端步骤受短期限约束；游标跨轮推进，不占用调度线程长时间扫描。 */
    private static final int CLEANUP_PAGE_SIZE = 20;
    private static final int CLEANUP_SECONDS = 30;
    private static final int CLEANUP_LOOKBACK_DAYS = 30;
    private static final int EXPIRED_OUTPUT_PAGE_SIZE = 1000;
    private static final Duration RAW_RETENTION = Duration.ofHours(24);
    private static final String TERMINAL_STATES = "Success,Failed,Cancel";
    private static final String ASCENDING_TIME = "Asc";
    private static final String FRAME_CLEANUP = "FRAME_CLEANUP";
    /** 普通媒体队列的官方状态与类型，不使用倍速转码队列。 */
    private static final String QUEUE_ACTIVE_STATE = "Active";
    private static final String QUEUE_CATEGORY = "Transcoding";
    private static final String QUEUE_STAGE = "FRAME_QUEUE";
    /** 有界完整查询，超过上限时要求运维显式选择真实队列。 */
    private static final int QUEUE_PAGE_SIZE = 10;
    private static final int QUEUE_MAX_PAGES = 20;
    /** 分页值只接受有界十进制整数。 */
    private static final Pattern QUEUE_PAGE_VALUE = Pattern.compile("[0-9]{1,9}");
    /** 无隐式重试的媒体处理客户端。 */
    private final COSClient client;
    /** 当前私有存储桶及地域。 */
    private final CosProperties cos;
    /** 下载边界及历史处理预算配置。 */
    private final VideoFrameProperties properties;
    /** 跨重启回收规范帧前核实终态任务与作品引用。 */
    private final VideoFrameOrphanRepository orphans;
    /** 分页范围冻结至本轮扫描水位；重启后重新扫描，删除同一原始对象可安全重试。 */
    private String cleanupNextToken;
    private Instant cleanupWatermark;
    /** 年龄兜底按桶内对象游标分页，不读取作品或原视频。 */
    private String expiredOutputMarker;

    /** 生产显式注入回收保护，任何数据库错误都保留对象等待下轮。 */
    @Autowired
    public VideoFrameSnapshotClient(@Qualifier(VideoFrameConfig.COS_CLIENT_NAME) COSClient client,
            CosProperties cos, VideoFrameProperties properties, VideoFrameOrphanRepository orphans) {
        this.client = client; this.cos = cos; this.properties = properties; this.orphans = orphans;
    }
    /** 兼容原始截图客户端测试；未提供数据库保护时不回收规范帧。 */
    public VideoFrameSnapshotClient(COSClient client, CosProperties cos, VideoFrameProperties properties) {
        this(client, cos, properties, null);
    }
    /** 每批执行前解析一次队列；显式配置优先，自动模式只选择完整查询中的唯一可用队列。 */
    public String resolveQueueId() {
        String configured = properties.getCiQueueId();
        if (configured != null && !configured.isBlank()
                && !VideoFrameProperties.AUTO_QUEUE_ID.equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }
        try {
            Integer totalCount = null;
            Set<String> seenIds = new HashSet<>();
            List<String> availableIds = new ArrayList<>();
            for (int page = 1; page <= QUEUE_MAX_PAGES; page++) {
                MediaQueueRequest request = new MediaQueueRequest();
                request.setBucketName(cos.getBucketName());
                request.setState(QUEUE_ACTIVE_STATE);
                request.setCategory(QUEUE_CATEGORY);
                request.setPageNumber(Integer.toString(page));
                request.setPageSize(Integer.toString(QUEUE_PAGE_SIZE));
                log.info("历史截图队列查询: bucket={}, state={}, category={}, pageNumber={}, pageSize={}",
                        cos.getBucketName(), QUEUE_ACTIVE_STATE, QUEUE_CATEGORY, page, QUEUE_PAGE_SIZE);
                MediaListQueueResponse response = client.describeMediaQueues(request);
                if (response == null || response.getQueueList() == null) {
                    throw failure(QUEUE_STAGE, "供应商队列查询结果缺失");
                }
                int responseTotal = pageValue(response.getTotalCount());
                if (pageValue(response.getPageNumber()) != page
                        || pageValue(response.getPageSize()) != QUEUE_PAGE_SIZE
                        || responseTotal > QUEUE_PAGE_SIZE * QUEUE_MAX_PAGES
                        || (totalCount != null && totalCount != responseTotal)) {
                    throw failure(QUEUE_STAGE, "供应商队列分页不完整或超过上限，请显式配置真实队列ID");
                }
                totalCount = responseTotal;
                int expectedSize = Math.min(QUEUE_PAGE_SIZE, responseTotal - (page - 1) * QUEUE_PAGE_SIZE);
                if (response.getQueueList().size() != expectedSize) {
                    throw failure(QUEUE_STAGE, "供应商队列分页数量不完整");
                }
                for (MediaQueueObject queue : response.getQueueList()) {
                    if (queue == null || queue.getQueueId() == null || queue.getQueueId().isBlank()
                            || VideoFrameProperties.AUTO_QUEUE_ID.equalsIgnoreCase(queue.getQueueId().trim())
                            || !seenIds.add(queue.getQueueId().trim())) {
                        throw failure(QUEUE_STAGE, "供应商队列身份缺失或分页重复");
                    }
                    if (queue.getBucketId() != null && !queue.getBucketId().isBlank()
                            && !cos.getBucketName().equals(queue.getBucketId())) {
                        throw failure(QUEUE_STAGE, "供应商队列所属存储桶不一致");
                    }
                    if (QUEUE_ACTIVE_STATE.equals(queue.getState()) && QUEUE_CATEGORY.equals(queue.getCategory())) {
                        availableIds.add(queue.getQueueId().trim());
                    }
                }
                log.info("历史截图队列查询结果: bucket={}, pageNumber={}, pageSize={}, totalCount={}, pageCount={}, availableCount={}",
                        cos.getBucketName(), page, QUEUE_PAGE_SIZE, responseTotal,
                        response.getQueueList().size(), availableIds.size());
                if (seenIds.size() == responseTotal) {
                    if (availableIds.size() != 1) {
                        throw failure(QUEUE_STAGE, availableIds.isEmpty() ? "没有可用的普通媒体处理队列，请开通媒体处理或配置真实队列ID"
                                : "存在多个可用媒体处理队列，请显式配置真实队列ID");
                    }
                    String queueId = availableIds.getFirst();
                    log.info("历史截图自动选择队列: bucket={}, queueId={}", cos.getBucketName(), queueId);
                    return queueId;
                }
            }
            throw failure(QUEUE_STAGE, "供应商队列查询超过分页上限，请显式配置真实队列ID");
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure(QUEUE_STAGE, "媒体处理队列查询失败，请检查数据万象开通状态及队列查询权限"); }
    }

    /** 分页字符串缺失、溢出或非法时中止，不用不完整响应选择队列。 */
    private int pageValue(String value) {
        if (value == null || !QUEUE_PAGE_VALUE.matcher(value).matches()) {
            throw failure(QUEUE_STAGE, "供应商队列分页参数非法");
        }
        return Integer.parseInt(value);
    }

    /** 首次提交；调用前计入批次截图预算，提交异常不能自动重发。 */
    public String submit(VideoFrameSource source, int count, String outputPrefix, String queueId) {
        MediaJobsRequest request = buildRequest(source, count, outputPrefix, queueId);
        log.info("历史视频 Snapshot 提交: sourceVersion={}, objectKey={}, mode={}, count={}, outputPrefix={}, queueId={}",
                source.sourceVersion(), source.objectKey(), AVERAGE_MODE, count, outputPrefix, request.getQueueId());
        try {
            MediaJobResponse response = client.createMediaJobs(request);
            MediaJobObject result = response == null ? null : response.getJobsDetail();
            if (result == null || result.getJobId() == null || result.getJobId().isBlank()) {
                throw failure("FRAME_SUBMIT", "供应商未返回可持久化 JobId，结果未知");
            }
            log.info("历史 Snapshot 提交结果: jobId={}, state={}, code={}", result.getJobId(), result.getState(), result.getCode());
            return result.getJobId();
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure("FRAME_SUBMIT", "截图提交响应未知，禁止盲目重复提交"); }
    }
    /** 构建 Average 请求，不携带 TimeInterval，返回毫秒时间通配符。 */
    public MediaJobsRequest buildRequest(VideoFrameSource source, int count, String outputPrefix, String queueId) {
        if (count < VideoFramePolicy.MIN_COUNT || count > VideoFramePolicy.MAX_COUNT || outputPrefix == null
                || !outputPrefix.contains(VideoFrameStorageService.FRAME_DIRECTORY) || outputPrefix.contains("..")) {
            throw failure("FRAME_SUBMIT", "历史截图冻结参数非法");
        }
        if (queueId == null || queueId.isBlank()
                || VideoFrameProperties.AUTO_QUEUE_ID.equalsIgnoreCase(queueId.trim())) {
            throw failure(QUEUE_STAGE, "历史截图提交缺少已解析的真实队列ID");
        }
        MediaJobsRequest request = new MediaJobsRequest();
        request.setBucketName(cos.getBucketName());
        request.setQueueId(queueId.trim());
        request.setTag(SNAPSHOT_TAG);
        MediaInputObject input = new MediaInputObject(); input.setObject(source.objectKey()); request.setInput(input);
        MediaSnapshotObject snapshot = new MediaSnapshotObject();
        snapshot.setMode(AVERAGE_MODE); snapshot.setCount(Integer.toString(count));
        snapshot.setSnapshotOutMode(SNAPSHOT_OUT_MODE);
        MediaOutputObject output = new MediaOutputObject();
        output.setBucket(cos.getBucketName()); output.setRegion(cos.getRegion()); output.setObject(outputPrefix + OUTPUT_TEMPLATE);
        MediaJobOperation operation = new MediaJobOperation(); operation.setSnapshot(snapshot); operation.setOutput(output);
        request.setOperation(operation);
        return request;
    }
    /** 查询已知 job，正常处理中的状态不算错误也不重提。 */
    public boolean ready(String jobId) {
        MediaJobsRequest request = new MediaJobsRequest(); request.setBucketName(cos.getBucketName()); request.setJobId(jobId);
        try {
            MediaJobResponse response = client.describeMediaJob(request);
            MediaJobObject detail = response == null ? null : response.getJobsDetail();
            if (detail == null || !jobId.equals(detail.getJobId())) { throw failure("FRAME_QUERY", "供应商截图查询身份不一致"); }
            String state = detail.getState();
            log.info("历史 Snapshot 查询结果: jobId={}, state={}, code={}", jobId, state, detail.getCode());
            if (SUCCESS_STATE.equals(state)) { return true; }
            if (SUBMITTED_STATE.equals(state) || RUNNING_STATE.equals(state)) { return false; }
            if (FAILED_STATE.equals(state) || CANCEL_STATE.equals(state)) { throw new VideoFrameSnapshotTerminalException("供应商截图任务已明确失败或取消，错误码=" + safeCode(detail.getCode())); }
            throw failure("FRAME_QUERY", "供应商截图状态未知");
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure("FRAME_QUERY", "供应商截图查询异常"); }
    }
    /** 从服务器输出文件名读真实毫秒时间，拒绝数目或时间不完整。 */
    public List<VideoFrameInput> outputFrames(String prefix, int count) {
        try {
            ObjectListing listing = client.listObjects(new ListObjectsRequest(cos.getBucketName(), prefix, null, null, count + 1));
            if (listing == null || listing.isTruncated() || listing.getObjectSummaries().size() != count) {
                throw failure("FRAME_LIST", "历史截图输出数量不完整");
            }
            List<VideoFrameInput> frames = new ArrayList<>();
            for (COSObjectSummary object : listing.getObjectSummaries()) {
                String key = object.getKey();
                if (key == null || !key.startsWith(prefix)) { throw failure("FRAME_LIST", "截图输出目录不一致"); }
                Matcher matcher = OUTPUT_NAME.matcher(key.substring(prefix.length()));
                if (!matcher.matches()) { throw failure("FRAME_LIST", "截图输出缺失真实时间通配符"); }
                frames.add(new VideoFrameInput(Long.parseLong(matcher.group(2)), null, key));
            }
            return frames;
        } catch (VideoFrameProcessingException exception) { throw exception; }
        catch (Exception exception) { throw failure("FRAME_LIST", "历史截图输出读取失败"); }
    }

    /**
     * 定时补偿丢失 JobId 的提交及查询中断输出，只消费供应商明确终态的历史任务。
     * 补帧和扫尾持有同一会话锁，禁止扫尾删除正在由本批次读取的已完成原始截图。
     * 供应商历史过期的对象另由二十四小时年龄兜底回收，不推断供应商任务终态。
     */
    public synchronized void cleanupCompletedOutputs(VideoFrameBackfillLock.ExecutionLock lock) {
        Instant deadline = cleanupDeadline();
        if (cleanupWatermark == null) { cleanupWatermark = Instant.now().truncatedTo(ChronoUnit.SECONDS); }
        lock.checkHeld();
        MediaJobsRequest request = new MediaJobsRequest();
        request.setBucketName(cos.getBucketName());
        request.setTag(SNAPSHOT_TAG);
        request.setStates(TERMINAL_STATES);
        request.setOrderByTime(ASCENDING_TIME);
        request.setSize(CLEANUP_PAGE_SIZE);
        request.setNextToken(cleanupNextToken);
        request.setStartCreationTime(cleanupWatermark.minus(Duration.ofDays(CLEANUP_LOOKBACK_DAYS)).toString());
        request.setEndCreationTime(cleanupWatermark.toString());
        try {
            MediaListJobResponse page = client.describeMediaJobs(request);
            if (page == null || page.getJobsDetailList() == null || page.getJobsDetailList().size() > CLEANUP_PAGE_SIZE
                    || (cleanupNextToken != null && cleanupNextToken.equals(page.getNextToken()))) {
                throw failure(FRAME_CLEANUP, "历史截图终态分页响应缺失、重复或超限");
            }
            for (MediaJobObject job : page.getJobsDetailList()) {
                if (!hasCallTime(deadline)) { return; }
                String prefix = ownedTerminalOutput(job);
                if (prefix == null) { continue; }
                int count = Integer.parseInt(job.getOperation().getSnapshot().getCount());
                if (!cleanupOutput(prefix, count, lock, deadline)) { return; }
                log.info("历史 Snapshot 终态补偿清理: jobId={}, state={}, prefix={}", job.getJobId(), job.getState(), prefix);
            }
            cleanupNextToken = page.getNextToken();
            if (cleanupNextToken == null || cleanupNextToken.isBlank()) {
                cleanupNextToken = null; cleanupWatermark = null;
            }
        } catch (Exception exception) {
            // 失败回到安全的首屏；下一轮不继续消费供应商可能已经失效的游标。
            cleanupNextToken = null; cleanupWatermark = null;
            throw failure(FRAME_CLEANUP, "历史截图终态扫尾失败，保留输出等待下轮重试");
        }
    }

    /**
     * 供应商任务历史过期和进程硬中断后的兜底：回收已超过二十四小时的原始截图及无引用规范帧。
     * 单件补帧期限硬上限为三百秒，且此处持有同一执行锁，因此这些对象已无合法读取者。
     * 极晚写入按云端新修改时间继续保留；审核帧先确认任务终态，全部规范帧核实当前清单。
     */
    public synchronized void cleanupExpiredOutputs(VideoFrameBackfillLock.ExecutionLock lock) {
        Instant deadline = cleanupDeadline();
        Instant cutoff = Instant.now().minus(RAW_RETENTION);
        lock.checkHeld();
        ObjectListing page = client.listObjects(new ListObjectsRequest(cos.getBucketName(), null,
                expiredOutputMarker, null, EXPIRED_OUTPUT_PAGE_SIZE));
        if (page == null || page.getObjectSummaries().size() > EXPIRED_OUTPUT_PAGE_SIZE
                || (page.isTruncated() && page.getObjectSummaries().isEmpty())) {
            throw failure(FRAME_CLEANUP, "过期历史截图扫描分页缺失、截断为空或超限");
        }
        for (COSObjectSummary object : page.getObjectSummaries()) {
            if (!hasCallTime(deadline)) { return; }
            if (object == null || object.getKey() == null || object.getKey().equals(expiredOutputMarker)) {
                throw failure(FRAME_CLEANUP, "过期历史截图扫描游标未推进");
            }
            String key = object.getKey();
            boolean raw = isExpiredRaw(key, object.getLastModified(), cutoff);
            boolean persistent = isExpiredPersistent(key, object.getLastModified(), cutoff);
            if (raw || persistent) {
                lock.checkHeld();
                try {
                    // 先读终态和数据库清单再删规范帧；活任务、未知身份或数据库不可读均保留。
                    if (persistent && !canDeletePersistent(key)) {
                        expiredOutputMarker = key;
                        continue;
                    }
                    // 删除前再次 HEAD，避免列表生成后供应商极晚写入刷新了对象。
                    ObjectMetadata current = client.getObjectMetadata(cos.getBucketName(), key);
                    if (current != null && (raw ? isExpiredRaw(key, current.getLastModified(), cutoff)
                            : isExpiredPersistent(key, current.getLastModified(), cutoff))) {
                        if (!hasCallTime(deadline)) { return; }
                        lock.checkHeld();
                        client.deleteObject(cos.getBucketName(), key);
                        log.info("视频帧过期无引用对象回收: objectKey={}", key);
                    }
                } catch (CosServiceException exception) {
                    if (exception.getStatusCode() != 404) { throw failure(FRAME_CLEANUP, "过期历史截图复核或清理失败"); }
                }
            }
            expiredOutputMarker = key;
        }
        if (!page.isTruncated()) { expiredOutputMarker = null; }
    }

    /** 云端修改时间与严格原始历史目录共同证明可回收对象，未知时间保留。 */
    private boolean isExpiredRaw(String key, Date modified, Instant cutoff) {
        if (modified == null || !modified.toInstant().isBefore(cutoff)) { return false; }
        int separator = key.lastIndexOf('/');
        return separator >= 0 && HISTORY_OUTPUT.matcher(key.substring(0, separator + 1)).matches()
                && OUTPUT_NAME.matcher(key.substring(separator + 1)).matches();
    }

    /** 严格规范帧编号与云端年龄共同筛选候选，零编号和协议上限外对象保留。 */
    private boolean isExpiredPersistent(String key, Date modified, Instant cutoff) {
        if (orphans == null || modified == null || !modified.toInstant().isBefore(cutoff)) { return false; }
        Matcher match = PERSISTENT_FRAME.matcher(key);
        if (!match.matches()) { return false; }
        int number = Integer.parseInt(match.group(7));
        return number >= 1 && number <= VideoFramePolicy.MAX_COUNT;
    }

    /** 从服务端命名路径恢复任务身份，当前规范帧清单只要引用任一同前缀对象就整目录保留。 */
    private boolean canDeletePersistent(String key) {
        Matcher match = PERSISTENT_FRAME.matcher(key);
        if (!match.matches()) { return false; }
        try {
            long workId = Long.parseLong(match.group(2));
            String prefix = key.substring(0, key.lastIndexOf('/') + 1);
            if (match.group(6) == null) { return orphans.isUnreferenced(workId, prefix); }
            return orphans.canDeleteAuditFrame(Long.parseLong(match.group(6)), workId,
                    match.group(3), match.group(4), match.group(1), prefix);
        } catch (NumberFormatException exception) { return false; }
    }

    /** 默认每阶段三十秒，高超时配置也为列表、复核和删除各预留一次完整调用。 */
    private Instant cleanupDeadline() {
        return Instant.now().plusSeconds(Math.max(CLEANUP_SECONDS, properties.getRemoteTimeoutSeconds() * 3L + 1));
    }

    /** 任务原始输入、输出桶、路径及终态必须同时可核实，不能相信仅有文件名前缀。 */
    private String ownedTerminalOutput(MediaJobObject job) {
        if (job == null || !SNAPSHOT_TAG.equals(job.getTag()) || job.getJobId() == null || job.getJobId().isBlank()
                || !(SUCCESS_STATE.equals(job.getState()) || FAILED_STATE.equals(job.getState()) || CANCEL_STATE.equals(job.getState()))
                || job.getInput() == null || job.getOperation() == null || job.getOperation().getOutput() == null
                || job.getOperation().getSnapshot() == null) { return null; }
        String source = job.getInput().getObject();
        if (source == null || source.contains("..")) { return null; }
        Matcher owner = SOURCE_KEY.matcher(source);
        MediaOutputObject output = job.getOperation().getOutput();
        String object = output.getObject();
        if (!owner.matches() || !Objects.equals(cos.getBucketName(), output.getBucket())
                || !Objects.equals(cos.getRegion(), output.getRegion()) || object == null || !object.endsWith(OUTPUT_TEMPLATE)) { return null; }
        String prefix = object.substring(0, object.length() - OUTPUT_TEMPLATE.length());
        Matcher target = HISTORY_OUTPUT.matcher(prefix);
        String count = job.getOperation().getSnapshot().getCount();
        if (!target.matches() || !owner.group(1).equals(target.group(1)) || count == null || !count.matches("[0-9]{1,2}")) { return null; }
        int parsed = Integer.parseInt(count);
        return parsed >= VideoFramePolicy.MIN_COUNT && parsed <= VideoFramePolicy.MAX_COUNT ? prefix : null;
    }

    /** 只清理当前输出前缀内的临时图片，成功持久帧位于另一个目录。 */
    public void cleanupOutput(String prefix, int count) {
        try {
            cleanupOutput(prefix, count, null, Instant.now().plusSeconds(CLEANUP_SECONDS));
        } catch (Exception exception) { log.warn("历史 Snapshot 临时输出清理失败: prefix={}", prefix); }
    }
    /** 先验证整页对象均为本任务直接原始图片；逐次远端调用检查总期限和跨实例执行锁。 */
    private boolean cleanupOutput(String prefix, int count, VideoFrameBackfillLock.ExecutionLock lock, Instant deadline) {
        if (!hasCallTime(deadline)) { return false; }
        if (lock != null) { lock.checkHeld(); }
        ObjectListing listing = client.listObjects(new ListObjectsRequest(cos.getBucketName(), prefix, null, null, count + 1));
        if (listing == null || listing.isTruncated() || listing.getObjectSummaries().size() > count) {
            throw failure(FRAME_CLEANUP, "历史截图输出清理列表缺失、截断或超限");
        }
        for (COSObjectSummary object : listing.getObjectSummaries()) {
            if (object == null || object.getKey() == null || !object.getKey().startsWith(prefix)
                    || !OUTPUT_NAME.matcher(object.getKey().substring(prefix.length())).matches()) {
                throw failure(FRAME_CLEANUP, "历史截图清理目录包含非原始输出对象");
            }
        }
        for (COSObjectSummary object : listing.getObjectSummaries()) {
            if (!hasCallTime(deadline)) { return false; }
            if (lock != null) { lock.checkHeld(); }
            client.deleteObject(cos.getBucketName(), object.getKey());
        }
        return true;
    }
    /** 为每次远端请求预留完整单次超时，下一轮重新读取尚未删除的剩余对象。 */
    private boolean hasCallTime(Instant deadline) {
        return !Thread.currentThread().isInterrupted()
                && Instant.now().plusSeconds(properties.getRemoteTimeoutSeconds()).isBefore(deadline);
    }
    /** 错误码只能包含安全短字符，不输出原供应商 Message。 */
    private String safeCode(String code) { return code != null && code.matches("[A-Za-z0-9_.-]{1,80}") ? code : "UNKNOWN"; }
    /** 创建不包含远端链接的失败原因。 */
    private VideoFrameProcessingException failure(String stage, String reason) { return new VideoFrameProcessingException(stage, reason); }
}
