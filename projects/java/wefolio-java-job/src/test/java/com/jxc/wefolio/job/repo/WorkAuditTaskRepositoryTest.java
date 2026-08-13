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

    @Test
    void countQueryableVideoTasksShouldMatchCandidatePredicate() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.countQueryableVideoTasks(120, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectCountWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "query_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status");
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

    @Test
    void findQueryableVideoTasksShouldIncludeExpiredQueryingAndRequireActiveMatchingWork() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        repository.findQueryableVideoTasks(20, 120, now);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectListWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "query_count", "deleted",
                "EXISTS", "wf_work", "audit_round", "audit_status", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                WorkAuditTaskStatusDict.SUBMITTED.getCode(), WorkAuditTaskStatusDict.RUNNING.getCode(),
                WorkAuditTaskStatusDict.QUERYING.getCode(), now, 120,
                WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

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
                "EXISTS", "wf_work", "audit_round", "audit_status", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                101L, "VIDEO", "SUBMITTING", now, 3, "AUDITING", 0L);
    }

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
        assertThat(wrapper.getSqlSegment()).contains("EXISTS", "attempt_count");
    }

    @Test
    void submissionRecoveryCandidatesShouldSplitRetryableAndExhaustedTasks() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);

        WorkAuditTaskMapper retryableMapper = mock(WorkAuditTaskMapper.class);
        new WorkAuditTaskRepository(retryableMapper)
                .findRetryableExpiredVideoSubmitTasks(20, 3, now);
        LambdaQueryWrapper<WorkAuditTaskEntity> retryable = captureSelectListWrapper(retryableMapper);
        assertThat(retryable.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "EXISTS", "LIMIT 20");
        assertThat(retryable.getParamNameValuePairs().values()).contains(
                MediaTypeDict.VIDEO.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);

        WorkAuditTaskMapper exhaustedMapper = mock(WorkAuditTaskMapper.class);
        new WorkAuditTaskRepository(exhaustedMapper)
                .findExhaustedExpiredVideoSubmitTasks(20, 3, now);
        LambdaQueryWrapper<WorkAuditTaskEntity> exhausted = captureSelectListWrapper(exhaustedMapper);
        assertThat(exhausted.getSqlSegment()).contains(
                "media_type", "task_status", "locked_until", "attempt_count", "EXISTS", "LIMIT 20");
        assertThat(exhausted.getParamNameValuePairs().values()).contains(
                MediaTypeDict.VIDEO.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                now, 3, WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

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
                "task_status", "locked_until", "query_count", "EXISTS", "audit_round");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                WorkAuditTaskStatusDict.SUBMITTED.getCode(), WorkAuditTaskStatusDict.RUNNING.getCode(),
                WorkAuditTaskStatusDict.QUERYING.getCode(), now, 120,
                WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

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
                "media_type", "task_status", "locked_until", "query_count", "EXISTS");
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
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted", "LIMIT 30");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MediaTypeDict.ANIMATION.getCode(),
                        WorkAuditTaskStatusDict.PENDING.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                        now,
                        3,
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
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, MediaTypeDict.ANIMATION.getCode(), WorkAuditTaskStatusDict.PENDING.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTING.getCode(), 3, 0L);
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
                .contains("media_type", "task_status", "locked_until", "attempt_count", "deleted", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MediaTypeDict.ANIMATION.getCode(), WorkAuditTaskStatusDict.SUBMITTING.getCode(),
                        now, 3, 0L);
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
