package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatMobileProperties;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MaintainerWechatLoginResponse;
import com.jxc.wefolio.dto.MobileWechatLoginRequest;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.WechatMobileMessage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 安卓登录安全边界 — 仅接受官方身份并关联唯一正常的存量账号。 */
@ExtendWith(MockitoExtension.class)
class MobileWechatAuthServiceTest {
    /** 微信 OAuth 模拟客户端。 */
    @Mock private WechatMobileClient client;
    /** 新增关联接口的小程序官方凭证模拟。 */
    @Mock private WechatMiniappClient miniappClient;
    /** 认证记录模拟查询。 */
    @Mock private UserAuthEntityMapper authMapper;
    /** 用户状态模拟查询。 */
    @Mock private UserEntityMapper userMapper;
    /** 原维护者令牌签发入口。 */
    @Mock private MobileMaintainerTokenService miniappAuth;
    /** 当前测试的移动应用配置。 */
    private WechatMobileProperties mobile;
    /** 待测用例。 */
    private MobileWechatAuthService service;

    /** 初始化模拟数据，绝不连接真实微信或数据库。 */
    @BeforeEach
    void setUp() {
        mobile = new WechatMobileProperties();
        mobile.setEnabled(true);
        mobile.setAppId("wx-mobile-test");
        mobile.setAppSecret("mobile-test-secret");
        WechatMiniappProperties mini = new WechatMiniappProperties();
        mini.setAppSecret("existing-identity-secret");
        service = new MobileWechatAuthService(mobile, mini, client, miniappClient, authMapper, userMapper, miniappAuth);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-test"), UserAuthEntity.class);
    }

