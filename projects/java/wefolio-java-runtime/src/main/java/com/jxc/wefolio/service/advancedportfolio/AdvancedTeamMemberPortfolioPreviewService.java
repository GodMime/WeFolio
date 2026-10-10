package com.jxc.wefolio.service.advancedportfolio;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 高级团队只读下钻，读取时持续复核成员授权和已保存引用，不产生真实访问。 */
@Service
@RequiredArgsConstructor
public class AdvancedTeamMemberPortfolioPreviewService {
    /** 当前成员可见性。 */
    private final TeamPortfolioAccessService access;
    /** 已保存引用查询。 */
    private final PortfolioReferenceEntityMapper references;
    /** 个人只读渲染算法。 */
    private final MinePortfolioService personal;
    /** 当前发布来源及作者账号的完整校验。 */
    private final AdvancedTeamSourceAccessService sources;
    /** 已发布父配置的作用域值。 */
    private static final String PUBLISHED_PREVIEW_SCOPE = "published";

    /** 下钻已发布普通或高级个人作品集。 */
    public MinePortfolioDetailResponse preview(long parentId, long childId, String scope) {
        validate(parentId, childId, scope);
        return personal.previewPublishedReferencedPortfolio(childId, true);
    }
    /** 已保存成员预览档期。 */
    public PortfolioScheduleOptionsResponse options(long parentId, long childId, String month, String componentKey, String scope) {
        validate(parentId, childId, scope);
        return personal.queryPublishedReferencedPreviewScheduleOptions(childId, month, componentKey, true);
    }
    /** 成员只读预览查档。 */
    public PortfolioScheduleQueryResponse query(long parentId, long childId, PortfolioScheduleQueryRequest request, String scope) {
        validate(parentId, childId, scope);
        return personal.submitPublishedReferencedPreviewScheduleQuery(childId, request, true);
    }
    /** 逐次校验父归属、有效引用、成员关系与 allowPortfolio，不能使用保存时授权快照。 */
    private void validate(long parentId, long childId, String scope) {
        var parent = access.requireVisiblePortfolio(parentId, AuthContextHolder.requireUserId(), PortfolioTemplateTypeDict.ADVANCED.getCode());
        String configScope = PUBLISHED_PREVIEW_SCOPE.equals(scope) ? PortfolioConfigScopeDict.PUBLISHED.getCode() : PortfolioConfigScopeDict.DRAFT.getCode();
        Long count = references.selectCount(Wrappers.<PortfolioReferenceEntity>lambdaQuery()
                .eq(PortfolioReferenceEntity::getPortfolioId, parentId).eq(PortfolioReferenceEntity::getConfigScope, configScope)
                .eq(PortfolioReferenceEntity::getReferenceType, ReferenceTypeDict.MEMBER_PORTFOLIO.getCode())
                .eq(PortfolioReferenceEntity::getReferenceId, childId).eq(PortfolioReferenceEntity::getIsValid, 1));
        if (count == null || count < 1) { throw invalid(); }
        sources.requireMemberPortfolio(parent.team().getId(), childId);
    }
    /** 受控来源错误。 */
    private BusinessException invalid() { return new BusinessException(AdvancedPortfolioMessage.MEMBER_PORTFOLIO_UNAVAILABLE); }
}
