package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMobileProperties;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.WechatMobileMessage;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 新安卓访客用例测试 — 身份必须来自原账号，禁止客户端指定 openid 或用户编号。 */
class MobileVisitorApplicationServiceTest {
    /** 原业务依赖均使用模拟实现。 */
    private final UserEntityMapper users = mock(UserEntityMapper.class);
    /** 身份记录查询模拟。 */
    private final UserAuthEntityMapper identities = mock(UserAuthEntityMapper.class);
    /** 原访客持久化服务模拟。 */
    private final VisitorIdentityPersistenceService persistence = mock(VisitorIdentityPersistenceService.class);
    /** 个人业务流程模拟。 */
    private final VisitorPortfolioService personal = mock(VisitorPortfolioService.class);
    /** 团队业务流程模拟。 */
    private final VisitorTeamPortfolioService team = mock(VisitorTeamPortfolioService.class);
    /** 测试开关。 */
    private final WechatMobileProperties properties = new WechatMobileProperties();
    /** 待测服务。 */
    private final MobileVisitorApplicationService service = new MobileVisitorApplicationService(
            properties, users, identities, persistence, personal, team);

    /** 开启测试环境，只在原业务选择身份时触发解析。 */
    @BeforeEach
    void setUp() {
        properties.setEnabled(true); properties.setAppId("wx-test"); properties.setAppSecret("test-secret");
        when(personal.openPortfolio(anyString(), any(), any())).thenAnswer(call -> {
            Supplier<VisitorService.VisitorSession> resolve = call.getArgument(2);
            resolve.get(); return null;
        });
        when(team.openPortfolio(anyString(), any(), anyString(), any())).thenAnswer(call -> {
            Supplier<VisitorService.VisitorSession> resolve = call.getArgument(3);
            resolve.get(); return null;
        });
    }

    /** 个人及两类团队均沿用已有身份，客户端伪造登录码不参与身份解析。 */
    @Test
    void allRoutesPreserveOriginalIdentityAndBusinessRequest() {
        activeIdentity();
        var visitor = new VisitorEntity(); visitor.setId(12L); visitor.setOpenid("original-openid");
        when(persistence.tryResolveOrCreate("original-openid", null))
                .thenReturn(Optional.of(new VisitorIdentityPersistenceService.VisitorIdentity(visitor, false)));
        var request = new VisitorPortfolioOpenRequest(); request.setLoginCode("untrusted-code");
        service.openPersonal(7L, "personal", request);
        var teamRequest = new VisitorTeamPortfolioOpenRequest();
        service.openTeam(7L, "team", teamRequest);
        service.openAdvancedTeam(7L, "advanced", teamRequest);
        verify(personal).openPortfolio(eq("personal"), same(request), any());
        verify(team).openPortfolio(eq("team"), same(teamRequest), eq(PortfolioTemplateTypeDict.STANDARD.getCode()), any());
        verify(team).openPortfolio(eq("advanced"), same(teamRequest), eq(PortfolioTemplateTypeDict.ADVANCED.getCode()), any());
        verify(persistence, times(3)).tryResolveOrCreate("original-openid", null);
    }

    /** 复用原并发恢复流程，避免重复创建访客。 */
    @Test
    void recoversConcurrentVisitorInsert() {
        activeIdentity();
        var visitor = new VisitorEntity(); visitor.setId(12L);
        when(persistence.tryResolveOrCreate("original-openid", null)).thenReturn(Optional.empty());
        when(persistence.recoverIgnoredInsert("original-openid", null))
                .thenReturn(new VisitorIdentityPersistenceService.VisitorIdentity(visitor, false));
        service.openPersonal(7L, "personal", new VisitorPortfolioOpenRequest());
        verify(persistence).recoverIgnoredInsert("original-openid", null);
    }

    /** 不可用账号或身份不得进入访客持久化。 */
    @Test
    void rejectsMissingDisabledAndAmbiguousIdentity() {
        assertThatThrownBy(() -> service.openPersonal(7L, "p", null)).hasMessage(WechatMobileMessage.ACCOUNT_INACTIVE);
        activeIdentity();
        when(identities.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.openPersonal(7L, "p", null)).hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        var identity = new UserAuthEntity(); identity.setStatus(UserStatusDict.DISABLED.getCode());
        when(identities.selectList(any())).thenReturn(List.of(identity));
        assertThatThrownBy(() -> service.openPersonal(7L, "p", null)).hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        when(identities.selectList(any())).thenReturn(List.of(identity, identity));
        assertThatThrownBy(() -> service.openPersonal(7L, "p", null)).hasMessage(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        verifyNoInteractions(persistence);
    }

    /** 关闭安卓开关时不调用原业务入口。 */
    @Test
    void disabledMobileDoesNotOpenPortfolio() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.openPersonal(7L, "p", null)).hasMessage(WechatMobileMessage.NOT_ENABLED);
        verifyNoInteractions(users, identities, persistence);
    }

    /** 准备原小程序正常账号与身份。 */
    private void activeIdentity() {
        var user = new UserEntity(); user.setId(7L); user.setStatus(UserStatusDict.ACTIVE.getCode());
        when(users.selectById(7L)).thenReturn(user);
        var identity = new UserAuthEntity(); identity.setOpenId("original-openid");
        identity.setStatus(UserStatusDict.ACTIVE.getCode());
        when(identities.selectList(any())).thenReturn(List.of(identity));
    }
}
