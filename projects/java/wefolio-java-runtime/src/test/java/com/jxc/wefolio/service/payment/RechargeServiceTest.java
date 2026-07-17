package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatPayProperties;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.CreateRechargeOrderRequest;
import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.RechargePackageEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.mapper.RechargePackageEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.PointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 充值应用服务测试。
 */
@ExtendWith(MockitoExtension.class)
class RechargeServiceTest {

    /** 商户订单号。 */
    private static final String MERCHANT_ORDER_NO = "WFR20260717153000123A3B7K9M2Q5R";

    /** 上海时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 用户 Mapper 模拟。 */
    @Mock
    private UserEntityMapper userEntityMapper;

    /** 用户认证 Mapper 模拟。 */
    @Mock
    private UserAuthEntityMapper userAuthEntityMapper;

    /** 充值套餐 Mapper 模拟。 */
    @Mock
    private RechargePackageEntityMapper rechargePackageEntityMapper;

    /** 充值订单 Mapper 模拟。 */
    @Mock
    private RechargeOrderEntityMapper rechargeOrderEntityMapper;

    /** 积分服务模拟。 */
    @Mock
    private PointService pointService;

    /** 订单事务服务模拟。 */
    @Mock
    private RechargeOrderTransactionService transactionService;

    /** 微信支付客户端模拟。 */
    @Mock
    private WechatPayClient wechatPayClient;

    /** 微信支付配置。 */
    private WechatPayProperties payProperties;

    /** 微信小程序配置。 */
    private WechatMiniappProperties miniappProperties;

    @BeforeEach
    void setUp() {
        payProperties = new WechatPayProperties();
        payProperties.setEnabled(true);
        payProperties.setMerchantId("1900000001");
        payProperties.setNotifyUrl(
                "https://api.we-folio.dingchenyong.top/api/payment/wechat/recharge/notify");
        payProperties.setOrderExpireMinutes(15);
        miniappProperties = new WechatMiniappProperties();
        miniappProperties.setAppId("wx-test-app-id");
    }

    @Test
    void getPageShouldReturnBalanceAndEffectivePackages() {
        PointAccountEntity account = account();
        when(pointService.ensureAccount(7L)).thenReturn(account);
        when(rechargePackageEntityMapper.selectList(any())).thenReturn(List.of(rechargePackage()));

        RechargePageResponse response = service().getPage(7L);

        assertThat(response.getBalance()).isEqualTo(286L);
        assertThat(response.isLowBalance()).isFalse();
        assertThat(response.getLowBalanceThreshold()).isEqualTo(50L);
        assertThat(response.getPackages()).singleElement().satisfies(item -> {
            assertThat(item.getPackageId()).isEqualTo(3L);
            assertThat(item.getAmountFen()).isEqualTo(5000);
            assertThat(item.getTotalPoints()).isEqualTo(520);
        });
    }

    @Test
    void createOrderShouldUseServerPackageOpenIdAndWechatPaymentParameters() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(userAuthEntityMapper.selectOne(any())).thenReturn(wechatAuth());
        when(rechargePackageEntityMapper.selectOne(any())).thenReturn(rechargePackage());
        when(pointService.ensureAccount(7L)).thenReturn(account());
        RechargeOrderEntity pendingOrder = order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
        when(transactionService.createPendingOrder(
                org.mockito.ArgumentMatchers.eq(7L),
                any(PointAccountEntity.class),
                any(RechargePackageEntity.class),
                any(LocalDateTime.class)
        )).thenReturn(pendingOrder);
        when(wechatPayClient.prepay(any())).thenReturn(new WechatPayClient.PrepayResult(
                "wx-prepay-1", "1784273400", "nonce", "prepay_id=wx-prepay-1", "RSA", "pay-sign"));

        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        request.setPackageId(3L);
        CreateRechargeOrderResponse response = service().createOrder(7L, request);

