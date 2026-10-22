package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatMobileProperties;
import com.jxc.wefolio.dict.AuthTypeDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioResponse;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.WechatMobileMessage;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 安卓作品集打开用例 — 仅由新增维护者认证路由调用，复用原访客、渲染及计费规则。 */
@Service
@RequiredArgsConstructor
public class MobileVisitorApplicationService {
    /** 身份唯一性查询上限。 */
    private static final String QUERY_LIMIT = "LIMIT 2";
    /** 移动应用开关。 */
    private final WechatMobileProperties properties;
    /** 用户状态查询。 */
    private final UserEntityMapper users;
    /** 已有小程序身份查询。 */
    private final UserAuthEntityMapper identities;
    /** 复用原访客持久化及并发恢复流程。 */
    private final VisitorIdentityPersistenceService persistence;
    /** 个人作品集原业务入口。 */
    private final VisitorPortfolioService personal;
    /** 团队作品集原业务入口。 */
    private final VisitorTeamPortfolioService team;

    /** 已认证用户打开个人作品集，身份不取自客户端请求字段。 */
    public VisitorPortfolioResponse openPersonal(Long userId, String shareCode, VisitorPortfolioOpenRequest request) {
        requireEnabled();
        return personal.openPortfolio(shareCode, request, () -> resolve(userId));
    }

    /** 已认证用户打开标准团队作品集。 */
    public VisitorTeamPortfolioResponse openTeam(Long userId, String shareCode, VisitorTeamPortfolioOpenRequest request) {
        requireEnabled();
        return team.openPortfolio(shareCode, request, PortfolioTemplateTypeDict.STANDARD.getCode(), () -> resolve(userId));
    }

    /** 已认证用户打开高级团队作品集。 */
    public VisitorTeamPortfolioResponse openAdvancedTeam(Long userId, String shareCode, VisitorTeamPortfolioOpenRequest request) {
        requireEnabled();
        return team.openPortfolio(shareCode, request, PortfolioTemplateTypeDict.ADVANCED.getCode(), () -> resolve(userId));
    }

    /** 只使用已认证账号的原 openid，保持本人访问识别及去重语义。 */
    private VisitorService.VisitorSession resolve(Long userId) {
        UserAuthEntity auth = requireIdentity(userId);
        VisitorIdentityPersistenceService.VisitorIdentity identity = persistence.tryResolveOrCreate(auth.getOpenId(), null)
                .orElseGet(() -> persistence.recoverIgnoredInsert(auth.getOpenId(), null));
        return new VisitorService.VisitorSession(identity.visitor(), identity.newVisitor(), false);
    }

    /** 默认关闭新增移动端入口，不影响原路由。 */
    private void requireEnabled() {
        if (!properties.isConfigured()) throw new BusinessException(WechatMobileMessage.NOT_ENABLED);
    }

    /** 身份来自存量认证记录，不能由客户端提交或猜测。 */
    private UserAuthEntity requireIdentity(Long userId) {
        UserEntity user = userId == null ? null : users.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_INACTIVE);
        }
        List<UserAuthEntity> matches = identities.selectList(Wrappers.lambdaQuery(UserAuthEntity.class)
                .eq(UserAuthEntity::getUserId, userId)
                .eq(UserAuthEntity::getAuthType, AuthTypeDict.WECHAT_MINI_APP.getCode()).last(QUERY_LIMIT));
        if (matches.size() != 1) throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        UserAuthEntity identity = matches.getFirst();
        if (!UserStatusDict.ACTIVE.getCode().equals(identity.getStatus())
                || identity.getOpenId() == null || identity.getOpenId().isBlank()) {
            throw new BusinessException(WechatMobileMessage.ACCOUNT_NOT_LINKED);
        }
        return identity;
    }

}
