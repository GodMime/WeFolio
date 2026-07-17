package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatPayProperties;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dto.PointMutationResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.service.MerchantOrderNoGenerator;
import com.jxc.wefolio.service.PointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /** 微信支付配置。 */
    private WechatPayProperties payProperties;

    /** 微信小程序配置。 */
    private WechatMiniappProperties miniappProperties;

    @BeforeEach
    void setUp() {
        payProperties = new WechatPayProperties();
        payProperties.setMerchantId("1900000001");
        miniappProperties = new WechatMiniappProperties();
        miniappProperties.setAppId("wx-test-app-id");
    }

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
            assertThat(order.getPayChannel()).isEqualTo("WECHAT_PAY");
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

    @Test
    void settleShouldUpgradeClosedOrderToPaidAndRechargeOnce() {
        RechargeOrderEntity order = order(RechargeOrderStatusDict.CLOSED.getCode());
        when(rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(MERCHANT_ORDER_NO)).thenReturn(order);
        PointMutationResponse mutation = new PointMutationResponse();
        mutation.setBalanceAfter(806L);
        when(pointService.recharge(
                7L,
                520L,
                MERCHANT_ORDER_NO,
                order.getPackageSnapshot(),
                "POINT_RECHARGE:" + MERCHANT_ORDER_NO,
                "50 元档"
        )).thenReturn(mutation);
        when(rechargeOrderEntityMapper.updateById(order)).thenReturn(1);
        WechatPayClient.Transaction transaction = successTransaction();

        RechargeSettlementResult result = service().settle(transaction);

        assertThat(result.balance()).isEqualTo(806L);
        assertThat(result.order().getStatus()).isEqualTo(RechargeOrderStatusDict.PAID.getCode());
        assertThat(result.order().getPaymentTransactionId()).isEqualTo("4200000000001");
        assertThat(result.order().getPaidAt()).isEqualTo(LocalDateTime.of(2026, 7, 17, 15, 30, 8));
        assertThat(result.order().getClosedAt()).isNull();
        verify(pointService).recharge(
                7L,
                520L,
                MERCHANT_ORDER_NO,
                order.getPackageSnapshot(),
                "POINT_RECHARGE:" + MERCHANT_ORDER_NO,
                "50 元档"
        );
        verify(rechargeOrderEntityMapper).updateById(order);
    }

    @Test
    void settleShouldReturnIdempotentlyWhenPaidTransactionMatches() {
        RechargeOrderEntity order = order(RechargeOrderStatusDict.PAID.getCode());
        order.setPaymentTransactionId("4200000000001");
        when(rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(MERCHANT_ORDER_NO)).thenReturn(order);

        RechargeSettlementResult result = service().settle(successTransaction());

        assertThat(result.idempotent()).isTrue();
        verify(pointService, never()).recharge(any(), anyLong(), any(), any(), any(), any());
        verify(rechargeOrderEntityMapper, never()).updateById(any(RechargeOrderEntity.class));
    }

    @Test
    void settleShouldRejectAmountMismatchWithoutChangingPoints() {
        when(rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(MERCHANT_ORDER_NO))
                .thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));
        WechatPayClient.Transaction transaction = new WechatPayClient.Transaction(
                "wx-test-app-id",
                "1900000001",
                MERCHANT_ORDER_NO,
                "4200000000001",
                WechatPayClient.TradeState.SUCCESS,
                "CNY",
                1,
                LocalDateTime.of(2026, 7, 17, 15, 30, 8)
        );

        assertThatThrownBy(() -> service().settle(transaction))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付金额与充值订单不一致");
        verify(pointService, never()).recharge(any(), anyLong(), any(), any(), any(), any());
        verify(rechargeOrderEntityMapper, never()).updateById(any(RechargeOrderEntity.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mismatchedTransactions")
    void settleShouldRejectMismatchedWechatIdentityWithoutChangingPoints(
            String scenario,
            WechatPayClient.Transaction transaction,
            String expectedMessage
    ) {
        when(rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(MERCHANT_ORDER_NO))
                .thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));

        assertThatThrownBy(() -> service().settle(transaction))
                .isInstanceOf(BusinessException.class)
                .hasMessage(expectedMessage);
        verify(pointService, never()).recharge(any(), anyLong(), any(), any(), any(), any());
        verify(rechargeOrderEntityMapper, never()).updateById(any(RechargeOrderEntity.class));
    }

    @Test
    void settleShouldRejectDifferentWechatTransactionForPaidOrder() {
        RechargeOrderEntity order = order(RechargeOrderStatusDict.PAID.getCode());
        order.setPaymentTransactionId("4200000000000");
        when(rechargeOrderEntityMapper.selectForUpdateByMerchantOrderNo(MERCHANT_ORDER_NO))
                .thenReturn(order);

        assertThatThrownBy(() -> service().settle(successTransaction()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已支付订单的微信交易号不一致");
        verify(pointService, never()).recharge(any(), anyLong(), any(), any(), any(), any());
    }

    /**
     * 构造微信身份与金额不匹配场景。
     *
     * @return 参数化测试数据
     */
    private static Stream<Arguments> mismatchedTransactions() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 7, 17, 15, 30, 8);
        return Stream.of(
                Arguments.of("AppID 不匹配", new WechatPayClient.Transaction(
                        "wx-other", "1900000001", MERCHANT_ORDER_NO, "4200000000001",
                        WechatPayClient.TradeState.SUCCESS, "CNY", 5000, paidAt),
                        "微信支付 AppID 与充值订单不一致"),
                Arguments.of("商户号不匹配", new WechatPayClient.Transaction(
                        "wx-test-app-id", "1900000002", MERCHANT_ORDER_NO, "4200000000001",
                        WechatPayClient.TradeState.SUCCESS, "CNY", 5000, paidAt),
                        "微信支付商户号与充值订单不一致"),
                Arguments.of("币种不匹配", new WechatPayClient.Transaction(
                        "wx-test-app-id", "1900000001", MERCHANT_ORDER_NO, "4200000000001",
                        WechatPayClient.TradeState.SUCCESS, "USD", 5000, paidAt),
                        "微信支付币种与充值订单不一致")
        );
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
                payProperties,
                miniappProperties
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

    /**
     * 构造微信支付成功交易。
     *
     * @return 微信支付交易
     */
    private WechatPayClient.Transaction successTransaction() {
        return new WechatPayClient.Transaction(
                "wx-test-app-id",
                "1900000001",
                MERCHANT_ORDER_NO,
                "4200000000001",
                WechatPayClient.TradeState.SUCCESS,
                "CNY",
                5000,
                LocalDateTime.of(2026, 7, 17, 15, 30, 8)
        );
    }
}
