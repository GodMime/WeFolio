package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.service.MerchantOrderNoGenerator;
import com.jxc.wefolio.service.PointService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充值订单事务服务测试。
 */
@ExtendWith(MockitoExtension.class)
class RechargeOrderTransactionServiceTest {

    /** 商户订单号。 */
    private static final String MERCHANT_ORDER_NO = "WFR20260717153000123A3B7K9M2Q5R";

    /** 订单 Mapper 模拟。 */
    @Mock
    private RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 积分服务模拟。 */
    @Mock
    private PointService pointService;

    /** 订单号生成器模拟。 */
    @Mock
    private MerchantOrderNoGenerator merchantOrderNoGenerator;

    @Mock
    private PointDebitTaskService pointDebitTaskService;

    @Test
    void createPendingOrderShouldPersistImmutablePackageSnapshot() {
        when(merchantOrderNoGenerator.generate()).thenReturn(MERCHANT_ORDER_NO);
        when(rechargeOrderEntityMapper.insert(any(RechargeOrderEntity.class))).thenAnswer(invocation -> {
            RechargeOrderEntity order = invocation.getArgument(0);
            order.setId(99L);
            return 1;
        });
        LocalDateTime expireAt = LocalDateTime.of(2026, 7, 17, 15, 45);

        RechargeOrderEntity created = service().createPendingOrder(
                7L, account(), rechargePackage(), expireAt);

        ArgumentCaptor<RechargeOrderEntity> captor = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderEntityMapper).insert(captor.capture());
        assertThat(captor.getValue()).satisfies(order -> {
            assertThat(order.getMerchantOrderNo()).isEqualTo(MERCHANT_ORDER_NO);
            assertThat(order.getAccountId()).isEqualTo(10L);
            assertThat(order.getUserId()).isEqualTo(7L);
            assertThat(order.getPackageId()).isEqualTo(3L);
            assertThat(order.getAmountFen()).isEqualTo(5000);
            assertThat(order.getBasePoints()).isEqualTo(500);
            assertThat(order.getBonusPoints()).isEqualTo(20);
            assertThat(order.getTotalPoints()).isEqualTo(520);
            assertThat(order.getStatus()).isEqualTo(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
            assertThat(order.getPayChannel()).isEqualTo("WECHAT_VIRTUAL_PAYMENT");
            assertThat(order.getBuyQuantity()).isEqualTo(500L);
            assertThat(order.getExpireAt()).isEqualTo(expireAt);
            assertThat(order.getPackageSnapshot())
                    .contains("RECHARGE_50_YUAN")
                    .contains("50 元档")
                    .contains("\"totalPoints\":520");
        });
        assertThat(created.getId()).isEqualTo(99L);
    }

