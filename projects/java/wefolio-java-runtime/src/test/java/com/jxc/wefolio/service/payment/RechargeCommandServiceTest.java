package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dict.RechargePackageStatusDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.CreateRechargeOrderRequest;
import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
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
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.PointService;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 充值建单编排的验证顺序、支付参数及无效会话保护测试。 */
@ExtendWith(MockitoExtension.class)
class RechargeCommandServiceTest {

    /** 测试维护者编号。 */
    private static final Long USER_ID = 7L;
    /** 测试套餐编号。 */
    private static final Long PACKAGE_ID = 3L;
    /** 测试商户订单号。 */
    private static final String ORDER_NO = "WFR-COMMAND-TEST";
    /** 测试虚拟支付应用标识。 */
    private static final String OFFER_ID = "test-offer";
    /** 仅供单元测试的应用签名密钥。 */
    private static final String APP_KEY = "test-app-key";
    /** 仅供单元测试的微信会话密钥。 */
    private static final String SESSION_KEY = "test-session-key";
    /** 测试微信身份。 */
    private static final String OPEN_ID = "test-openid";
    /** 测试客户端地址。 */
    private static final String CLIENT_IP = "127.0.0.1";
    /** 小程序支付调用模式。 */
    private static final String PAYMENT_MODE = "short_series_coin";
    /** 小程序支付签名接口名。 */
    private static final String PAYMENT_URI = "requestVirtualPayment";
    /** 模拟签名器返回的应用签名。 */
    private static final String PAY_SIGNATURE = "test-pay-signature";
    /** 模拟签名器返回的用户签名。 */
    private static final String USER_SIGNATURE = "test-user-signature";
    /** 按支付协议手写的签名正文，确保金额数量及字段类型保持不变。 */
    private static final String EXPECTED_SIGN_DATA = "{\"offerId\":\"test-offer\",\"buyQuantity\":500,"
            + "\"env\":0,\"currencyType\":\"CNY\",\"outTradeNo\":\"WFR-COMMAND-TEST\",\"zoneId\":\"1\"}";
    /** 原有微信会话失效提示。 */
    private static final String SESSION_UNAVAILABLE_MESSAGE = "微信会话已失效，请刷新后重试";
    /** 建单过期时间使用的业务时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 测试支付配置，不读取环境变量或真实密钥。 */
    private final WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
    /** 用户状态查询边界。 */
    @Mock private UserEntityMapper users;
    /** 微信身份查询边界。 */
    @Mock private UserAuthEntityMapper auths;
    /** 有效套餐查询边界。 */
    @Mock private RechargePackageEntityMapper packages;
    /** 充值订单查询边界。 */
    @Mock private RechargeOrderEntityMapper orders;
    /** 积分账户初始化边界。 */
    @Mock private PointService points;
    /** 本地订单持久化事务边界。 */
    @Mock private RechargeOrderTransactionService transactions;
    /** 微信远端请求边界。 */
    @Mock private WechatVirtualPaymentClient client;
    /** 支付签名边界。 */
    @Mock private WechatVirtualPaymentSigner signer;
    /** 同用户支付互斥边界。 */
    @Mock private UserPointMutex mutex;
    /** 维护者有效会话查询边界。 */
    @Mock private MaintainerWechatSessionService sessions;
    /** 退款事务边界。 */
    @Mock private RechargeRefundTransactionService refunds;
    /** 权威余额同步边界。 */
    @Mock private WechatAuthoritativeBalanceSyncService balances;
    /** 被测充值写业务服务。 */
    private RechargeCommandService service;
    /** 建单使用的积分账户。 */
    private PointAccountEntity account;
    /** 建单使用的有效套餐。 */
    private RechargePackageEntity rechargePackage;