    /** 未开通时拒绝登录，不调用任何外部依赖。 */
    @Test
    void disabledLoginDoesNotExchangeCode() {
        mobile.setEnabled(false);
        assertThat(service.capabilities().enabled()).isFalse();
        assertThat(service.capabilities().registrationEnabled()).isFalse();
        assertThat(service.capabilities().paymentsEnabled()).isFalse();
        assertThatThrownBy(() -> service.login(new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.NOT_ENABLED);
        verifyNoInteractions(client, authMapper, userMapper, miniappAuth);
    }

    /** 只按官方 UnionID 的既有 HMAC 查询，不使用移动应用密钥计算旧身份。 */
    @Test
    void existingAccountGetsExistingMaintainerToken() throws Exception {
        when(client.exchangeUnionId("official-code")).thenReturn("verified-union-id");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("existing-identity-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedDigest = HexFormat.of().formatHex(mac.doFinal("verified-union-id".getBytes(StandardCharsets.UTF_8)));
        when(authMapper.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("union_identifier_hash", "auth_type", "LIMIT 2");
            assertThat(query.getParamNameValuePairs().values()).contains(expectedDigest, "WECHAT_MINI_APP");
            return List.of(auth(7L));
        });
        UserEntity user = new UserEntity(); user.setId(7L); user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userMapper.selectById(7L)).thenReturn(user);
        MaintainerWechatLoginResponse session = new MaintainerWechatLoginResponse(); session.setToken("existing-session");
        when(miniappAuth.issue(7L)).thenReturn(session);
        assertThat(service.login(new MobileWechatLoginRequest("official-code"))).isSameAs(session);
        verify(authMapper, never()).insert(any(UserAuthEntity.class));
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    /** 未关联用户不得被自动注册，也不得尝试根据客户端昵称或手机号匹配。 */
    @Test
    void unlinkedIdentityDoesNotCreateAccount() {
        when(client.exchangeUnionId("code")).thenReturn("unknown-union");
        when(authMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.login(new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        verifyNoInteractions(userMapper, miniappAuth);
        verify(authMapper, never()).insert(any(UserAuthEntity.class));
    }

    /** 同一个身份命中多个记录时拒绝签发，禁止任取第一条。 */
    @Test
    void ambiguousIdentityDoesNotIssueToken() {
        when(client.exchangeUnionId("code")).thenReturn("union");
        when(authMapper.selectList(any())).thenReturn(List.of(auth(7L), auth(8L)));
        assertThatThrownBy(() -> service.login(new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        verifyNoInteractions(userMapper, miniappAuth);
    }

    /** 被停用的身份不能通过安卓入口绕过认证状态。 */
    @Test
    void disabledIdentityDoesNotIssueToken() {
        UserAuthEntity auth = auth(7L); auth.setStatus(UserStatusDict.DISABLED.getCode());
        when(client.exchangeUnionId("code")).thenReturn("union");
        when(authMapper.selectList(any())).thenReturn(List.of(auth));
        assertThatThrownBy(() -> service.login(new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.ACCOUNT_INACTIVE);
        verifyNoInteractions(userMapper, miniappAuth);
    }

    /** 已删除或被停用的用户不能获取会话。 */
    @Test
    void missingOrDisabledUserDoesNotIssueToken() {
        when(client.exchangeUnionId("code")).thenReturn("union");
        when(authMapper.selectList(any())).thenReturn(List.of(auth(7L)));
        assertThatThrownBy(() -> service.login(new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.ACCOUNT_INACTIVE);
        UserEntity user = new UserEntity(); user.setStatus(UserStatusDict.DISABLED.getCode());
        when(userMapper.selectById(7L)).thenReturn(user);
        assertThatThrownBy(() -> service.login(new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.ACCOUNT_INACTIVE);
        verifyNoInteractions(miniappAuth);
    }

    /** 空白和异常长度的凭证在业务入口被拒绝。 */
    @Test
    void invalidCodeDoesNotCallWechat() {
        for (MobileWechatLoginRequest request : new MobileWechatLoginRequest[]{null,
                new MobileWechatLoginRequest(" "), new MobileWechatLoginRequest("x".repeat(513))}) {
            assertThatThrownBy(() -> service.login(request)).hasMessage(WechatMobileMessage.CODE_REQUIRED);
        }
        verifyNoInteractions(client, authMapper, userMapper, miniappAuth);
    }

    /** 构造有效的存量认证记录。 */
    private UserAuthEntity auth(long userId) {
        UserAuthEntity entity = new UserAuthEntity(); entity.setUserId(userId);
        entity.setStatus(UserStatusDict.ACTIVE.getCode()); return entity;
    }

    /** 新关联接口只更新通过当前令牌及官方 openid 双重验证的现有身份。 */
    @Test
    void linksOnlyAuthenticatedMiniappIdentity() {
        UserEntity user = new UserEntity(); user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userMapper.selectById(7L)).thenReturn(user);
        var session = new com.jxc.wefolio.dto.WechatSessionResponse();
        session.setOpenid("original-openid"); session.setUnionid("official-union");
        when(miniappClient.exchangeCode("mini-code")).thenReturn(session);
        var identity = auth(7L); identity.setOpenId("original-openid");
        when(authMapper.selectList(any())).thenReturn(List.of(identity), List.of());
        when(authMapper.updateById(identity)).thenReturn(1);
        service.linkMiniapp(7L, new MobileWechatLoginRequest("mini-code"));
        assertThat(identity.getUnionIdentifierHash()).hasSize(64).doesNotContain("official-union");
        verify(authMapper).updateById(identity);
        verify(authMapper, never()).insert(any(UserAuthEntity.class));
        verifyNoInteractions(client, miniappAuth);
    }

    /** 即使持有有效账号令牌，也不能把其他微信账号绑定到自己名下。 */
    @Test
    void rejectsLinkingDifferentWechatIdentity() {
        UserEntity user = new UserEntity(); user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userMapper.selectById(7L)).thenReturn(user);
        var session = new com.jxc.wefolio.dto.WechatSessionResponse();
        session.setOpenid("someone-else"); session.setUnionid("official-union");
        when(miniappClient.exchangeCode("mini-code")).thenReturn(session);
        var identity = auth(7L); identity.setOpenId("original-openid");
        when(authMapper.selectList(any())).thenReturn(List.of(identity));
        assertThatThrownBy(() -> service.linkMiniapp(7L, new MobileWechatLoginRequest("mini-code")))
                .hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        verify(authMapper, never()).updateById(any(UserAuthEntity.class));
    }

    /** 关联接口关闭或凭证非法时不访问微信、数据库或令牌服务。 */
    @Test
    void rejectsDisabledOrInvalidLinkRequest() {
        mobile.setEnabled(false);
        assertThatThrownBy(() -> service.linkMiniapp(7L, new MobileWechatLoginRequest("code")))
                .hasMessage(WechatMobileMessage.NOT_ENABLED);
        mobile.setEnabled(true);
        for (var request : new MobileWechatLoginRequest[]{null, new MobileWechatLoginRequest(" "),
                new MobileWechatLoginRequest("x".repeat(513))}) {
            assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.CODE_REQUIRED);
        }
        verifyNoInteractions(client, miniappClient, authMapper, userMapper, miniappAuth);
    }

    /** 无有效用户或官方跨端身份时禁止补写摘要。 */
    @Test
    void rejectsLinkWithoutActiveUserOrOfficialIdentity() {
        var request = new MobileWechatLoginRequest("code");
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_INACTIVE);
        var user = new UserEntity(); user.setStatus(UserStatusDict.DISABLED.getCode());
        when(userMapper.selectById(7L)).thenReturn(user);
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_INACTIVE);
        user.setStatus(UserStatusDict.ACTIVE.getCode());
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        var session = new com.jxc.wefolio.dto.WechatSessionResponse(); session.setOpenid("openid");
        when(miniappClient.exchangeCode("code")).thenReturn(session);
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        verifyNoInteractions(authMapper, miniappAuth);
    }

    /** 只在旧身份摘要确实匹配时允许补齐；多重绑定及并发更新失败均不得报告成功。 */
    @Test
    void legacyHashLinkRejectsAmbiguityAndFailedUpdate() throws Exception {
        var request = new MobileWechatLoginRequest("code");
        var user = new UserEntity(); user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(userMapper.selectById(7L)).thenReturn(user);
        var session = new com.jxc.wefolio.dto.WechatSessionResponse();
        session.setOpenid("legacy-openid"); session.setUnionid("official-union");
        when(miniappClient.exchangeCode("code")).thenReturn(session);
        var identity = auth(7L);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("existing-identity-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        identity.setIdentifierHash(HexFormat.of().formatHex(mac.doFinal("legacy-openid".getBytes(StandardCharsets.UTF_8))));
        when(authMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        when(authMapper.selectList(any())).thenReturn(List.of(identity, identity));
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        when(authMapper.selectList(any())).thenReturn(List.of(identity), List.of(auth(8L)));
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.ACCOUNT_AMBIGUOUS);
        verify(authMapper, never()).updateById(any(UserAuthEntity.class));
        when(authMapper.selectList(any())).thenReturn(List.of(identity), List.of());
        assertThatThrownBy(() -> service.linkMiniapp(7L, request)).hasMessage(WechatMobileMessage.IDENTITY_FAILED);
        when(authMapper.selectList(any())).thenReturn(List.of(identity), List.of());
        when(authMapper.updateById(identity)).thenReturn(1);
        service.linkMiniapp(7L, request);
        assertThat(identity.getUnionIdentifierHash()).hasSize(64);
    }
}
