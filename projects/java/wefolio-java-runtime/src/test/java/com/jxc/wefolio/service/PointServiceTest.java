package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PointCalcModeDict;
import com.jxc.wefolio.dict.MessageActionTypeDict;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dict.MessageTypeDict;
import com.jxc.wefolio.dict.PointRuleStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.PointTransactionTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MinePointOverviewResponse;
import com.jxc.wefolio.dto.PointCalculationRequest;
import com.jxc.wefolio.dto.PointCalculationResponse;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointMeterEntity;
import com.jxc.wefolio.entity.PointRuleEntity;
import com.jxc.wefolio.entity.PointTransactionEntity;
import com.jxc.wefolio.entity.SystemMessageEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointMeterEntityMapper;
import com.jxc.wefolio.mapper.PointRuleEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import com.jxc.wefolio.mapper.SystemMessageEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 积分服务测试 — 覆盖人工加分、固定扣费、余额不足和累计阈值计算。
 */
@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    /** 用户 Mapper 模拟 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 积分账户 Mapper 模拟 */
    @Mock
    private PointAccountEntityMapper pointAccountEntityMapper;

    /** 积分规则 Mapper 模拟 */
    @Mock
    private PointRuleEntityMapper pointRuleEntityMapper;

    /** 积分计量器 Mapper 模拟 */
    @Mock
    private PointMeterEntityMapper pointMeterEntityMapper;

    /** 积分流水 Mapper 模拟 */
    @Mock
    private PointTransactionEntityMapper pointTransactionEntityMapper;

    /** 系统消息 Mapper 模拟 */
    @Mock
    private SystemMessageEntityMapper systemMessageEntityMapper;

    @Test
    void grantPointsWritesGiftTransactionAndUpdatesBalance() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 20L));
        when(pointAccountEntityMapper.updateById(any(PointAccountEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            PointTransactionEntity transaction = invocation.getArgument(0);
            transaction.setId(88L);
            return 1;
        }).when(pointTransactionEntityMapper).insert(any(PointTransactionEntity.class));

        PointMutationResponse response = service().grantPoints(7L, 100L, "manual-grant-1", "后台补分");

        ArgumentCaptor<PointAccountEntity> accountCaptor = ArgumentCaptor.forClass(PointAccountEntity.class);
        ArgumentCaptor<PointTransactionEntity> transactionCaptor = ArgumentCaptor.forClass(PointTransactionEntity.class);
        verify(pointAccountEntityMapper).updateById(accountCaptor.capture());
        verify(pointTransactionEntityMapper).insert(transactionCaptor.capture());
        assertThat(accountCaptor.getValue().getBalance()).isEqualTo(120L);
        assertThat(accountCaptor.getValue().getTotalGifted()).isEqualTo(100L);
        assertThat(transactionCaptor.getValue().getTransactionType()).isEqualTo(PointTransactionTypeDict.GIFT.getCode());
        assertThat(transactionCaptor.getValue().getSceneCode()).isEqualTo(PointSceneCodeDict.MANUAL_ADMIN_GRANT.getCode());
        assertThat(transactionCaptor.getValue().getPointsChange()).isEqualTo(100L);
        assertThat(transactionCaptor.getValue().getBalanceBefore()).isEqualTo(20L);
        assertThat(transactionCaptor.getValue().getBalanceAfter()).isEqualTo(120L);
        assertThat(transactionCaptor.getValue().getIdempotencyKey()).isEqualTo("manual-grant-1");
        assertThat(response.getTransactionId()).isEqualTo(88L);
        assertThat(response.getBalanceAfter()).isEqualTo(120L);
        assertThat(response.isIdempotent()).isFalse();
    }

    @Test
    void grantPointsReturnsExistingTransactionForIdempotencyKey() {
        activeUser(7L);
        PointTransactionEntity existing = transaction(88L, 10L, 7L, 100L, 20L, 120L);
        existing.setIdempotencyKey("manual-grant-1");
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(existing);

        PointMutationResponse response = service().grantPoints(7L, 100L, "manual-grant-1", "后台补分");

        assertThat(response.getTransactionId()).isEqualTo(88L);
        assertThat(response.getBalanceAfter()).isEqualTo(120L);
        assertThat(response.isIdempotent()).isTrue();
        verify(pointAccountEntityMapper, never()).updateById(any(PointAccountEntity.class));
        verify(pointTransactionEntityMapper, never()).insert(any(PointTransactionEntity.class));
    }

    @Test
    void grantPointsReturnsExistingTransactionBilledUnitsFromSnapshot() {
        activeUser(7L);
        PointTransactionEntity existing = transaction(88L, 10L, 7L, -5L, 30L, 25L);
        existing.setIdempotencyKey("UPLOAD_IMAGE:batch-1");
        existing.setCalculationSnapshot("{\"calcMode\":\"FIXED_PER_ACTION\",\"billedUnits\":5}");
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(existing);

        PointMutationResponse response = service().grantPoints(7L, 100L, "UPLOAD_IMAGE:batch-1", "幂等命中");

        assertThat(response.getTransactionId()).isEqualTo(88L);
        assertThat(response.getBilledUnits()).isEqualTo(5L);
        assertThat(response.isIdempotent()).isTrue();
        verify(pointAccountEntityMapper, never()).updateById(any(PointAccountEntity.class));
        verify(pointTransactionEntityMapper, never()).insert(any(PointTransactionEntity.class));
    }

    @Test
    void consumeFixedRuleDeductsBalanceAndWritesTransaction() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                1L,
                PointSceneCodeDict.CREATE_TEAM,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1000L
        )));
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 1200L));
        when(pointAccountEntityMapper.updateById(any(PointAccountEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            PointTransactionEntity transaction = invocation.getArgument(0);
            transaction.setId(90L);
            return 1;
        }).when(pointTransactionEntityMapper).insert(any(PointTransactionEntity.class));

        PointMutationResponse response = service().consume(
                7L,
                PointSceneCodeDict.CREATE_TEAM.getCode(),
                "TEAM",
                "100",
                1,
                "CREATE_TEAM:100",
                "新建团队扣除积分"
        );

        ArgumentCaptor<PointAccountEntity> accountCaptor = ArgumentCaptor.forClass(PointAccountEntity.class);
        ArgumentCaptor<PointTransactionEntity> transactionCaptor = ArgumentCaptor.forClass(PointTransactionEntity.class);
        verify(pointAccountEntityMapper).updateById(accountCaptor.capture());
        verify(pointTransactionEntityMapper).insert(transactionCaptor.capture());
        assertThat(accountCaptor.getValue().getBalance()).isEqualTo(200L);
        assertThat(accountCaptor.getValue().getTotalConsumed()).isEqualTo(1000L);
        assertThat(transactionCaptor.getValue().getTransactionType()).isEqualTo(PointTransactionTypeDict.CONSUMPTION.getCode());
        assertThat(transactionCaptor.getValue().getPointsChange()).isEqualTo(-1000L);
        assertThat(transactionCaptor.getValue().getBusinessType()).isEqualTo("TEAM");
        assertThat(transactionCaptor.getValue().getBusinessId()).isEqualTo("100");
        assertThat(response.getBalanceAfter()).isEqualTo(200L);
        assertThat(response.isCharged()).isTrue();
    }

    @Test
    void consumeWritesLowBalanceMessageWhenBalanceAfterBelowThreshold() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                1L,
                PointSceneCodeDict.UPLOAD_IMAGE,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1L
        )));
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 50L));
        when(pointAccountEntityMapper.updateById(any(PointAccountEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            PointTransactionEntity transaction = invocation.getArgument(0);
            transaction.setId(90L);
            return 1;
        }).when(pointTransactionEntityMapper).insert(any(PointTransactionEntity.class));

        PointMutationResponse response = service().consume(
                7L,
                PointSceneCodeDict.UPLOAD_IMAGE.getCode(),
                "WORK",
                "100",
                1,
                "UPLOAD_IMAGE:100",
                "上传图片扣除积分"
        );

        ArgumentCaptor<SystemMessageEntity> messageCaptor = ArgumentCaptor.forClass(SystemMessageEntity.class);
        verify(systemMessageEntityMapper).insert(messageCaptor.capture());
        assertThat(response.getBalanceAfter()).isEqualTo(49L);
        assertThat(messageCaptor.getValue().getUserId()).isEqualTo(7L);
        assertThat(messageCaptor.getValue().getMessageType()).isEqualTo(MessageTypeDict.POINT_LOW_BALANCE.getCode());
        assertThat(messageCaptor.getValue().getCategory()).isEqualTo(MessageCategoryDict.POINT.getCode());
        assertThat(messageCaptor.getValue().getReadStatus()).isEqualTo(MessageReadStatusDict.UNREAD.getCode());
        assertThat(messageCaptor.getValue().getActionType()).isEqualTo(MessageActionTypeDict.POINT_RECHARGE.getCode());
        assertThat(messageCaptor.getValue().getBizType()).isEqualTo("POINT_TRANSACTION");
        assertThat(messageCaptor.getValue().getBizId()).isEqualTo(90L);
        assertThat(messageCaptor.getValue().getIdempotencyKey()).isEqualTo("POINT_LOW_BALANCE:90");
    }

    @Test
    void consumeDoesNotWriteLowBalanceMessageWhenBalanceAfterEqualsThreshold() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                1L,
                PointSceneCodeDict.UPLOAD_IMAGE,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1L
        )));
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 51L));
        when(pointAccountEntityMapper.updateById(any(PointAccountEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            PointTransactionEntity transaction = invocation.getArgument(0);
            transaction.setId(91L);
            return 1;
        }).when(pointTransactionEntityMapper).insert(any(PointTransactionEntity.class));

        PointMutationResponse response = service().consume(
                7L,
                PointSceneCodeDict.UPLOAD_IMAGE.getCode(),
                "WORK",
                "100",
                1,
                "UPLOAD_IMAGE:101",
                "上传图片扣除积分"
        );

        assertThat(response.getBalanceAfter()).isEqualTo(50L);
        verify(systemMessageEntityMapper, never()).insert(any(SystemMessageEntity.class));
    }

    @Test
    void consumeFixedRuleRejectsInsufficientBalance() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                1L,
                PointSceneCodeDict.CREATE_TEAM,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1000L
        )));
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 999L));

        assertThatThrownBy(() -> service().consume(
                7L,
                PointSceneCodeDict.CREATE_TEAM.getCode(),
                "TEAM",
                "100",
                1,
                "CREATE_TEAM:100",
                "新建团队扣除积分"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额不足，请充值后再试");
        verify(pointAccountEntityMapper, never()).updateById(any(PointAccountEntity.class));
        verify(pointTransactionEntityMapper, never()).insert(any(PointTransactionEntity.class));
    }

    @Test
    void assertCanConsumeRejectsInsufficientBalanceWithoutWriting() {
        activeUser(7L);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                1L,
                PointSceneCodeDict.CREATE_TEAM,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1000L
        )));
        when(pointAccountEntityMapper.selectOne(any())).thenReturn(account(10L, 7L, 999L));

        assertThatThrownBy(() -> service().assertCanConsume(7L, PointSceneCodeDict.CREATE_TEAM.getCode(), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分余额不足，请充值后再试");
        verify(pointAccountEntityMapper, never()).selectByUserIdForUpdate(any());
        verify(pointAccountEntityMapper, never()).updateById(any(PointAccountEntity.class));
        verify(pointTransactionEntityMapper, never()).insert(any(PointTransactionEntity.class));
    }

    @Test
    void assertCanConsumeAllowsEnoughBalanceWithoutWriting() {
        activeUser(7L);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                1L,
                PointSceneCodeDict.CREATE_TEAM,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1000L
        )));
        when(pointAccountEntityMapper.selectOne(any())).thenReturn(account(10L, 7L, 1000L));

        service().assertCanConsume(7L, PointSceneCodeDict.CREATE_TEAM.getCode(), 1);

        verify(pointAccountEntityMapper, never()).selectByUserIdForUpdate(any());
        verify(pointAccountEntityMapper, never()).updateById(any(PointAccountEntity.class));
        verify(pointTransactionEntityMapper, never()).insert(any(PointTransactionEntity.class));
    }

    @Test
    void calculateAccumulatedThresholdUsesExistingPendingCount() {
        activeUser(7L);
        PointCalculationRequest request = new PointCalculationRequest();
        request.setSceneCode(PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode());
        request.setActionCount(1);
        request.setBusinessType("PORTFOLIO");
        request.setBusinessId("200");
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                8L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES,
                PointCalcModeDict.ACCUMULATED_THRESHOLD,
                10,
                1L
        )));
        when(pointAccountEntityMapper.selectOne(any())).thenReturn(account(10L, 7L, 30L));
        PointMeterEntity meter = new PointMeterEntity();
        meter.setPendingCount(9);
        meter.setTotalCount(19L);
        meter.setTotalBilledUnits(1L);
        when(pointMeterEntityMapper.selectOne(any())).thenReturn(meter);

        PointCalculationResponse response = service().calculate(7L, request);

        assertThat(response.getSceneCode()).isEqualTo(PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode());
        assertThat(response.getCalcMode()).isEqualTo(PointCalcModeDict.ACCUMULATED_THRESHOLD.getCode());
        assertThat(response.getPendingCountBefore()).isEqualTo(9);
        assertThat(response.getPendingCountAfter()).isZero();
        assertThat(response.getBilledUnits()).isEqualTo(1L);
        assertThat(response.getPointsChange()).isEqualTo(-1L);
    }

    @Test
    void consumeAccumulatedRuleReloadsMeterWhenConcurrentInsertWins() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                8L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES,
                PointCalcModeDict.ACCUMULATED_THRESHOLD,
                10,
                1L
        )));
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 30L));
        when(pointAccountEntityMapper.updateById(any(PointAccountEntity.class))).thenReturn(1);
        PointMeterEntity existingMeter = meter(30L, 10L, 7L, PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES, 9, 9L, 0L);
        when(pointMeterEntityMapper.selectMeterForUpdate(10L, PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(), "PORTFOLIO", "200"))
                .thenReturn(null, existingMeter);
        doThrow(new DuplicateKeyException("duplicate meter"))
                .when(pointMeterEntityMapper).insert(any(PointMeterEntity.class));
        when(pointMeterEntityMapper.updateById(any(PointMeterEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            PointTransactionEntity transaction = invocation.getArgument(0);
            transaction.setId(91L);
            return 1;
        }).when(pointTransactionEntityMapper).insert(any(PointTransactionEntity.class));

        PointMutationResponse response = service().consume(
                7L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(),
                "PORTFOLIO",
                "200",
                1,
                "VIEW_PORTFOLIO_IMAGES:200:visitor-1:10",
                "查看图片扣除积分"
        );

        ArgumentCaptor<PointMeterEntity> meterCaptor = ArgumentCaptor.forClass(PointMeterEntity.class);
        verify(pointMeterEntityMapper).updateById(meterCaptor.capture());
        assertThat(meterCaptor.getValue().getPendingCount()).isZero();
        assertThat(meterCaptor.getValue().getTotalCount()).isEqualTo(10L);
        assertThat(meterCaptor.getValue().getTotalBilledUnits()).isEqualTo(1L);
        assertThat(response.getTransactionId()).isEqualTo(91L);
        assertThat(response.getBilledUnits()).isEqualTo(1L);
        assertThat(response.getBalanceAfter()).isEqualTo(29L);
    }

    @Test
    void consumeAccumulatedRuleRejectsMeterOptimisticLockFailure() {
        activeUser(7L);
        when(pointTransactionEntityMapper.selectOne(any())).thenReturn(null);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(rule(
                8L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES,
                PointCalcModeDict.ACCUMULATED_THRESHOLD,
                10,
                1L
        )));
        when(pointAccountEntityMapper.selectByUserIdForUpdate(7L)).thenReturn(account(10L, 7L, 30L));
        when(pointMeterEntityMapper.selectMeterForUpdate(10L, PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(), "PORTFOLIO", "200"))
                .thenReturn(meter(30L, 10L, 7L, PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES, 9, 9L, 0L));
        when(pointMeterEntityMapper.updateById(any(PointMeterEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service().consume(
                7L,
                PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(),
                "PORTFOLIO",
                "200",
                1,
                "VIEW_PORTFOLIO_IMAGES:200:visitor-1:10",
                "查看图片扣除积分"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分计量器更新失败，请重试");
        verify(pointAccountEntityMapper, never()).updateById(any(PointAccountEntity.class));
        verify(pointTransactionEntityMapper, never()).insert(any(PointTransactionEntity.class));
    }

    @Test
    void getOverviewKeepsOnlyLatestActiveRulePerScene() {
        activeUser(7L);
        when(pointAccountEntityMapper.selectOne(any())).thenReturn(account(10L, 7L, 30L));
        PointRuleEntity oldRule = rule(
                1L,
                PointSceneCodeDict.UPLOAD_IMAGE,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                1L
        );
        oldRule.setRuleVersion(1);
        PointRuleEntity latestRule = rule(
                2L,
                PointSceneCodeDict.UPLOAD_IMAGE,
                PointCalcModeDict.FIXED_PER_ACTION,
                1,
                2L
        );
        latestRule.setRuleVersion(2);
        when(pointRuleEntityMapper.selectList(any())).thenReturn(List.of(latestRule, oldRule));

        MinePointOverviewResponse response = service().getOverview(7L);

        assertThat(response.getRules()).hasSize(1);
        assertThat(response.getRules().get(0).getRuleId()).isEqualTo(2L);
        assertThat(response.getRules().get(0).getPointsValue()).isEqualTo(2L);
    }

    /**
     * 构造被测积分服务。
     *
     * @return 积分服务
     */
    private PointService service() {
        return new PointService(
                userEntityMapper,
                pointAccountEntityMapper,
                pointRuleEntityMapper,
                pointMeterEntityMapper,
                pointTransactionEntityMapper,
                systemMessageEntityMapper
        );
    }

    /**
     * 模拟启用用户。
     *
     * @param userId 用户 ID
     */
    private void activeUser(Long userId) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userEntityMapper.selectById(userId)).thenReturn(user);
    }

    /**
     * 构造积分账户。
     *
     * @param accountId 账户 ID
     * @param userId 用户 ID
     * @param balance 当前余额
     * @return 积分账户
     */
    private PointAccountEntity account(Long accountId, Long userId, Long balance) {
        PointAccountEntity account = new PointAccountEntity();
        account.setId(accountId);
        account.setUserId(userId);
        account.setBalance(balance);
        account.setTotalRecharged(0L);
        account.setTotalGifted(0L);
        account.setTotalConsumed(0L);
        account.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        account.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        return account;
    }

    /**
     * 构造积分规则。
     *
     * @param id 规则 ID
     * @param scene 场景
     * @param calcMode 计算模式
     * @param unitCount 计费单位次数
     * @param pointsValue 单位积分值
     * @return 积分规则
     */
    private PointRuleEntity rule(
            Long id,
            PointSceneCodeDict.DictValue scene,
            PointCalcModeDict calcMode,
            Integer unitCount,
            Long pointsValue
    ) {
        PointRuleEntity rule = new PointRuleEntity();
        rule.setId(id);
        rule.setRuleCode(scene.getCode());
        rule.setRuleVersion(1);
        rule.setRuleName(scene.getDisplayName());
        rule.setTransactionType(PointTransactionTypeDict.CONSUMPTION.getCode());
        rule.setSceneCode(scene.getCode());
        rule.setCalcMode(calcMode.getCode());
        rule.setUnitCount(unitCount);
        rule.setPointsValue(pointsValue);
        rule.setStatus(PointRuleStatusDict.ACTIVE.getCode());
        return rule;
    }

    /**
     * 构造积分计量器。
     *
     * @param id 计量器 ID
     * @param accountId 账户 ID
     * @param userId 用户 ID
     * @param scene 场景
     * @param pendingCount 待计费余数
     * @param totalCount 累计次数
     * @param totalBilledUnits 已计费单位数
     * @return 积分计量器
     */
    private PointMeterEntity meter(
            Long id,
            Long accountId,
            Long userId,
            PointSceneCodeDict.DictValue scene,
            Integer pendingCount,
            Long totalCount,
            Long totalBilledUnits
    ) {
        PointMeterEntity meter = new PointMeterEntity();
        meter.setId(id);
        meter.setAccountId(accountId);
        meter.setUserId(userId);
        meter.setRuleCode(scene.getCode());
        meter.setAppliedRuleId(id);
        meter.setBusinessType("PORTFOLIO");
        meter.setBusinessId("200");
        meter.setPendingCount(pendingCount);
        meter.setTotalCount(totalCount);
        meter.setTotalBilledUnits(totalBilledUnits);
        meter.setVersion(0);
        meter.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        meter.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        return meter;
    }

    /**
     * 构造积分流水。
     *
     * @param id 流水 ID
     * @param accountId 账户 ID
     * @param userId 用户 ID
     * @param pointsChange 变动积分
     * @param balanceBefore 变动前余额
     * @param balanceAfter 变动后余额
     * @return 积分流水
     */
    private PointTransactionEntity transaction(
            Long id,
            Long accountId,
            Long userId,
            Long pointsChange,
            Long balanceBefore,
            Long balanceAfter
    ) {
        PointTransactionEntity transaction = new PointTransactionEntity();
        transaction.setId(id);
        transaction.setAccountId(accountId);
        transaction.setUserId(userId);
        transaction.setPointsChange(pointsChange);
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setOccurredAt(LocalDateTime.of(2026, 6, 18, 12, 0));
        return transaction;
    }
}