    /** 使用固定测试配置构造写服务，所有数据库和远端边界均由模拟替代。 */
    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setOfferId(OFFER_ID);
        properties.setAppKey(APP_KEY);
        service = new RechargeCommandService(properties, users, auths, packages, orders, points,
                transactions, client, signer, mutex, sessions, refunds, balances);
        account = new PointAccountEntity();
        account.setId(10L);
        account.setUserId(USER_ID);
        account.setBalance(120L);
        rechargePackage = new RechargePackageEntity();
        rechargePackage.setId(PACKAGE_ID);
        rechargePackage.setStatus(RechargePackageStatusDict.ACTIVE.getCode());
        rechargePackage.setAmountFen(5000);
        rechargePackage.setBasePoints(500);
        rechargePackage.setBonusPoints(20);
        rechargePackage.setTotalPoints(520);
    }

    /** 成功建单按原顺序验证身份并把同一签名正文交给两种签名与响应。 */
    @Test
    void createOrderShouldKeepValidationOrderAndSignExactPaymentPayload() {
        prepareAvailableAccountAndPackage();
        when(sessions.findAvailableSession(USER_ID))
                .thenReturn(new MaintainerWechatSession(USER_ID, 2L, SESSION_KEY, 1L, CLIENT_IP));
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setMerchantOrderNo(ORDER_NO);
        order.setStatus(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
        order.setAmountFen(5000);
        order.setBuyQuantity(500L);
        order.setTotalPoints(520);
        when(transactions.createPendingOrder(eq(USER_ID), same(account), same(rechargePackage), any()))
                .thenReturn(order);
        when(signer.paySignature(eq(APP_KEY), eq(PAYMENT_URI), anyString())).thenReturn(PAY_SIGNATURE);
        when(signer.userSignature(eq(SESSION_KEY), anyString())).thenReturn(USER_SIGNATURE);
        LocalDateTime earliestExpiry = LocalDateTime.now(SHANGHAI_ZONE).plusMinutes(30L);

        CreateRechargeOrderResponse response = service.createOrder(USER_ID, request());

        LocalDateTime latestExpiry = LocalDateTime.now(SHANGHAI_ZONE).plusMinutes(30L);
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        InOrder sequence = inOrder(users, auths, packages, points, sessions, transactions, signer);
        sequence.verify(users).selectById(USER_ID);
        sequence.verify(auths).selectOne(any());
        sequence.verify(packages).selectOne(any());
        sequence.verify(points).ensureAccount(USER_ID);
        sequence.verify(sessions).findAvailableSession(USER_ID);
        sequence.verify(transactions)
                .createPendingOrder(eq(USER_ID), same(account), same(rechargePackage), expiry.capture());
        sequence.verify(signer).paySignature(APP_KEY, PAYMENT_URI, EXPECTED_SIGN_DATA);
        sequence.verify(signer).userSignature(SESSION_KEY, EXPECTED_SIGN_DATA);
        assertThat(expiry.getValue()).isBetween(earliestExpiry, latestExpiry);
        assertThat(response.getMerchantOrderNo()).isEqualTo(ORDER_NO);
        assertThat(response.getStatus()).isEqualTo(RechargeOrderStatusDict.PENDING_PAYMENT.getCode());
        assertThat(response.getMode()).isEqualTo(PAYMENT_MODE);
        assertThat(response.getSignData()).isEqualTo(EXPECTED_SIGN_DATA);
        assertThat(response.getPaySig()).isEqualTo(PAY_SIGNATURE);
        assertThat(response.getSignature()).isEqualTo(USER_SIGNATURE);
        verifyNoInteractions(orders, client, mutex, refunds, balances);
    }

    /** 支付关闭时空请求仍优先返回功能关闭提示，不触发身份读取或任何写入。 */
    @Test
    void disabledPaymentShouldRejectBeforeValidatingNullRequest() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.createOrder(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(RechargeMessage.PAYMENT_NOT_CONFIGURED_MESSAGE);

        verifyNoInteractions(users, auths, packages, orders, points, sessions, transactions,
                signer, client, mutex, refunds, balances);
    }

    /** 请求未选择套餐时直接返回套餐提示，身份及账户服务不能提前执行。 */
    @Test
    void missingPackageIdShouldRejectBeforeReadingIdentity() {
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();

        assertThatThrownBy(() -> service.createOrder(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(RechargeMessage.PACKAGE_ID_REQUIRED_MESSAGE);

        verifyNoInteractions(users, auths, packages, orders, points, sessions, transactions,
                signer, client, mutex, refunds, balances);
    }

    /** 账户和套餐有效但缺少微信会话时，必须在建单和签名前终止。 */
    @Test
    void missingWechatSessionShouldNotCreateOrderOrSignPayment() {
        prepareAvailableAccountAndPackage();

        assertThatThrownBy(() -> service.createOrder(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(SESSION_UNAVAILABLE_MESSAGE);

        verify(points).ensureAccount(USER_ID);
        verify(sessions).findAvailableSession(USER_ID);
        verifyNoInteractions(orders, transactions, signer, client, mutex, refunds, balances);
    }

    /** 模拟有效用户、微信身份、套餐和已初始化账户供成功及会话失效分支使用。 */
    private void prepareAvailableAccountAndPackage() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(users.selectById(USER_ID)).thenReturn(user);
        UserAuthEntity auth = new UserAuthEntity();
        auth.setUserId(USER_ID);
        auth.setOpenId(OPEN_ID);
        when(auths.selectOne(any())).thenReturn(auth);
        when(packages.selectOne(any())).thenReturn(rechargePackage);
        when(points.ensureAccount(USER_ID)).thenReturn(account);
    }

    /** 构造仅包含套餐编号的旧客户端建单请求。 */
    private CreateRechargeOrderRequest request() {
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        request.setPackageId(PACKAGE_ID);
        return request;
    }
}
