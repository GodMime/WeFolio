package com.jxc.wefolio.service.point;

import com.jxc.wefolio.exception.InvalidPointAmountException;
import com.jxc.wefolio.message.PointMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一积分命令入口 — 在进入用户锁和事务写入前完成公共金额与身份参数校验。
 */
@Service
@RequiredArgsConstructor
public class PointCommandService {

    /** 积分命令事务服务。 */
    private final PointCommandTransactionService pointCommandTransactionService;

    /** 创建一批独立、幂等的微信代币赠送订单。 */
    public GiftOrderResult createGiftOrders(List<GiftCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new InvalidPointAmountException(PointMessage.GIFT_COMMAND_REQUIRED_MESSAGE);
        }
        for (GiftCommand command : commands) {
            validateGift(command);
        }
        try {
            return pointCommandTransactionService.createGiftOrders(commands);
        } catch (DuplicateKeyException exception) {
            return pointCommandTransactionService.recoverGiftOrders(commands);
        }
    }

    /** 按维护者语义完整扣除积分，扣除后余额不得为负。 */
    public PointMutationResult deductForMaintainer(DebitCommand command) {
        validateDebit(command);
        try {
            return pointCommandTransactionService.deductForMaintainer(command);
        } catch (DuplicateKeyException exception) {
            return pointCommandTransactionService.recoverExistingMutation(command);
        }
    }

    /** 按访客语义完整扣除积分，允许实际可用积分变为负数。 */
    public PointMutationResult deductForVisitor(DebitCommand command) {
        validateDebit(command);
        try {
            return pointCommandTransactionService.deductForVisitor(command);
        } catch (DuplicateKeyException exception) {
            return pointCommandTransactionService.recoverExistingMutation(command);
        }
    }

    /** 按后台月费语义在扣费前余额为正时完整扣除。 */
    public SystemDebitResult deductForSystem(DebitCommand command) {
        validateDebit(command);
        try {
            return pointCommandTransactionService.deductForSystem(command);
        } catch (DuplicateKeyException exception) {
            return SystemDebitResult.deducted(pointCommandTransactionService.recoverExistingMutation(command));
        }
    }

    /** 在任何锁或 Mapper 调用前校验扣除命令。 */
    private void validateDebit(DebitCommand command) {
        if (command == null || command.points() <= 0L) {
            throw new InvalidPointAmountException(PointMessage.DEBIT_POINTS_INVALID_MESSAGE);
        }
        requireIdentity(command.userId(), command.sceneCode(), command.businessType(),
                command.businessId(), command.idempotencyKey());
    }

    /** 在任何锁或 Mapper 调用前校验赠送命令。 */
    private void validateGift(GiftCommand command) {
        if (command == null || command.amount() <= 0L) {
            throw new InvalidPointAmountException(PointMessage.GIFT_POINTS_INVALID_MESSAGE);
        }
        requireIdentity(command.userId(), command.sceneCode(), command.businessType(),
                command.businessId(), command.idempotencyKey());
        if (blank(command.businessSnapshot())) {
            throw new InvalidPointAmountException(PointMessage.GIFT_SNAPSHOT_REQUIRED_MESSAGE);
        }
    }

    /** 校验积分命令的公共业务身份。 */
    private void requireIdentity(
            Long userId,
            String sceneCode,
            String businessType,
            String businessId,
            String idempotencyKey
    ) {
        if (userId == null || blank(sceneCode) || blank(businessType) || blank(businessId) || blank(idempotencyKey)) {
            throw new InvalidPointAmountException(PointMessage.POINT_COMMAND_IDENTITY_INVALID_MESSAGE);
        }
    }

    /** 判断文本是否为空白。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
