package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.WorkManualAuditUpdateRequest;
import com.jxc.wefolio.dto.WorkManualAuditUpdateResponse;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.WorkManualAuditMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 作品人工审核回传服务测试。 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WorkManualAuditServiceTest {

    /** 固定人工审核编号。 */
    private static final String MANUAL_NO = "WA20260827153042A7K2Q9";

    /** 固定首次结论时间。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0);

    /** 作品 Mapper 模拟。 */
    @Mock
    private WorkEntityMapper mapper;

    /** 非法人工审核编号必须在访问数据库前失败。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "FB20260827153042ABCDEF"})
    void invalidManualNumberFailsBeforeDatabaseAccess(String manualAuditNo) {
        assertThatThrownBy(() -> service().update(manualAuditNo, request("PASSED", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.NOT_FOUND_MESSAGE);

        verifyNoInteractions(mapper);
    }

    /** 人工审核编号仅按共享前缀识别，并在回传前去除首尾空白。 */
    @Test
    void manualNumberUsesSharedPrefixAndNormalizedValue() {
        String normalizedManualAuditNo = "WAlegacy-text";
        WorkEntity auditing = auditingWork();
        auditing.setManualAuditNo(normalizedManualAuditNo);
        when(mapper.lockByManualAuditNo(normalizedManualAuditNo)).thenReturn(auditing);
        when(mapper.completeManualAudit(
                91L,
                normalizedManualAuditNo,
                WorkAuditStatusDict.PASSED.getCode(),
                null,
                NOW)).thenReturn(1);

        WorkManualAuditUpdateResponse response = service().update(
                " WAlegacy-text ", request("PASSED", null));

        assertThat(response.getManualAuditNo()).isEqualTo(normalizedManualAuditNo);
        assertThat(response.getAuditStatus()).isEqualTo(WorkAuditStatusDict.PASSED.getCode());
        verify(mapper).lockByManualAuditNo(normalizedManualAuditNo);
    }

    /** 状态规范化后只允许通过或拒绝。 */
    @Test
    void requestOnlyAllowsPassedOrRejected() {
        assertThatThrownBy(() -> service().update(MANUAL_NO, request("AUDITING", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.STATUS_INVALID_MESSAGE);
        verify(mapper, never()).lockByManualAuditNo(anyString());
    }

    /** 拒绝原因按 Unicode 码点计数并接受恰好 512 个码点。 */
    @Test
    void rejectReasonAcceptsTrimmedUnicodeCodePointLimit() {
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(auditingWork());
        when(mapper.completeManualAudit(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        WorkManualAuditUpdateResponse accepted = service().update(
                MANUAL_NO, request(" rejected ", " " + "😀".repeat(512) + " "));

        assertThat(accepted.getAuditRejectReason()).isEqualTo("😀".repeat(512));
        verify(mapper).completeManualAudit(
                91L, MANUAL_NO, WorkAuditStatusDict.REJECTED.getCode(), "😀".repeat(512), NOW);

    }

    /** 超过 512 个 Unicode 码点的拒绝原因必须在访问数据库前失败。 */
    @Test
    void tooLongRejectReasonFailsBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service().update(
                MANUAL_NO, request("REJECTED", "😀".repeat(513))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.REJECT_REASON_TOO_LONG_MESSAGE);

        verify(mapper, never()).lockByManualAuditNo(anyString());
    }

    /** 通过和拒绝分别执行对应的原因规则，并兼容大小写和首尾空白。 */
    @Test
    void reasonRulesDependOnTargetStatus() {
        assertThatThrownBy(() -> service().update(MANUAL_NO, request("REJECTED", " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.REJECT_REASON_EMPTY_MESSAGE);
        assertThatThrownBy(() -> service().update(MANUAL_NO, request("PASSED", "不应携带")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.PASSED_REASON_NOT_ALLOWED_MESSAGE);

        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(auditingWork());
        when(mapper.completeManualAudit(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);
        assertThat(service().update(MANUAL_NO, request(" PASSED ", "  "))
                .getAuditRejectReason()).isNull();
    }

    /** 审核中记录首次写入拒绝结论并返回完整最小响应。 */
    @Test
    void firstAuditingResultWinsAndReturnsChangedResponse() {
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(auditingWork());
        when(mapper.completeManualAudit(
                91L, MANUAL_NO, WorkAuditStatusDict.REJECTED.getCode(), "原因", NOW))
                .thenReturn(1);

        WorkManualAuditUpdateResponse response = service().update(
                MANUAL_NO, request("REJECTED", " 原因 "));

        assertThat(response.getManualAuditNo()).isEqualTo(MANUAL_NO);
        assertThat(response.getAuditStatus()).isEqualTo(WorkAuditStatusDict.REJECTED.getCode());
        assertThat(response.getAuditRejectReason()).isEqualTo("原因");
        assertThat(response.getManualAuditResultAt()).isEqualTo(NOW);
        assertThat(response.isChanged()).isTrue();
    }

    /** 相同通过结论重复回传应幂等成功并保留首次结论时间。 */
    @Test
    void passedTerminalReplayIsIdempotentAndPreservesResultTime() {
        WorkEntity passed = terminalWork(WorkAuditStatusDict.PASSED, null);
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(passed);

        WorkManualAuditUpdateResponse response = service().update(
                MANUAL_NO, request(" passed ", null));

        assertThat(response.isChanged()).isFalse();
        assertThat(response.getManualAuditResultAt()).isEqualTo(NOW.minusHours(1));
        verify(mapper, never()).completeManualAudit(anyLong(), anyString(), anyString(), any(), any());
    }

    /** 相同拒绝结论重复回传应幂等成功并保留首次结论时间。 */
    @Test
    void rejectedTerminalReplayIsIdempotentAndPreservesResultTime() {
        WorkEntity rejected = terminalWork(WorkAuditStatusDict.REJECTED, "原因");
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(rejected);

        WorkManualAuditUpdateResponse response = service().update(
                MANUAL_NO, request("REJECTED", " 原因 "));

        assertThat(response.isChanged()).isFalse();
        assertThat(response.getManualAuditResultAt()).isEqualTo(NOW.minusHours(1));
        verify(mapper, never()).completeManualAudit(anyLong(), anyString(), anyString(), any(), any());
    }

    /** 已有结论与新状态或新拒绝原因不一致时必须拒绝覆盖。 */
    @Test
    void differentTerminalConclusionConflicts() {
        WorkEntity rejected = terminalWork(WorkAuditStatusDict.REJECTED, "原因");
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(rejected);

        assertThatThrownBy(() -> service().update(
                MANUAL_NO, request("REJECTED", "另一原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.RESULT_CONFLICT_MESSAGE);
        assertThatThrownBy(() -> service().update(MANUAL_NO, request("PASSED", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.RESULT_CONFLICT_MESSAGE);
        verify(mapper, never()).completeManualAudit(anyLong(), anyString(), anyString(), any(), any());
    }

    /** 人工编号存在时不依赖当前最大轮次，历史非最终轮记录仍可写入结论。 */
    @Test
    void manualNumberAllowsCallbackWhenStoredRoundIsNoLongerFinal() {
        WorkEntity auditing = auditingWork();
        auditing.setAuditRound(2);
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(auditing);
        when(mapper.completeManualAudit(
                91L, MANUAL_NO, WorkAuditStatusDict.PASSED.getCode(), null, NOW))
                .thenReturn(1);

        WorkManualAuditUpdateResponse response = service().update(
                MANUAL_NO, request("PASSED", null));

        assertThat(response.getAuditStatus()).isEqualTo(WorkAuditStatusDict.PASSED.getCode());
        assertThat(response.isChanged()).isTrue();
    }

    /** 缺失、停用、删除或不在人工审核流程的记录统一按失效处理。 */
    @Test
    void invalidOrMissingLockedWorkUsesExpiredMessage() {
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(null);
        assertExpired();

        WorkEntity inactive = auditingWork();
        inactive.setStatus("DISABLED");
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(inactive);
        assertExpired();

        WorkEntity deleted = auditingWork();
        deleted.setDeleted(91L);
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(deleted);
        assertExpired();

        WorkEntity pending = auditingWork();
        pending.setAuditStatus(WorkAuditStatusDict.PENDING.getCode());
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(pending);
        assertExpired();
    }

    /** 条件更新未命中时返回并发冲突且日志不包含拒绝原因。 */
    @Test
    void conditionalUpdateMissUsesConcurrentConflictAndSanitizedWarning(CapturedOutput output) {
        when(mapper.lockByManualAuditNo(MANUAL_NO)).thenReturn(auditingWork());
        when(mapper.completeManualAudit(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service().update(
                MANUAL_NO, request("REJECTED", "敏感拒绝原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.CONCURRENT_CONFLICT_MESSAGE);
        assertThat(output)
                .contains("作品人工审核条件更新未命中")
                .contains("workId=91")
                .contains("manualAuditNo=" + MANUAL_NO)
                .contains("targetStatus=REJECTED")
                .doesNotContain("敏感拒绝原因");
    }

    /** 断言当前人工记录按失效语义失败。 */
    private void assertExpired() {
        assertThatThrownBy(() -> service().update(MANUAL_NO, request("PASSED", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(WorkManualAuditMessage.NOT_FOUND_MESSAGE);
    }

    /** 创建使用固定时钟的被测服务。 */
    private WorkManualAuditService service() {
        return new WorkManualAuditService(
                mapper,
                Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC));
    }

    /** 构建人工审核回传请求。 */
    private WorkManualAuditUpdateRequest request(String status, String reason) {
        WorkManualAuditUpdateRequest request = new WorkManualAuditUpdateRequest();
        request.setStatus(status);
        request.setAuditRejectReason(reason);
        return request;
    }

    /** 构建有效的人工审核中作品。 */
    private WorkEntity auditingWork() {
        WorkEntity work = new WorkEntity();
        work.setId(91L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode());
        work.setAuditStatus(WorkAuditStatusDict.AUDITING.getCode());
        work.setAuditRound(3);
        work.setManualAuditNo(MANUAL_NO);
        work.setDeleted(0L);
        return work;
    }

    /** 构建已写入首次人工结论的作品。 */
    private WorkEntity terminalWork(WorkAuditStatusDict status, String reason) {
        WorkEntity work = auditingWork();
        work.setAuditStatus(status.getCode());
        work.setAuditRejectReason(reason);
        work.setManualAuditResultAt(NOW.minusHours(1));
        return work;
    }
}
