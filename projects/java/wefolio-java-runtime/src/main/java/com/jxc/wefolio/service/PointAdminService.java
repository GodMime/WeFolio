package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.AdminPointGrantRequest;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.payment.PointDebitTaskTransactionService;
import com.jxc.wefolio.service.payment.PointGiftOrderTransactionService;
import com.jxc.wefolio.service.point.GiftCommand;
import com.jxc.wefolio.service.point.GiftOrderResult;
import com.jxc.wefolio.service.point.PointCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 后台积分服务 — 人工赠送及失败任务人工重试。
 */
@Service
@RequiredArgsConstructor
public class PointAdminService {

    private static final String BUSINESS_TYPE_ADMIN_GRANT = "ADMIN_GRANT";

    private final PointCommandService pointCommandService;
    private final UserEntityMapper userEntityMapper;
    private final PointGiftOrderEntityMapper pointGiftOrderEntityMapper;
    private final PointGiftOrderTransactionService pointGiftOrderTransactionService;
    private final PointDebitTaskTransactionService pointDebitTaskTransactionService;
    private final AdminPointSecretValidator secretValidator;

    /** 创建后台人工赠送订单。 */
    public GiftOrderResult grantPoints(String secret, AdminPointGrantRequest request) {
        secretValidator.validate(secret);
        if (request == null) {
            throw new BusinessException("加分内容不能为空");
        }
        UserEntity user = findActiveUser(normalizeRequired(request.getUniqueCode(), "用户唯一码不能为空"));
        String businessId = normalizeRequired(request.getIdempotencyKey(), "幂等键不能为空");
        return pointCommandService.createGiftOrders(List.of(new GiftCommand(
                user.getId(),
                PointSceneCodeDict.MANUAL_ADMIN_GRANT.getCode(),
                request.getPoints() == null ? 0L : request.getPoints(),
                BUSINESS_TYPE_ADMIN_GRANT,
                businessId,
                JSON.toJSONString(Map.of("remark", request.getRemark() == null ? "" : request.getRemark())),
                businessId
        )));
    }

    /** 人工重试原赠送订单。 */
    public boolean retryGiftOrder(String secret, String orderNo) {
        secretValidator.validate(secret);
        PointGiftOrderEntity order = pointGiftOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointGiftOrderEntity.class)
                        .eq(PointGiftOrderEntity::getOrderNo, normalizeRequired(orderNo, "赠送订单号不能为空"))
                        .last("LIMIT 1"));
        return order != null && pointGiftOrderTransactionService.resetForManualRetry(order.getId());
    }

    /** 人工重试原扣币任务。 */
    public boolean retryDebitTask(String secret, Long taskId) {
        secretValidator.validate(secret);
        if (taskId == null) {
            throw new BusinessException("扣币任务 ID 不能为空");
        }
        return pointDebitTaskTransactionService.resetForManualRetry(taskId);
    }

    /** 查询启用用户。 */
    private UserEntity findActiveUser(String uniqueCode) {
        UserEntity user = userEntityMapper.selectOne(
                Wrappers.lambdaQuery(UserEntity.class)
                        .eq(UserEntity::getUniqueCode, uniqueCode)
                        .eq(UserEntity::getStatus, UserStatusDict.ACTIVE.getCode())
                        .last("LIMIT 1"));
        if (user == null) {
            throw new BusinessException("用户不存在或已停用");
        }
        return user;
    }

    /** 规范化必填文本。 */
    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.strip();
    }
}
