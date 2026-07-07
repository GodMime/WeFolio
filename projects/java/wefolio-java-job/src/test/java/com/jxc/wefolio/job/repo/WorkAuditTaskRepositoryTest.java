package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.MediaTypeDict;
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
    void countQueryableVideoTasksShouldFilterSubmittedAndRunningVideoUnderLimit() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.countQueryableVideoTasks(120);

        LambdaQueryWrapper<WorkAuditTaskEntity> wrapper = captureSelectCountWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains("media_type", "task_status", "query_count", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MediaTypeDict.VIDEO.getCode(),
                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                        WorkAuditTaskStatusDict.RUNNING.getCode(),
                        120,
                        0L);
    }

    @Test
    void allTaskStateUpdatesShouldRefreshUpdatedAtAndVersion() {
        assertUpdateRefreshesAuditColumns(repository -> repository.claimVideoQuery(
                101L, "test", LocalDateTime.now().plusMinutes(5), 120));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoSubmitted(101L, "job-1", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markVideoRunning(101L, "Running", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markSuccess(
                101L, AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markQueryFailureForNextRun(
                101L, "远端调用失败", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.markFailed(101L, "远端调用失败", "{}"));
        assertUpdateRefreshesAuditColumns(repository -> repository.logicDeleteFailedTasksByWorkId(11L));
    }

    @Test
    void markVideoSubmittedShouldGuardSubmittingStatus() {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repository.markVideoSubmitted(101L, "job-1", "{}");

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSegment()).contains("id", "task_status", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(101L, WorkAuditTaskStatusDict.SUBMITTING.getCode(), 0L);
    }

    private void assertUpdateRefreshesAuditColumns(Consumer<WorkAuditTaskRepository> repositoryCall) {
        WorkAuditTaskMapper taskMapper = mock(WorkAuditTaskMapper.class);
        WorkAuditTaskRepository repository = new WorkAuditTaskRepository(taskMapper);

        repositoryCall.accept(repository);

        LambdaUpdateWrapper<WorkAuditTaskEntity> wrapper = captureUpdateWrapper(taskMapper);
        assertThat(wrapper.getSqlSet()).contains("updated_at", "version = version + 1");
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
}
