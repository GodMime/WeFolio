package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.WorkAuditProperties;
import com.jxc.wefolio.dict.WorkAuditReasonCodeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.MineWorkAuditResubmitResponse;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.MineWorkMessage;
import com.jxc.wefolio.model.WorkManualAuditSubmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作品主动重新审核服务测试。 */
@ExtendWith(MockitoExtension.class)
class MineWorkAuditServiceTest {

    /** 作品 Mapper 模拟。 */
    @Mock
    private WorkEntityMapper workEntityMapper;

    /** 人工审核编号生成器模拟。 */
    @Mock
    private WorkManualAuditNoGenerator manualAuditNoGenerator;

    /** 审核轮次配置。 */
    private WorkAuditProperties properties;

    /** 初始化 MyBatis 字段缓存、登录上下文和默认审核配置。 */
    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "mine-work-audit"),
                WorkEntity.class);
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        properties = new WorkAuditProperties();
    }

    /** 清理当前测试线程的登录上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /** 三种可重审状态在非最终轮均进入下一轮自动审核且不生成编号。 */
    @ParameterizedTest
    @EnumSource(value = WorkAuditStatusDict.class, names = {"REJECTED", "REVIEW_REQUIRED", "FAILED"})
    void eligibleFailureStatusShouldEnterNextRound(WorkAuditStatusDict status) {
        WorkEntity work = work(status, 1);
        when(workEntityMapper.selectOne(any())).thenReturn(work);
        when(workEntityMapper.update(isNull(), any())).thenReturn(1);

        MineWorkAuditService.ResubmitResult result = service().resubmit(11L);
        MineWorkAuditResubmitResponse response = result.response();

        assertThat(response.getWorkId()).isEqualTo(11L);
        assertThat(response.getAuditStatus()).isEqualTo(WorkAuditStatusDict.PENDING.getCode());
        assertThat(response.getAuditRound()).isEqualTo(2);
        assertThat(response.getMaxAuditRounds()).isEqualTo(3);
        assertThat(response.getRemainingAuditResubmitCount()).isEqualTo(1);
        assertThat(response.isCanResubmitAudit()).isFalse();
        assertThat(result.notification()).isNull();
        verify(manualAuditNoGenerator, never()).generate();
    }

    /** 三种可重审状态在最终轮均进入人工审核并生成通知快照。 */
    @ParameterizedTest
    @EnumSource(value = WorkAuditStatusDict.class, names = {"REJECTED", "REVIEW_REQUIRED", "FAILED"})
    void secondRoundShouldEnterFinalManualAuditRound(WorkAuditStatusDict status) {
        WorkEntity work = work(status, 2);
        List<WorkAuditUserReasonResolver.AuditReason> expectedPreviousReasons =
                service().buildAuditView(work).auditReasons();
        when(workEntityMapper.selectOne(any())).thenReturn(work);
        when(workEntityMapper.update(isNull(), any())).thenReturn(1);
        when(manualAuditNoGenerator.generate())
                .thenReturn("WA20260827153042A7K2Q9");

        MineWorkAuditService.ResubmitResult result = service().resubmit(11L);
        MineWorkAuditResubmitResponse response = result.response();

        assertThat(response.getAuditStatus()).isEqualTo(WorkAuditStatusDict.AUDITING.getCode());
        assertThat(response.getAuditRound()).isEqualTo(3);
        assertThat(response.getRemainingAuditResubmitCount()).isZero();
        WorkManualAuditSubmission notification = result.notification();
        assertThat(notification).isNotNull();
        assertThat(notification.manualAuditNo())
                .isEqualTo("WA20260827153042A7K2Q9");
        assertThat(notification.previousReasons()).isEqualTo(expectedPreviousReasons);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workEntityMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("manual_audit_no");
        assertThat(wrapperCaptor.getValue().getSqlSet())
                .contains("audit_status", "manual_audit_no", "manual_audit_result_at",
                        "audit_reason_code", "audit_reason_codes", "audit_reject_reason");
        assertThat(((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue())
                .getParamNameValuePairs().values())
                .contains(WorkAuditStatusDict.AUDITING.getCode(), notification.manualAuditNo());
    }

    /** 已到最大轮次的作品不得再次提交审核。 */
    @Test
    void thirdRoundShouldBeRejected() {
        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.REJECTED, 3));

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
    }

    /** 已在待审核或审核中的作品不得重复提交。 */
    @ParameterizedTest
    @EnumSource(value = WorkAuditStatusDict.class, names = {"PENDING", "AUDITING"})
    void workAlreadyInAuditFlowShouldBeRejected(WorkAuditStatusDict status) {
        when(workEntityMapper.selectOne(any())).thenReturn(work(status, 1));

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_IN_PROGRESS_MESSAGE);
        verify(workEntityMapper, never()).update(isNull(), any());
    }

    /** 已通过作品不得再次提交审核。 */
    @Test
    void passedWorkShouldBeRejected() {
        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.PASSED, 1));

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_PASSED_MESSAGE);
    }

    /** 不存在或不属于当前用户的作品沿用不存在语义。 */
    @Test
    void unknownOrUnownedWorkShouldUseExistingNotFoundSemantics() {
        when(workEntityMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service().resubmit(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
    }

    /** 并发进入待审核或审核中时返回审核进行中。 */
    @ParameterizedTest
    @EnumSource(value = WorkAuditStatusDict.class, names = {"PENDING", "AUDITING"})
    void concurrentAdvanceIntoAuditFlowShouldReturnInProgress(WorkAuditStatusDict latestStatus) {
        when(workEntityMapper.selectOne(any()))
                .thenReturn(work(WorkAuditStatusDict.REJECTED, 1))
                .thenReturn(work(latestStatus, 2));
        when(workEntityMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_IN_PROGRESS_MESSAGE);
    }

    /** 并发通过、达到上限或其它状态变化必须按最新数据库状态返回。 */
    @Test
    void concurrentPassOrLimitOrOtherChangeShouldUseLatestDatabaseState() {
        assertConcurrentMessage(work(WorkAuditStatusDict.PASSED, 2), MineWorkMessage.AUDIT_RESUBMIT_PASSED_MESSAGE);
        assertConcurrentMessage(
                work(WorkAuditStatusDict.REJECTED, 3),
                MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
        assertConcurrentMessage(
                work(WorkAuditStatusDict.REVIEW_REQUIRED, 1),
                MineWorkMessage.AUDIT_RESUBMIT_STATE_CHANGED_MESSAGE);
    }

    /** 原子更新必须精确使用 audit_round 小于最大轮次并携带完整条件值。 */
    @Test
    void atomicUpdateShouldConstrainOwnerStatusRoundAndClearOldReasons() {
        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.FAILED, 1));
        when(workEntityMapper.update(isNull(), any())).thenReturn(1);

        service().resubmit(11L);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workEntityMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("user_id", "status", "audit_status", "audit_round", "deleted");
        assertThat(sqlSegment).contains("audit_round <");
        assertThat(sqlSegment).contains("manual_audit_no");
        assertThat(((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue())
                .getParamNameValuePairs().values())
                .contains(7L, WorkStatusDict.ACTIVE.getCode(),
                        WorkAuditStatusDict.REJECTED.getCode(),
                        WorkAuditStatusDict.REVIEW_REQUIRED.getCode(),
                        WorkAuditStatusDict.FAILED.getCode(), 3, 0L);
        assertThat(wrapperCaptor.getValue().getSqlSet())
                .contains("audit_reason_code", "audit_reason_codes", "audit_reject_reason");
    }

    /** 人工编号一旦生成，即使最大轮次提高也不得再次提交。 */
    @Test
    void existingManualAuditNumberAlwaysBlocksResubmitEvenWhenLimitIncreases() {
        properties.setMaxRounds(4);
        WorkEntity work = work(WorkAuditStatusDict.REJECTED, 3);
        work.setManualAuditNo("WA20260827153042A7K2Q9");
        when(workEntityMapper.selectOne(any())).thenReturn(work);

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
        verify(workEntityMapper, never()).update(isNull(), any());
    }

    /** 人工审核编号唯一冲突必须返回固定重试文案。 */
    @Test
    void duplicateManualAuditNumberUsesFixedRetryMessage() {
        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.REJECTED, 2));
        when(manualAuditNoGenerator.generate())
                .thenReturn("WA20260827153042A7K2Q9");
        when(workEntityMapper.update(isNull(), any()))
                .thenThrow(new DuplicateKeyException("uk_work_manual_audit_no"));

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.MANUAL_AUDIT_NO_CONFLICT_MESSAGE);
    }

    /** 审核展示必须使用配置轮次并把自动原因转换为用户文案。 */
    @Test
    void auditViewShouldUseConfiguredLimitAndFriendlyReason() {
        properties.setMaxRounds(4);
        WorkEntity work = work(WorkAuditStatusDict.REJECTED, 2);
        work.setAuditReasonCode(WorkAuditReasonCodeDict.PORN_CONTENT.getCode());
        work.setAuditReasonCodes("[\"PORN_CONTENT\",\"ADVERTISING_CONTENT\"]");
        work.setAuditRejectReason("腾讯云判定违规：label=Porn，result=2，score=88");

        MineWorkAuditService.AuditView view = service().buildAuditView(work);

        assertThat(view.auditRound()).isEqualTo(2);
        assertThat(view.maxAuditRounds()).isEqualTo(4);
        assertThat(view.remainingAuditResubmitCount()).isEqualTo(2);
        assertThat(view.canResubmitAudit()).isTrue();
        assertThat(view.auditRejectReason())
                .isEqualTo("作品可能包含色情或低俗内容，未通过审核，请调整后重新提交")
                .doesNotContain("腾讯云", "Porn", "result=", "score=");
        assertThat(view.auditReasons())
                .extracting(WorkAuditUserReasonResolver.AuditReason::code)
                .containsExactly("PORN_CONTENT", "ADVERTISING_CONTENT");
    }

    /** 历史空审核状态必须以安全默认值展示。 */
    @Test
    void auditViewShouldTolerateLegacyNullAuditStatus() {
        WorkEntity work = new WorkEntity();
        work.setAuditRound(null);

        MineWorkAuditService.AuditView view = service().buildAuditView(work);

        assertThat(view.auditRound()).isEqualTo(1);
        assertThat(view.remainingAuditResubmitCount()).isEqualTo(2);
        assertThat(view.canResubmitAudit()).isFalse();
        assertThat(view.auditRejectReason()).isNull();
        assertThat(view.auditReasons()).isEmpty();
    }

    /** 人工拒绝展示必须保留审核员填写的原始原因。 */
    @Test
    void manualRejectedAuditViewUsesOriginalHumanReason() {
        WorkEntity work = work(WorkAuditStatusDict.REJECTED, 3);
        work.setManualAuditNo("WA20260827153042A7K2Q9");
        work.setAuditRejectReason("封面含有无法核实的联系方式");

        MineWorkAuditService.AuditView view = service().buildAuditView(work);

        assertThat(view.remainingAuditResubmitCount()).isZero();
        assertThat(view.canResubmitAudit()).isFalse();
        assertThat(view.auditRejectReason()).isEqualTo("封面含有无法核实的联系方式");
        assertThat(view.auditReasons())
                .containsExactly(new WorkAuditUserReasonResolver.AuditReason(
                        "MANUAL_REVIEW", "封面含有无法核实的联系方式"));
    }

    /** 断言并发条件更新未命中时按最新作品状态返回指定文案。 */
    private void assertConcurrentMessage(WorkEntity latest, String expectedMessage) {
        reset(workEntityMapper);
        when(workEntityMapper.selectOne(any()))
                .thenReturn(work(WorkAuditStatusDict.REJECTED, 1))
                .thenReturn(latest);
        when(workEntityMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(expectedMessage);
    }

    /** 创建使用当前测试配置和模拟依赖的审核服务。 */
    private MineWorkAuditService service() {
        return new MineWorkAuditService(
                workEntityMapper,
                properties,
                new WorkAuditUserReasonResolver(),
                manualAuditNoGenerator);
    }

    /** 构建指定状态和轮次的有效作品。 */
    private WorkEntity work(WorkAuditStatusDict status, int round) {
        WorkEntity work = new WorkEntity();
        work.setId(11L);
        work.setUserId(7L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setAuditStatus(status.getCode());
        work.setAuditRound(round);
        work.setAuditReasonCode(WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode());
        work.setAuditReasonCodes("[\"AUDIT_SERVICE_ERROR\"]");
        work.setAuditRejectReason("旧原因");
        work.setTitle("草坪婚礼快剪");
        work.setMediaType("VIDEO");
        work.setMediaObjectKey("WFA3B1E7A2/work/video/demo.mp4");
        work.setDeleted(0L);
        return work;
    }
}