    @Test
    void createPendingOrderShouldRetryMerchantOrderNumberConflictAtMostThreeTimes() {
        when(merchantOrderNoGenerator.generate()).thenReturn(
                "WFR20260717153000123A3B7K9M2Q5A",
                "WFR20260717153000123A3B7K9M2Q5B",
                "WFR20260717153000123A3B7K9M2Q5C");
        when(rechargeOrderEntityMapper.insert(any(RechargeOrderEntity.class)))
                .thenThrow(new DuplicateKeyException(
                        "Duplicate entry for key 'uk_recharge_merchant_order'"));

        assertThatThrownBy(() -> service().createPendingOrder(
                7L, account(), rechargePackage(), LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("充值订单创建失败，请稍后重试");
        verify(merchantOrderNoGenerator, times(3)).generate();
        verify(rechargeOrderEntityMapper, times(3)).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void createPendingOrderShouldNotRetryOtherUniqueConflicts() {
        when(merchantOrderNoGenerator.generate()).thenReturn(MERCHANT_ORDER_NO);
        when(rechargeOrderEntityMapper.insert(any(RechargeOrderEntity.class)))
                .thenThrow(new DuplicateKeyException(
                        "Duplicate entry for key 'uk_recharge_payment_transaction'"));

        assertThatThrownBy(() -> service().createPendingOrder(
                7L, account(), rechargePackage(), LocalDateTime.now()))
                .isInstanceOf(DuplicateKeyException.class);
        verify(merchantOrderNoGenerator).generate();
        verify(rechargeOrderEntityMapper).insert(any(RechargeOrderEntity.class));
    }

    /** 虚拟支付确认迟到时用权威余额结算，订单重放不会重复充值。 */
    @Test
    void virtualSettlementShouldUseAuthoritativeBalanceAndRemainIdempotent() {
        RechargeOrderEntity order = order(RechargeOrderStatusDict.CLOSED.getCode());
        order.setBuyQuantity(500L);
        order.setBonusPoints(0);
        when(rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(MERCHANT_ORDER_NO)).thenReturn(order);
        when(rechargeOrderEntityMapper.updateById(order)).thenReturn(1);
        PointMutationResponse mutation = new PointMutationResponse();
        mutation.setTransactionId(88L);
        mutation.setBalanceAfter(580L);
        when(pointService.rechargeVirtual(7L, 500L, MERCHANT_ORDER_NO, order.getPackageSnapshot(),
                "POINT_RECHARGE:" + MERCHANT_ORDER_NO, "50 元档", 600L, 50L)).thenReturn(mutation);
        WechatVirtualPaymentResult paid = new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 0L, 0L, 0L, "PAID", 500L, 5000L, 200);
        WechatVirtualPaymentResult balance = new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 600L, 50L, 0L, null, 0L, 0L, 200);

        RechargeSettlementResult first = service().settleVirtual(MERCHANT_ORDER_NO, paid, balance);
        RechargeSettlementResult replay = service().settleVirtual(MERCHANT_ORDER_NO, paid, balance);

        assertThat(first.balance()).isEqualTo(580L);
        assertThat(replay.idempotent()).isTrue();
        assertThat(order.getPointTransactionId()).isEqualTo(88L);
        verify(pointService).rechargeVirtual(7L, 500L, MERCHANT_ORDER_NO, order.getPackageSnapshot(),
                "POINT_RECHARGE:" + MERCHANT_ORDER_NO, "50 元档", 600L, 50L);
        verify(rechargeOrderEntityMapper).updateById(order);
    }

    /**
     * 创建被测服务。
     *
     * @return 充值订单事务服务
     */
    private RechargeOrderTransactionService service() {
        return new RechargeOrderTransactionService(
                rechargeOrderEntityMapper,
                pointService,
                merchantOrderNoGenerator,
                null,
                pointDebitTaskService
        );
    }

    /**
     * 构造积分账户。
     *
     * @return 积分账户
     */
    private PointAccountEntity account() {
        PointAccountEntity account = new PointAccountEntity();
        account.setId(10L);
        account.setUserId(7L);
        return account;
    }

    /**
     * 构造 50 元充值套餐。
     *
     * @return 充值套餐
     */
    private RechargePackageEntity rechargePackage() {
        RechargePackageEntity rechargePackage = new RechargePackageEntity();
        rechargePackage.setId(3L);
        rechargePackage.setPackageCode("RECHARGE_50_YUAN");
        rechargePackage.setPackageVersion(1);
        rechargePackage.setPackageName("50 元档");
        rechargePackage.setAmountFen(5000);
        rechargePackage.setBasePoints(500);
        rechargePackage.setBonusPoints(20);
        rechargePackage.setTotalPoints(520);
        return rechargePackage;
    }

    /**
     * 构造充值订单。
     *
     * @param status 订单状态
     * @return 充值订单
     */
    private RechargeOrderEntity order(String status) {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(99L);
        order.setMerchantOrderNo(MERCHANT_ORDER_NO);
        order.setAccountId(10L);
        order.setUserId(7L);
        order.setPackageId(3L);
        order.setPackageSnapshot(
                "{\"packageCode\":\"RECHARGE_50_YUAN\",\"packageVersion\":1,"
                        + "\"packageName\":\"50 元档\",\"amountFen\":5000,"
                        + "\"basePoints\":500,\"bonusPoints\":20,\"totalPoints\":520}");
        order.setAmountFen(5000);
        order.setBasePoints(500);
        order.setBonusPoints(20);
        order.setTotalPoints(520);
        order.setStatus(status);
        order.setClosedAt(LocalDateTime.of(2026, 7, 17, 15, 30));
        return order;
    }

}
