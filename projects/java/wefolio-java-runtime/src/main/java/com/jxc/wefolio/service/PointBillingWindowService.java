package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.BillingWindowScopeDict;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointBillingWindowEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointBillingWindowEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 访客积分滚动扣费窗口服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointBillingWindowService {

    /** 滚动扣费窗口小时数 */
    private static final long BILLING_WINDOW_HOURS = 2L;

    /** 活动记录逻辑删除值 */
    private static final long ACTIVE_DELETED_VALUE = 0L;

    /** 窗口 Mapper */
    private final PointBillingWindowEntityMapper pointBillingWindowEntityMapper;

    /** 积分服务 */
    private final PointService pointService;

    /**
     * 窗口到期时扣除一次积分，窗口内只返回跳过结果。
     *
     * @param userId 被扣费维护者用户 ID
     * @param visitorId 全局访客 ID
     * @param sceneCode 积分场景
     * @param scopeType 作用域类型
     * @param scopeId 作品集 ID 或作品 ID
     * @param businessType 积分业务类型
     * @param businessId 积分业务 ID
     * @param idempotencyKey 积分流水幂等键
     * @param remark 积分流水备注
     * @return 窗口处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public BillingWindowResult consumeIfEligible(
            Long userId,
            Long visitorId,
            String sceneCode,
            String scopeType,
            Long scopeId,
            String businessType,
            String businessId,
            String idempotencyKey,
            String remark
    ) {
        Long normalizedUserId = requirePositive(userId, "维护者用户 ID 必须为正数");
        Long normalizedVisitorId = requirePositive(visitorId, "访客 ID 必须为正数");
        Long normalizedScopeId = requirePositive(scopeId, "扣费窗口作用域 ID 必须为正数");
        String normalizedSceneCode = requireText(sceneCode, "积分场景不能为空");
        String normalizedScopeType = requireText(scopeType, "扣费窗口作用域不能为空");
        String normalizedBusinessType = requireText(businessType, "业务类型不能为空");
        String normalizedBusinessId = requireText(businessId, "业务 ID 不能为空");
        String normalizedIdempotencyKey = requireText(idempotencyKey, "幂等键不能为空");
        String normalizedRemark = requireText(remark, "积分流水备注不能为空");
        validateSceneScope(normalizedSceneCode, normalizedScopeType);

        PointBillingWindowEntity identity = new PointBillingWindowEntity();
        identity.setUserId(normalizedUserId);
        identity.setVisitorId(normalizedVisitorId);
        identity.setSceneCode(normalizedSceneCode);
        identity.setScopeType(normalizedScopeType);
        identity.setScopeId(normalizedScopeId);

        int inserted = pointBillingWindowEntityMapper.insertIgnore(identity);
        if (inserted != 0 && inserted != 1) {
            logWindowError("窗口占位记录影响行数异常", identity, inserted);
            throw windowException();
        }

        PointBillingWindowEntity window = pointBillingWindowEntityMapper.selectForUpdateByUniqueKey(
                normalizedUserId,
                normalizedSceneCode,
                normalizedVisitorId,
                normalizedScopeType,
                normalizedScopeId
        );
        if (window == null) {
            logWindowError("窗口占位后未查询到活动记录", identity, inserted);
            throw windowException();
        }

        LocalDateTime databaseNow = pointBillingWindowEntityMapper.selectCurrentTimestamp();
        if (databaseNow == null) {
            logWindowError("数据库当前时间为空", identity, inserted);
            throw windowException();
        }
        if (window.getLastChargedAt() != null
                && databaseNow.isBefore(window.getLastChargedAt().plusHours(BILLING_WINDOW_HOURS))) {
            return BillingWindowResult.SKIPPED_WITHIN_WINDOW;
        }

        PointMutationResponse mutation = pointService.consume(
                normalizedUserId,
                normalizedSceneCode,
                normalizedBusinessType,
                normalizedBusinessId,
                1,
                normalizedIdempotencyKey,
                normalizedRemark
        );
        if (!isSuccessfulMutation(mutation)) {
            logWindowError("积分扣费响应缺少有效账户或流水", identity, inserted);
            throw windowException();
        }
        int updated = pointBillingWindowEntityMapper.updateCharged(
                window.getId(),
                mutation.getAccountId(),
                mutation.getTransactionId(),
                databaseNow
        );
        if (updated != 1) {
            logWindowError("积分扣费窗口推进失败", identity, inserted);
            throw windowException();
        }
        return BillingWindowResult.CHARGED;
    }

    /** 校验积分场景与作用域的唯一映射。 */
    private void validateSceneScope(String sceneCode, String scopeType) {
        BillingWindowScopeDict.DictValue expectedScope = BillingWindowScopeDict.fromSceneCode(sceneCode)
                .orElseThrow(() -> new BusinessException("积分场景不支持滚动扣费窗口"));
        BillingWindowScopeDict.DictValue actualScope = BillingWindowScopeDict.fromCode(scopeType)
                .orElseThrow(() -> new BusinessException("积分扣费窗口作用域无效"));
        if (!expectedScope.getCode().equals(actualScope.getCode())) {
            throw new BusinessException("积分扣费窗口作用域与场景不匹配");
        }
    }

    /** 判断积分服务是否返回了可持久化的成功扣费结果。 */
    private boolean isSuccessfulMutation(PointMutationResponse mutation) {
        return mutation != null
                && mutation.isCharged()
                && mutation.getAccountId() != null
                && mutation.getAccountId() > 0L
                && mutation.getTransactionId() != null
                && mutation.getTransactionId() > 0L;
    }

    /** 校验正数 ID。 */
    private Long requirePositive(Long value, String message) {
        if (value == null || value <= 0L) {
            throw new BusinessException(message);
        }
        return value;
    }

    /** 校验必填文本并去除首尾空白。 */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.strip();
    }

    /** 记录可排查且不含敏感访客凭证的完整活动唯一键。 */
    private void logWindowError(String reason, PointBillingWindowEntity identity, int inserted) {
        log.error(
                "{}: userId={}, sceneCode={}, visitorId={}, scopeType={}, scopeId={}, deleted={}, insertAffectedRows={}",
                reason,
                identity.getUserId(),
                identity.getSceneCode(),
                identity.getVisitorId(),
                identity.getScopeType(),
                identity.getScopeId(),
                ACTIVE_DELETED_VALUE,
                inserted
        );
    }

    /** 构造统一窗口异常。 */
    private BusinessException windowException() {
        return new BusinessException(PointMessage.BILLING_WINDOW_ABNORMAL_MESSAGE);
    }
}
