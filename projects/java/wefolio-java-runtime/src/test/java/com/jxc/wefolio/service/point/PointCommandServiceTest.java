package com.jxc.wefolio.service.point;

import com.jxc.wefolio.exception.InvalidPointAmountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 统一积分命令入口测试 — 非正扣除金额不得进入事务服务、用户锁或 Mapper 链路。
 */
@ExtendWith(MockitoExtension.class)
class PointCommandServiceTest {

    /** 积分命令事务服务模拟。 */
    @Mock
    private PointCommandTransactionService transactionService;

    /** 被测统一入口。 */
    private PointCommandService service;

    @BeforeEach
    void setUp() {
        service = new PointCommandService(transactionService);
    }

    @Test
    void allDebitEntrypointsShouldRejectZeroBeforeTransactionService() {
        DebitCommand command = command(0L);

        assertThatThrownBy(() -> service.deductForMaintainer(command))
                .isInstanceOf(InvalidPointAmountException.class);
        assertThatThrownBy(() -> service.deductForVisitor(command))
                .isInstanceOf(InvalidPointAmountException.class);
        assertThatThrownBy(() -> service.deductForSystem(command))
                .isInstanceOf(InvalidPointAmountException.class);
        verifyNoInteractions(transactionService);
    }

    @Test
    void allDebitEntrypointsShouldRejectNegativeAmountBeforeTransactionService() {
        DebitCommand command = command(-1L);

        assertThatThrownBy(() -> service.deductForMaintainer(command))
                .isInstanceOf(InvalidPointAmountException.class);
        assertThatThrownBy(() -> service.deductForVisitor(command))
                .isInstanceOf(InvalidPointAmountException.class);
        assertThatThrownBy(() -> service.deductForSystem(command))
                .isInstanceOf(InvalidPointAmountException.class);
        verifyNoInteractions(transactionService);
    }

    /** 创建合法身份、指定金额的扣除命令。 */
    private DebitCommand command(long points) {
        return new DebitCommand(
                7L,
                3L,
                "UPLOAD_IMAGE",
                "WORK_UPLOAD",
                "101",
                "{}",
                "WORK_UPLOAD:101",
                "上传图片作品",
                points
        );
    }
}