        ArgumentCaptor<WechatPayClient.PrepayCommand> captor =
                ArgumentCaptor.forClass(WechatPayClient.PrepayCommand.class);
        verify(wechatPayClient).prepay(captor.capture());
        assertThat(captor.getValue()).satisfies(command -> {
            assertThat(command.appId()).isEqualTo("wx-test-app-id");
            assertThat(command.merchantId()).isEqualTo("1900000001");
            assertThat(command.payerOpenId()).isEqualTo("openid-1");
            assertThat(command.amountFen()).isEqualTo(5000);
            assertThat(command.merchantOrderNo()).isEqualTo(MERCHANT_ORDER_NO);
            assertThat(command.description()).isEqualTo("映期Folio-50 元档");
        });
        verify(transactionService).markPrepayReady(MERCHANT_ORDER_NO, "wx-prepay-1");
        assertThat(response.getMerchantOrderNo()).isEqualTo(MERCHANT_ORDER_NO);
        assertThat(response.getPackageValue()).isEqualTo("prepay_id=wx-prepay-1");
        assertThat(response.getPaySign()).isEqualTo("pay-sign");
    }

    @Test
    void createOrderShouldUseShanghaiExpiryWhenJvmDefaultTimezoneIsUtc() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
            when(userAuthEntityMapper.selectOne(any())).thenReturn(wechatAuth());
            when(rechargePackageEntityMapper.selectOne(any())).thenReturn(rechargePackage());
            when(pointService.ensureAccount(7L)).thenReturn(account());
            when(transactionService.createPendingOrder(
                    org.mockito.ArgumentMatchers.eq(7L),
                    any(PointAccountEntity.class),
                    any(RechargePackageEntity.class),
                    any(LocalDateTime.class)
            )).thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));
            when(wechatPayClient.prepay(any())).thenReturn(new WechatPayClient.PrepayResult(
                    "wx-prepay-1", "1784273400", "nonce", "prepay_id=wx-prepay-1", "RSA", "pay-sign"));
            CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
            request.setPackageId(3L);
            LocalDateTime earliestExpiry = LocalDateTime.now(SHANGHAI_ZONE)
                    .plusMinutes(payProperties.getOrderExpireMinutes())
                    .minusSeconds(1);

            service().createOrder(7L, request);

            LocalDateTime latestExpiry = LocalDateTime.now(SHANGHAI_ZONE)
                    .plusMinutes(payProperties.getOrderExpireMinutes())
                    .plusSeconds(1);
            ArgumentCaptor<LocalDateTime> expiryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(transactionService).createPendingOrder(
                    org.mockito.ArgumentMatchers.eq(7L),
                    any(PointAccountEntity.class),
                    any(RechargePackageEntity.class),
                    expiryCaptor.capture()
            );
            assertThat(expiryCaptor.getValue()).isBetween(earliestExpiry, latestExpiry);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void createOrderShouldRejectDisabledPaymentBeforeWritingLocalOrder() {
        payProperties.setEnabled(false);
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        request.setPackageId(3L);

        assertThatThrownBy(() -> service().createOrder(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付暂未配置，请稍后再试");
        verifyNoInteractions(userEntityMapper, userAuthEntityMapper, rechargePackageEntityMapper,
                rechargeOrderEntityMapper, pointService, transactionService, wechatPayClient);
    }

    @Test
    void createOrderShouldMarkLocalOrderFailedWhenWechatPrepayFails() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(userAuthEntityMapper.selectOne(any())).thenReturn(wechatAuth());
        when(rechargePackageEntityMapper.selectOne(any())).thenReturn(rechargePackage());
        when(pointService.ensureAccount(7L)).thenReturn(account());
        when(transactionService.createPendingOrder(
                org.mockito.ArgumentMatchers.eq(7L), any(), any(), any()))
                .thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));
        when(wechatPayClient.prepay(any())).thenThrow(new RuntimeException("remote detail"));
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        request.setPackageId(3L);

        assertThatThrownBy(() -> service().createOrder(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付下单失败，请稍后重试")
                .hasMessageNotContaining("remote detail");
        verify(transactionService).markPrepayFailed(MERCHANT_ORDER_NO);
        verify(transactionService, never()).markPrepayReady(any(), any());
    }

    @Test
    void createOrderShouldKeepReadablePrepayErrorWhenFailureMarkAlsoFails() {
        when(userEntityMapper.selectById(7L)).thenReturn(activeUser());
        when(userAuthEntityMapper.selectOne(any())).thenReturn(wechatAuth());
        when(rechargePackageEntityMapper.selectOne(any())).thenReturn(rechargePackage());
        when(pointService.ensureAccount(7L)).thenReturn(account());
        when(transactionService.createPendingOrder(
                org.mockito.ArgumentMatchers.eq(7L), any(), any(), any()))
                .thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));
        when(wechatPayClient.prepay(any())).thenThrow(new RuntimeException("remote detail"));
        doThrow(new RuntimeException("database detail"))
                .when(transactionService).markPrepayFailed(MERCHANT_ORDER_NO);
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        request.setPackageId(3L);

        assertThatThrownBy(() -> service().createOrder(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付下单失败，请稍后重试")
                .hasMessageNotContaining("database detail");
    }

    @Test
    void listOrdersShouldReturnOnlyMappedCurrentUserPage() {
        Page<RechargeOrderEntity> page = new Page<>(1, 20);
        page.setTotal(1L);
        page.setRecords(List.of(order(RechargeOrderStatusDict.PAID.getCode())));
        when(rechargeOrderEntityMapper.selectPage(any(), any())).thenReturn(page);

        RechargeOrdersResponse response = service().listOrders(7L, 1, 20);

        assertThat(response.getTotal()).isEqualTo(1L);
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.getMerchantOrderNo()).isEqualTo(MERCHANT_ORDER_NO);
            assertThat(item.getPackageName()).isEqualTo("50 元档");
            assertThat(item.getStatusText()).isEqualTo("充值成功");
            assertThat(item.getAmountFen()).isEqualTo(5000);
        });
    }

    @Test
    void syncOrderShouldSettleSuccessfulWechatTransaction() {
        RechargeOrderEntity pendingOrder = order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
        when(rechargeOrderEntityMapper.selectOne(any())).thenReturn(pendingOrder);
        WechatPayClient.Transaction transaction = new WechatPayClient.Transaction(
                "wx-test-app-id", "1900000001", MERCHANT_ORDER_NO, "4200000000001",
                WechatPayClient.TradeState.SUCCESS, "CNY", 5000,
                LocalDateTime.of(2026, 7, 17, 15, 30, 8));
        when(wechatPayClient.queryByMerchantOrderNo(MERCHANT_ORDER_NO)).thenReturn(transaction);
        RechargeOrderEntity paidOrder = order(RechargeOrderStatusDict.PAID.getCode());
        when(transactionService.settle(transaction))
                .thenReturn(new RechargeSettlementResult(paidOrder, 806L, false));

        RechargeOrderSyncResponse response = service().syncOrder(7L, MERCHANT_ORDER_NO);

        assertThat(response.getStatus()).isEqualTo(RechargeOrderStatusDict.PAID.getCode());
        assertThat(response.getBalance()).isEqualTo(806L);
        assertThat(response.isConfirmed()).isTrue();
        verify(transactionService).settle(transaction);
    }

    @Test
    void syncOrderShouldRejectBlankMerchantOrderNumberBeforeQuery() {
        assertThatThrownBy(() -> service().syncOrder(7L, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商户订单号不能为空");
        verifyNoInteractions(rechargeOrderEntityMapper, wechatPayClient, transactionService);
    }

    @Test
    void syncOrderShouldReturnReadableErrorWhenWechatQueryTemporarilyFails() {
        when(rechargeOrderEntityMapper.selectOne(any()))
                .thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));
        when(wechatPayClient.queryByMerchantOrderNo(MERCHANT_ORDER_NO))
                .thenThrow(new RuntimeException("remote request detail"));

        assertThatThrownBy(() -> service().syncOrder(7L, MERCHANT_ORDER_NO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付订单查询失败，请稍后在充值记录中查看")
                .hasMessageNotContaining("remote request detail");
        verify(transactionService, never()).settle(any());
        verify(transactionService, never()).markTerminalState(any(), any(), any());
    }

    @Test
    void syncOrderShouldRejectWechatResponseForDifferentMerchantOrder() {
        when(rechargeOrderEntityMapper.selectOne(any()))
                .thenReturn(order(RechargeOrderStatusDict.PENDING_PAYMENT.getCode()));
        WechatPayClient.Transaction differentOrder = new WechatPayClient.Transaction(
                "wx-test-app-id", "1900000001", "WFR20260717153000123A3B7K9M2Q5X",
                "4200000000001", WechatPayClient.TradeState.SUCCESS, "CNY", 5000,
                LocalDateTime.of(2026, 7, 17, 15, 30, 8));
        when(wechatPayClient.queryByMerchantOrderNo(MERCHANT_ORDER_NO)).thenReturn(differentOrder);

        assertThatThrownBy(() -> service().syncOrder(7L, MERCHANT_ORDER_NO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付返回的商户订单号不一致");
        verify(transactionService, never()).settle(any());
        verify(transactionService, never()).markTerminalState(any(), any(), any());
    }

    /**
     * 创建被测服务。
     *
     * @return 充值服务
     */
    private RechargeService service() {
        return new RechargeService(
                payProperties,
                miniappProperties,
                userEntityMapper,
                userAuthEntityMapper,
                rechargePackageEntityMapper,
                rechargeOrderEntityMapper,
                pointService,
                transactionService,
                wechatPayClient
        );
    }

    /**
     * 构造启用用户。
     *
     * @return 用户
     */
    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        return user;
    }

    /**
     * 构造维护者微信身份。
     *
     * @return 微信身份
     */
    private UserAuthEntity wechatAuth() {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(7L);
        auth.setOpenId("openid-1");
        auth.setStatus("ACTIVE");
        return auth;
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
        account.setBalance(286L);
        return account;
    }

    /**
     * 构造充值套餐。
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
        rechargePackage.setSortOrder(30);
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
        order.setCreatedAt(LocalDateTime.of(2026, 7, 17, 15, 30));
        order.setPaidAt(RechargeOrderStatusDict.PAID.getCode().equals(status)
                ? LocalDateTime.of(2026, 7, 17, 15, 30, 8)
                : null);
        return order;
    }
}
