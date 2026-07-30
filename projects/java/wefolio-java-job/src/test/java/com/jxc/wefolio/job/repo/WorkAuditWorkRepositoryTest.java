package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditWorkEntity;
import com.jxc.wefolio.job.mapper.WorkAuditWorkMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 作品审核用作品仓储测试。
 */
class WorkAuditWorkRepositoryTest {

    /**
     * 初始化 MyBatis-Plus Lambda 字段缓存，便于直接检查 wrapper 条件。
     */
    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(WorkAuditWorkEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                    WorkAuditWorkEntity.class);
        }
    }

    @Test
    void countPendingImagesShouldFilterPendingImageAndNotDeleted() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.countPendingImages();

        LambdaQueryWrapper<WorkAuditWorkEntity> wrapper = captureSelectCountWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("audit_status", "media_type", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WorkAuditStatusDict.PENDING.getCode(), MediaTypeDict.IMAGE.getCode(), 0L);
    }

    @Test
    void countPendingVideosShouldFilterPendingVideoAndNotDeleted() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.countPendingVideos();

        LambdaQueryWrapper<WorkAuditWorkEntity> wrapper = captureSelectCountWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("audit_status", "media_type", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WorkAuditStatusDict.PENDING.getCode(), MediaTypeDict.VIDEO.getCode(), 0L);
    }

    @Test
    void findPendingAnimationsShouldFilterPendingAnimationAndNotDeleted() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.findPendingAnimations(25);

        LambdaQueryWrapper<WorkAuditWorkEntity> wrapper = captureSelectListWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("audit_status", "media_type", "deleted", "LIMIT 25");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WorkAuditStatusDict.PENDING.getCode(), MediaTypeDict.ANIMATION.getCode(), 0L);
    }

    @Test
    void countPendingAnimationsShouldFilterPendingAnimationAndNotDeleted() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.countPendingAnimations();

        LambdaQueryWrapper<WorkAuditWorkEntity> wrapper = captureSelectCountWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("audit_status", "media_type", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WorkAuditStatusDict.PENDING.getCode(), MediaTypeDict.ANIMATION.getCode(), 0L);
    }

    @Test
    void claimPendingWorkShouldGuardStatusAndRefreshAuditColumns() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.claimPendingWork(11L);

        LambdaUpdateWrapper<WorkAuditWorkEntity> wrapper = captureUpdateWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("id", "audit_status", "deleted");
        assertThat(wrapper.getSqlSet())
                .contains("audit_status", "audit_reason_code", "audit_reason_codes", "audit_reject_reason", "updated_at",
                        "version = version + 1");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(11L, WorkAuditStatusDict.PENDING.getCode(), WorkAuditStatusDict.AUDITING.getCode(), 0L);
    }

    @Test
    @SuppressWarnings("deprecation")
    void updateAuditStatusShouldFilterNotDeletedAndRefreshAuditColumns() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.updateAuditStatus(11L, WorkAuditStatusDict.PASSED);

        LambdaUpdateWrapper<WorkAuditWorkEntity> wrapper = captureUpdateWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("id", "deleted");
        assertThat(wrapper.getSqlSet()).contains("audit_status", "updated_at", "version = version + 1");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(11L, WorkAuditStatusDict.PASSED.getCode(), 0L);
    }

    @Test
    void updateAuditStatusAndRejectReasonShouldUpdateStatusAndReasonTogether() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.updateAuditStatusAndRejectReason(11L, WorkAuditStatusDict.REVIEW_REQUIRED, "疑似违规");

        LambdaUpdateWrapper<WorkAuditWorkEntity> wrapper = captureUpdateWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("id", "deleted");
        assertThat(wrapper.getSqlSet())
                .contains("audit_status", "audit_reject_reason", "updated_at", "version = version + 1");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(11L, WorkAuditStatusDict.REVIEW_REQUIRED.getCode(), "疑似违规", 0L);
    }

    @Test
    void updateAuditStatusAndReasonsShouldPersistStableReasonCodeAndInternalSummaryTogether() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.updateAuditStatusAndReasons(
                11L,
                WorkAuditStatusDict.REJECTED,
                "PORN_CONTENT",
                "[\"PORN_CONTENT\",\"ADVERTISING_CONTENT\"]",
                "腾讯云判定违规：label=Porn，result=1，score=88");

        LambdaUpdateWrapper<WorkAuditWorkEntity> wrapper = captureUpdateWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("id", "deleted");
        assertThat(wrapper.getSqlSet())
                .contains("audit_status", "audit_reason_code", "audit_reason_codes", "audit_reject_reason", "updated_at",
                        "version = version + 1");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(11L, WorkAuditStatusDict.REJECTED.getCode(), "PORN_CONTENT",
                        "[\"PORN_CONTENT\",\"ADVERTISING_CONTENT\"]",
                        "腾讯云判定违规：label=Porn，result=1，score=88", 0L);
    }

    @Test
    void updateAuditStatusAndReasonsForRoundShouldRejectStaleAuditRound() {
        WorkAuditWorkMapper workMapper = mock(WorkAuditWorkMapper.class);
        WorkAuditWorkRepository repository = new WorkAuditWorkRepository(workMapper);

        repository.updateAuditStatusAndReasonsForRound(
                11L, 2, WorkAuditStatusDict.PASSED, null, null, null);

        LambdaUpdateWrapper<WorkAuditWorkEntity> wrapper = captureUpdateWrapper(workMapper);
        assertThat(wrapper.getSqlSegment()).contains("id", "audit_round", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(11L, 2, WorkAuditStatusDict.PASSED.getCode(), 0L);
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<WorkAuditWorkEntity> captureSelectCountWrapper(WorkAuditWorkMapper workMapper) {
        ArgumentCaptor<Wrapper<WorkAuditWorkEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workMapper).selectCount(captor.capture());
        return (LambdaQueryWrapper<WorkAuditWorkEntity>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<WorkAuditWorkEntity> captureSelectListWrapper(WorkAuditWorkMapper workMapper) {
        ArgumentCaptor<Wrapper<WorkAuditWorkEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<WorkAuditWorkEntity>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<WorkAuditWorkEntity> captureUpdateWrapper(WorkAuditWorkMapper workMapper) {
        ArgumentCaptor<Wrapper<WorkAuditWorkEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workMapper).update((WorkAuditWorkEntity) isNull(), captor.capture());
        return (LambdaUpdateWrapper<WorkAuditWorkEntity>) captor.getValue();
    }
}
