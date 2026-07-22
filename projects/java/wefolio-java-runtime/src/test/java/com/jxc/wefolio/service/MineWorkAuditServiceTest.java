package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作品主动重新审核服务测试。 */
@ExtendWith(MockitoExtension.class)
class MineWorkAuditServiceTest {

    @Mock
    private WorkEntityMapper workEntityMapper;

    private WorkAuditProperties properties;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "mine-work-audit"),
                WorkEntity.class);
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        properties = new WorkAuditProperties();
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @ParameterizedTest
    @EnumSource(value = WorkAuditStatusDict.class, names = {"REJECTED", "REVIEW_REQUIRED", "FAILED"})
    void eligibleFailureStatusShouldEnterNextRound(WorkAuditStatusDict status) {
        WorkEntity work = work(status, 1);
        when(workEntityMapper.selectOne(any())).thenReturn(work);
        when(workEntityMapper.update(isNull(), any())).thenReturn(1);

        MineWorkAuditResubmitResponse response = service().resubmit(11L);

        assertThat(response.getWorkId()).isEqualTo(11L);
        assertThat(response.getAuditStatus()).isEqualTo(WorkAuditStatusDict.PENDING.getCode());
        assertThat(response.getAuditRound()).isEqualTo(2);
        assertThat(response.getMaxAuditRounds()).isEqualTo(3);
        assertThat(response.getRemainingAuditResubmitCount()).isEqualTo(1);
        assertThat(response.isCanResubmitAudit()).isFalse();
    }

    @Test
    void secondRoundShouldEnterThirdAndThirdRoundShouldBeRejected() {
        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.REJECTED, 2));
        when(workEntityMapper.update(isNull(), any())).thenReturn(1);

        MineWorkAuditResubmitResponse response = service().resubmit(11L);

        assertThat(response.getAuditRound()).isEqualTo(3);
        assertThat(response.getRemainingAuditResubmitCount()).isZero();

        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.REJECTED, 3));
        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_LIMIT_REACHED_MESSAGE);
    }

    @ParameterizedTest
    @EnumSource(value = WorkAuditStatusDict.class, names = {"PENDING", "AUDITING"})
    void workAlreadyInAuditFlowShouldBeRejected(WorkAuditStatusDict status) {
        when(workEntityMapper.selectOne(any())).thenReturn(work(status, 1));

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_IN_PROGRESS_MESSAGE);
        verify(workEntityMapper, never()).update(isNull(), any());
    }

    @Test
    void passedWorkShouldBeRejected() {
        when(workEntityMapper.selectOne(any())).thenReturn(work(WorkAuditStatusDict.PASSED, 1));

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.AUDIT_RESUBMIT_PASSED_MESSAGE);
    }

    @Test
    void unknownOrUnownedWorkShouldUseExistingNotFoundSemantics() {
        when(workEntityMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service().resubmit(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineWorkMessage.WORK_NOT_FOUND_MESSAGE);
    }

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
        assertThat(wrapperCaptor.getValue().getSqlSet())
                .contains("audit_reason_code", "audit_reason_codes", "audit_reject_reason");
    }

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

    private void assertConcurrentMessage(WorkEntity latest, String expectedMessage) {
        org.mockito.Mockito.reset(workEntityMapper);
        when(workEntityMapper.selectOne(any()))
                .thenReturn(work(WorkAuditStatusDict.REJECTED, 1))
                .thenReturn(latest);
        when(workEntityMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().resubmit(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(expectedMessage);
    }

    private MineWorkAuditService service() {
        return new MineWorkAuditService(workEntityMapper, properties, new WorkAuditUserReasonResolver());
    }

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
        work.setDeleted(0L);
        return work;
    }
}
