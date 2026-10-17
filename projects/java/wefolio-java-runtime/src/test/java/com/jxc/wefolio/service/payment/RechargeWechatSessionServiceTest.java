package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.WechatSessionResponse;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.message.MiniappAuthMessage;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.TrustedClientIpResolver;
import com.jxc.wefolio.service.WechatMiniappClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 支付使用本次设备密钥，拒绝身份不符及无效登录凭证，且不被共享会话回读结果替换。 */
@ExtendWith(MockitoExtension.class)
class RechargeWechatSessionServiceTest {
    /** 测试用户编号。 */
    private static final Long USER_ID = 7L;
    /** 测试认证编号。 */
    private static final Long AUTH_ID = 2L;
    /** 大小写敏感的测试微信身份。 */
    private static final String OPEN_ID = "device-OpenId";
    /** 当前设备测试凭证。 */
    private static final String CODE = "device-code";
    /** 当前设备测试密钥。 */
    private static final String DEVICE_KEY = "device-session-key";
    /** 测试请求来源地址。 */
    private static final String CLIENT_IP = "127.0.0.1";
    /** 微信会话交换边界。 */
    @Mock private WechatMiniappClient client;
    /** 身份查询边界。 */
    @Mock private UserAuthEntityMapper auths;
    /** 共享会话持久化边界。 */
    @Mock private MaintainerWechatSessionService sessions;
    /** 可信请求地址边界。 */
    @Mock private TrustedClientIpResolver ips;
    /** 被测支付会话服务。 */
    private RechargeWechatSessionService service;

    /** 构造仅使用测试替身的服务。 */
    @BeforeEach
    void setUp() {
        service = new RechargeWechatSessionService(client, auths, sessions, ips);
    }

    /** 模拟其他终端覆盖共享会话后，仍必须返回本次设备密钥用于支付签名。 */
    @Test
    void shouldUseExchangedKeyEvenIfSavedSessionReturnsAnotherDevicesKey() {
        prepareIdentity();
        when(client.exchangeCode(CODE)).thenReturn(response(OPEN_ID, DEVICE_KEY));
        when(sessions.saveAvailableSession(USER_ID, AUTH_ID, DEVICE_KEY, CLIENT_IP))
                .thenReturn(new MaintainerWechatSession(USER_ID, AUTH_ID, "other-device-key", 99L, CLIENT_IP));

        assertThat(service.refreshForPayment(USER_ID, CODE)).isEqualTo(DEVICE_KEY);

        verify(sessions).saveAvailableSession(USER_ID, AUTH_ID, DEVICE_KEY, CLIENT_IP);
        verify(sessions, never()).findAvailableSession(any());
    }

    /** 另一个微信或仅大小写不同的 openid 均不能刷新当前维护者的会话。 */
    @ParameterizedTest
    @ValueSource(strings = {"other-openid", "device-openid", ""})
    void mismatchedIdentityShouldNotSaveSession(String openid) {
        prepareIdentity();
        when(client.exchangeCode(CODE)).thenReturn(response(openid, DEVICE_KEY));

        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, CODE))
                .isInstanceOf(BusinessException.class)
                .hasMessage(RechargeMessage.PAYMENT_IDENTITY_MISMATCH_MESSAGE);
        verifyNoInteractions(sessions);
    }

    /** 新版客户端传空凭证时不能静默退回历史会话。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blankCodeShouldFailWithoutExchangingOrSaving(String code) {
        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, code))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MiniappAuthMessage.WECHAT_LOGIN_CODE_REQUIRED_MESSAGE);
        verifyNoInteractions(client, auths, sessions, ips);
    }

    /** 微信没有返回有效密钥时停止支付，不使用历史密钥兜底。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingSessionKeyShouldFail(String key) {
        prepareIdentity();
        when(client.exchangeCode(CODE)).thenReturn(response(OPEN_ID, key));
        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, CODE))
                .hasMessage(RechargeMessage.PAYMENT_SESSION_UNAVAILABLE_MESSAGE);
        verifyNoInteractions(sessions);
    }

    /** 微信拒绝已消费的 code 时必须传播失败，不保存会话或重用旧密钥。 */
    @Test
    void rejectedCodeShouldPropagateWithoutFallback() {
        prepareIdentity();
        when(client.exchangeCode(CODE)).thenThrow(new BusinessException("测试凭证已消费"));
        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, CODE)).hasMessage("测试凭证已消费");
        verifyNoInteractions(sessions);
    }

    /** 多身份或缺少有效认证时，连微信凭证交换也不能开始。 */
    @Test
    void missingUniqueIdentityShouldFailBeforeExchanging() {
        when(auths.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, CODE))
                .hasMessage(MiniappAuthMessage.WECHAT_ACCOUNT_INVALID_MESSAGE);
        verifyNoInteractions(client, sessions, ips);
    }

    /** 无可信请求地址时不得先消费一次性 code。 */
    @Test
    void missingClientIpShouldFailBeforeExchanging() {
        prepareIdentity();
        when(ips.resolveCurrentRequest()).thenReturn(null);
        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, CODE))
                .hasMessage(RechargeMessage.PAYMENT_CLIENT_IP_REQUIRED_MESSAGE);
        verifyNoInteractions(client, sessions);
    }

    /** 微信返回失败码即使夹带密钥也不能签名。 */
    @Test
    void errorResponseShouldNotSaveSession() {
        prepareIdentity();
        WechatSessionResponse failed = response(OPEN_ID, DEVICE_KEY);
        failed.setErrcode(40029);
        when(client.exchangeCode(CODE)).thenReturn(failed);
        assertThatThrownBy(() -> service.refreshForPayment(USER_ID, CODE))
                .hasMessage(RechargeMessage.PAYMENT_SESSION_UNAVAILABLE_MESSAGE);
        verifyNoInteractions(sessions);
    }

    /** 准备唯一有效身份及可信客户端地址。 */
    private void prepareIdentity() {
        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(AUTH_ID);
        auth.setUserId(USER_ID);
        auth.setAuthType(AuthTypeDict.WECHAT_MINI_APP.getCode());
        auth.setStatus(UserStatusDict.ACTIVE.getCode());
        auth.setOpenId(OPEN_ID);
        when(auths.selectList(any())).thenReturn(List.of(auth));
        when(ips.resolveCurrentRequest()).thenReturn(CLIENT_IP);
    }

    /** 构造微信会话响应测试数据。 */
    private WechatSessionResponse response(String openid, String key) {
        WechatSessionResponse response = new WechatSessionResponse();
        response.setOpenid(openid);
        response.setSessionKey(key);
        return response;
    }
}
