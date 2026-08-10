package com.jxc.wefolio.service.point;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一积分命令入口结构测试 — 固定金额前置校验、事务边界和持久待扣写入顺序。
 */
class PointCommandServiceStructureTest {

    /** 积分命令入口源码。 */
    private static final Path COMMAND_SERVICE = Path.of(
            "src/main/java/com/jxc/wefolio/service/point/PointCommandService.java");

    /** 积分命令事务服务源码。 */
    private static final Path TRANSACTION_SERVICE = Path.of(
            "src/main/java/com/jxc/wefolio/service/point/PointCommandTransactionService.java");

    @Test
    void publicDebitEntrypointsShouldValidateBeforeEnteringMutexOrMapperTransaction() throws IOException {
        assertThat(COMMAND_SERVICE).exists();
        String source = Files.readString(COMMAND_SERVICE);

        assertThat(source)
                .contains("deductForMaintainer(DebitCommand command)")
                .contains("deductForVisitor(DebitCommand command)")
                .contains("deductForSystem(DebitCommand command)")
                .contains("validateDebit(command);")
                .contains("pointCommandTransactionService.deductForMaintainer(command)")
                .contains("pointCommandTransactionService.deductForVisitor(command)")
                .contains("pointCommandTransactionService.deductForSystem(command)");

        int validation = source.indexOf("validateDebit(command);");
        int firstTransactionCall = source.indexOf("pointCommandTransactionService.deductForMaintainer(command)");
        assertThat(validation).isGreaterThanOrEqualTo(0).isLessThan(firstTransactionCall);
    }

    @Test
    void transactionServiceShouldWriteAccountLedgerPendingSourceAndActiveTaskTogether() throws IOException {
        assertThat(TRANSACTION_SERVICE).exists();
        String source = Files.readString(TRANSACTION_SERVICE);

        assertThat(source)
                .contains("@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)")
                .contains("userPointMutex.execute(command.userId()")
                .contains("pointTransactionEntityMapper.insert(transaction)")
                .contains("pointPendingDebitEntityMapper.insert(pendingDebit)")
                .contains("pointDebitTaskService.ensureActiveTask(updatedAccount)")
                .contains("PointTransactionTypeDict.CONSUMPTION.getCode()")
                .contains("InsufficientPointBalanceException");
    }

    @Test
    void multiUserGiftShouldKeepAscendingRecursiveLockOrder() throws IOException {
        assertThat(TRANSACTION_SERVICE).exists();
        String source = Files.readString(TRANSACTION_SERVICE);

        assertThat(source)
                .contains(".distinct()")
                .contains(".sorted()")
                .contains("executeWithUserLocks(userIds, 0")
                .contains("userPointMutex.execute(userIds.get(index)")
                .contains("executeWithUserLocks(userIds, index + 1, action)");
    }
}
