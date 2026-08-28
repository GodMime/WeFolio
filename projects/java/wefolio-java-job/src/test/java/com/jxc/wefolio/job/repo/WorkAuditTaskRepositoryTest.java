package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.dict.WorkAuditTaskStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.mapper.WorkAuditTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 作品审核任务仓储测试。
 */
class WorkAuditTaskRepositoryTest {

    /**
     * 初始化 MyBatis-Plus Lambda 字段缓存，便于直接检查 wrapper 条件。
     */
    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(WorkAuditTaskEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                    WorkAuditTaskEntity.class);
        }
    }

    /**
     * 成功终态只允许从当前执行中的任务状态进入，避免覆盖其他终态。
     */
    @Test
    void markSuccessShouldGuardCurrentTaskStatus() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markSuccess(101L, AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains("task_status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WorkAuditTaskStatusDict.SUBMITTING.getCode(), WorkAuditTaskStatusDict.QUERYING.getCode());
    }

    /**
     * 失败终态只允许从当前执行中的任务状态进入，避免错误路径覆盖成功结果。
     */
    @Test
    void markFailedShouldGuardCurrentTaskStatus() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markFailed(101L, "远端调用失败", "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains("task_status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WorkAuditTaskStatusDict.SUBMITTING.getCode(), WorkAuditTaskStatusDict.QUERYING.getCode());
    }

    /** 视频结果候选计数必须关联同轮次且非人工的审核中作品。 */
    @Test
    void countQueryableVideoTasksShouldMatchCandidatePredicate() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.countQueryableVideoTasks(120, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectCountWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "query_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MediaTypeDict.VIDEO.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                        WorkAuditTaskStatusDict.RUNNING.getCode(),
                        WorkAuditTaskStatusDict.QUERYING.getCode(),
                        now,
                        120,
                        WorkAuditStatusDict.AUDITING.getCode(),
                        0L);
    }

    /** 可恢复图片任务必须处于过期提交态，并关联同轮次非人工审核作品。 */
    @Test
    void findRetryableExpiredImageTasksShouldRequireActiveMatchingAutomaticWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 12, 0);

        repository.findRetryableExpiredImageTasks(20, 3, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                MediaTypeDict.IMAGE.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 达到上限的图片任务查询必须复用自动审核硬隔离条件。 */
    @Test
    void findExhaustedExpiredImageTasksShouldRequireActiveMatchingAutomaticWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 12, 0);

        repository.findExhaustedExpiredImageTasks(20, 3, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                MediaTypeDict.IMAGE.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 图片任务恢复领取必须刷新租约、递增尝试次数并复用自动审核硬隔离条件。 */
    @Test
    void claimExpiredImageTaskShouldIncrementAttemptAndRequireActiveMatchingAutomaticWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 12, 0);

        repository.claimExpiredImageTask(101L, "image-token", now, now.plusMinutes(5), 3);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet()).contains(
                "locked_by", "locked_until", "started_at", "last_error_message", "updated_at",
                "attempt_count = attempt_count + 1", "version = version + 1");
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                101L, MediaTypeDict.IMAGE.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 图片终态回收领取不得增加已经达到上限的尝试次数。 */
    @Test
    void claimExhaustedExpiredImageTaskShouldNotIncrementAttemptCount() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 12, 0);

        repository.claimExhaustedExpiredImageTask(101L, "terminal-token", now, now.plusMinutes(5), 3);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet())
                .contains("locked_by", "locked_until", "updated_at", "version = version + 1")
                .doesNotContain("attempt_count = attempt_count + 1");
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
    }

    /** 图片结果写回必须同时校验领取 token 与同轮自动审核作品。 */
    @Test
    void imageTerminalUpdatesShouldRequireClaimTokenAndActiveMatchingAutomaticWork() {
        WorkAuditTaskMapper successMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository successRepository = new WorkAuditTaskRepository(successMapper);

        successRepository.markImageSuccess(
                101L, "image-token", AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> successWrapper = captureUpdateWrapper(successMapper);
        assertThat(successWrapper.getSqlSegment()).contains(
                "id", "media_type", "task_status", "locked_by", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
        assertThat(successWrapper.getParamNameValuePairs().values()).contains(
                101L, MediaTypeDict.IMAGE.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                "image-token", WorkAuditStatusDict.AUDITING.getCode(), 0L);

        WorkAuditTaskMapper failureMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository failureRepository = new WorkAuditTaskRepository(failureMapper);
        failureRepository.markImageFailed(101L, "image-token", "远端调用失败", "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> failureWrapper = captureUpdateWrapper(failureMapper);
        assertThat(failureWrapper.getSqlSegment()).contains(
                "id", "media_type", "task_status", "locked_by", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
    }

    /** 视频结果候选查询必须关联同轮次且非人工的审核中作品。 */
    @Test
    void findQueryableVideoTasksShouldIncludeExpiredQueryingAndRequireActiveMatchingWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.findQueryableVideoTasks(20, 120, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "query_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                WorkAuditTaskStatusDict.SUBMITTED.getCode(), WorkAuditTaskStatusDict.RUNNING.getCode(),
                WorkAuditTaskStatusDict.QUERYING.getCode(), now, 120,
                WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 过期视频提交领取必须关联同轮次且非人工的审核中作品。 */
    @Test
    void claimExpiredVideoSubmitShouldIncrementAttemptAndRequireActiveMatchingWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        LocalDateTime lockedUntil = now.plusMinutes(5);

        repository.claimExpiredVideoSubmit(101L, "test-token", now, lockedUntil, 3);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet()).contains(
                "locked_by", "locked_until", "started_at", "last_error_message",
                "attempt_count = attempt_count + 1", "updated_at", "version = version + 1");
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count",
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                101L, "VIDEO", "SUBMITTING", now, 3, "AUDITING", 0L);
    }

    /** 达到上限的视频提交领取必须关联非人工作品且不再递增尝试次数。 */
    @Test
    void claimExhaustedExpiredVideoSubmitShouldNotIncrementAttemptCount() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.claimExhaustedExpiredVideoSubmit(
                101L, "terminal-token", now, now.plusMinutes(5), 3);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet())
                .contains("locked_by", "locked_until", "updated_at", "version = version + 1")
                .doesNotContain("attempt_count = attempt_count + 1");
        assertThat(wrapper.getSqlSegment()).contains(
                "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "attempt_count");
    }

    /** 视频提交恢复的重试与终态候选都必须排除人工审核作品。 */
    @Test
    void submissionRecoveryCandidatesShouldSplitRetryableAndExhaustedTasks() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        WorkAuditTaskMapper retryableMapper = mock(WorkAuditTaskMapper.class);
        new WorkAuditTaskRepository(retryableMapper)
                .findRetryableExpiredVideoSubmitTasks(20, 3, now);
        LambdaQueryWrapper<WorkAuditTaskEntity> retryable = captureSelectListWrapper(retryableMapper);
        assertThat(retryable.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "EXISTS",
                "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(retryable.getParamNameValuePairs().values()).contains(
                MediaTypeDict.VIDEO.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);

        WorkAuditTaskMapper exhaustedMapper = mock(WorkAuditTaskMapper.class);
        new WorkAuditTaskRepository(exhaustedMapper)
                .findExhaustedExpiredVideoSubmitTasks(20, 3, now);
        LambdaQueryWrapper<WorkAuditTaskEntity> exhausted = captureSelectListWrapper(exhaustedMapper);
        assertThat(exhausted.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "EXISTS",
                "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(exhausted.getParamNameValuePairs().values()).contains(
                MediaTypeDict.VIDEO.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 达到查询上限的视频候选必须排除人工审核作品。 */
    @Test
    void exhaustedVideoQueryCandidatesShouldRequireAutomaticAuditingWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.findExhaustedExpiredVideoQueryTasks(20, 120, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "query_count", "EXISTS",
                "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                MediaTypeDict.VIDEO.getCode(), WorkAuditTaskStatusDict.QUERYING.getCode(),
                now, 120, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 视频结果领取必须关联同轮次且非人工的审核中作品。 */
    @Test
    void claimVideoQueryShouldIncrementQueryCountAndRefreshLastQueryAt() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.claimVideoQuery(101L, "query-token", now, now.plusMinutes(5), 120);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet()).contains(
                "task_status", "locked_by", "locked_until", "last_query_at",
                "query_count = query_count + 1", "updated_at", "version = version + 1");
        assertThat(wrapper.getSqlSegment()).contains(
                "task_status", "locked_until", "query_count", "EXISTS", "wf_work",
                "audit_round", "audit_status", "manual_audit_no");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                WorkAuditTaskStatusDict.SUBMITTED.getCode(), WorkAuditTaskStatusDict.RUNNING.getCode(),
                WorkAuditTaskStatusDict.QUERYING.getCode(), now, 120,
                WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 达到查询上限的视频领取必须排除人工审核作品且不再递增计数。 */
    @Test
    void claimExhaustedExpiredVideoQueryShouldNotIncrementQueryCount() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.claimExhaustedExpiredVideoQuery(
                101L, "terminal-query-token", now, now.plusMinutes(5), 120);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet())
                .contains("locked_by", "locked_until", "updated_at", "version = version + 1")
                .doesNotContain("query_count = query_count + 1");
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "query_count", "EXISTS",
                "wf_work", "audit_round", "audit_status", "manual_audit_no");
    }

    @Test
    void allTaskStateUpdatesShouldRefreshUpdatedAtAndVersion() {
        assertUpdateRefreshesAuditColumns(repository -> repository.claimVideoQuery(
                101L, "test", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5), 120));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoSubmitted(
                101L, "claim-token", "job-1", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoRunning(
                101L, "claim-token", "Running", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markSuccess(
                101L, AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoQueryFailureForNextRun(
                101L, "claim-token", "远端调用失败", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoSuccess(
                101L, "claim-token", AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoFailed(
                101L, "claim-token", "远端调用失败", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markFailed(101L, "远端调用失败", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markImageSuccess(
                101L, "claim-token", AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markImageFailed(
                101L, "claim-token", "远端调用失败", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.logicDeleteFailedTasksByWorkId(11L));
    }

    @Test
    void markVideoSubmittedShouldGuardSubmittingStatus() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markVideoSubmitted(101L, "claim-token", "job-1", "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "id", "media_type", "task_status", "locked_by", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, MediaTypeDict.VIDEO.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(), "claim-token", 0L);
    }

    @Test
    void allVideoResultUpdatesShouldRequireCurrentClaimToken() {
        assertVideoTokenGuard(repository -> repository.markVideoRunning(
                101L, "claim-token", "Running", "{}"), WorkAuditTaskStatusDict.QUERYING);
        assertVideoTokenGuard(repository -> repository.markVideoQueryFailureForNextRun(
                101L, "claim-token", "远端调用失败", "{}"), WorkAuditTaskStatusDict.QUERYING);
        assertVideoTokenGuard(repository -> repository.markVideoSuccess(
                101L, "claim-token", AuditResultDict.PASS,
                "Success", 0, "Normal", 0, "{}"), WorkAuditTaskStatusDict.QUERYING);

        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        repository.markVideoFailed(101L, "claim-token", "远端调用失败", "{}");
        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "id", "media_type", "task_status", "locked_by", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                101L, MediaTypeDict.VIDEO.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                WorkAuditTaskStatusDict.QUERYING.getCode(), "claim-token", 0L);
    }

    @Test
    void findRunnableAnimationTasksShouldIncludePendingAndExpiredSubmittingTasks() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 7, 30, 18, 0);

        repository.findRunnableAnimationTasks(30, 3, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment())
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted",
                        "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 30");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MediaTypeDict.ANIMATION.getCode(),
                        WorkAuditTaskStatusDict.PENDING.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                        now,
                        3,
                        WorkAuditStatusDict.AUDITING.getCode(),
                        0L);
    }

    @Test
    void claimAnimationTaskShouldIncrementAttemptAndUseExpiredLockGuard() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime lockedUntil = LocalDateTime.of(2026, 7, 30, 18, 5);

        repository.claimAnimationTask(101L, "job-1", lockedUntil, 3);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet())
                .contains("task_status", "attempt_count = attempt_count + 1", "locked_by", "locked_until",
                        "started_at", "updated_at", "version = version + 1");
        assertThat(wrapper.getSqlSegment())
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted",
                        "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, MediaTypeDict.ANIMATION.getCode(), WorkAuditTaskStatusDict.PENDING.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(), 3,
                        WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    @Test
    void markAnimationRetryPendingShouldPreservePersistedSamples() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markAnimationRetryPending(101L, "claim-token", "远端调用失败", "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet())
                .contains("task_status", "last_error_message", "response_payload", "locked_by", "locked_until")
                .doesNotContain("sampled_frame_numbers");
        assertThat(wrapper.getSqlSegment()).contains("id", "media_type", "task_status", "locked_by", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, WorkAuditTaskStatusDict.PENDING.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(), "claim-token", 0L);
    }

    @Test
    void markPendingAnimationFailedShouldOnlyMatchPendingAnimationTask() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markPendingAnimationFailed(101L, "作品审核轮次已变化", "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment())
                .contains("id", "media_type", "task_status", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, MediaTypeDict.ANIMATION.getCode(),
                        WorkAuditTaskStatusDict.PENDING.getCode(), 0L);
    }

    @Test
    void animationTerminalUpdatesShouldRequireCurrentClaimToken() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markAnimationSuccess(
                101L, "claim-token", AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment())
                .contains("id", "media_type", "task_status", "locked_by", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, MediaTypeDict.ANIMATION.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(), "claim-token", 0L);
    }

    @Test
    void findExhaustedExpiredAnimationTasksShouldRecoverCrashAtAttemptLimit() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 7, 30, 18, 0);

        repository.findExhaustedExpiredAnimationTasks(20, 3, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment())
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted",
                        "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MediaTypeDict.ANIMATION.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                        now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    /** 达到尝试上限的动图任务领取必须关联有效的同轮自动审核作品。 */
    @Test
    void claimExhaustedAnimationTaskShouldRequireActiveMatchingAutomaticWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime lockedUntil = LocalDateTime.of(2026, 7, 30, 18, 5);

        repository.claimExhaustedAnimationTask(101L, "terminal-token", lockedUntil, 3);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet())
                .contains("locked_by", "locked_until", "updated_at", "version = version + 1")
                .doesNotContain("attempt_count = attempt_count + 1");
        assertThat(wrapper.getSqlSegment())
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted",
                        "EXISTS", "wf_work", "audit_round", "audit_status", "manual_audit_no");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, MediaTypeDict.ANIMATION.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(), 3,
                        WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    private void assertUpdateRefreshesAuditColumns(Consumer<WorkAuditTaskRepository> repositoryCall) {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repositoryCall.accept(repository);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet()).contains("updated_at", "version = version + 1");
    }

    private void assertVideoTokenGuard(
            Consumer<WorkAuditTaskRepository> repositoryCall, WorkAuditTaskStatusDict expectedStatus) {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repositoryCall.accept(repository);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "id", "media_type", "task_status", "locked_by", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                101L, MediaTypeDict.VIDEO.getCode(), expectedStatus.getCode(), "claim-token", 0L);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<WorkAuditTaskEntity> captureUpdateWrapper(WorkAuditTaskMapper taskMapper) {
        ArgumentCaptor<Wrapper<WorkAuditTaskEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).update((WorkAuditTaskEntity) isNull(), captor.capture());
        return (LambdaUpdateWrapper<WorkAuditTaskEntity>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<WorkAuditTaskEntity> captureSelectCountWrapper(WorkAuditTaskMapper taskMapper) {
        ArgumentCaptor<Wrapper<WorkAuditTaskEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectCount(captor.capture());
        return (LambdaQueryWrapper<WorkAuditTaskEntity>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<WorkAuditTaskEntity> captureSelectListWrapper(WorkAuditTaskMapper taskMapper) {
        ArgumentCaptor<Wrapper<WorkAuditTaskEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<WorkAuditTaskEntity>) captor.getValue();
    }
}
