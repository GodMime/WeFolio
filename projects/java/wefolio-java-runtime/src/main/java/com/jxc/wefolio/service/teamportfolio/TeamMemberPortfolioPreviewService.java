package com.jxc.wefolio.service.teamportfolio;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dto.MinePortfolioDetailResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.service.MinePortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 团队预览下钻成员个人作品集服务。
 */
@Service
@RequiredArgsConstructor
public class TeamMemberPortfolioPreviewService {

    /** 正式预览参数。 */
    private static final String PUBLISHED_PREVIEW_SCOPE = "published";

    /** 草稿预览参数。 */
    private static final String DRAFT_PREVIEW_SCOPE = "draft";

    /** 有效引用标识。 */
    private static final int VALID_REFERENCE = 1;

    /** 成员作品集未被当前配置引用提示。 */
    private static final String MEMBER_PORTFOLIO_NOT_REFERENCED_MESSAGE =
            "成员个人作品集未被当前团队作品集引用";

    /** 团队作品集访问控制。 */
    private final TeamPortfolioAccessService accessService;

    /** 作品集引用数据访问器。 */
    private final PortfolioReferenceEntityMapper referenceMapper;

    /** 个人作品集维护服务。 */
    private final MinePortfolioService minePortfolioService;

    /**
     * 预览团队配置引用的成员个人作品集发布版本。
     */
    public MinePortfolioDetailResponse preview(
            long teamPortfolioId,
            long memberPortfolioId,
            String scope,
            long userId
    ) {
        requirePreviewReference(teamPortfolioId, memberPortfolioId, scope, userId);
        return minePortfolioService.previewPublishedReferencedPortfolio(memberPortfolioId);
    }

    /**
     * 查询成员个人作品集预览档期月历。
     */
    public PortfolioScheduleOptionsResponse scheduleOptions(
            long teamPortfolioId,
            long memberPortfolioId,
            String month,
            String componentKey,
            String scope,
            long userId
    ) {
        requirePreviewReference(teamPortfolioId, memberPortfolioId, scope, userId);
        return minePortfolioService.queryPublishedReferencedPreviewScheduleOptions(
                memberPortfolioId, month, componentKey);
    }

    /**
     * 执行成员个人作品集预览档期查询，不写入查档记录。
     */
    public PortfolioScheduleQueryResponse scheduleQueryPreview(
            long teamPortfolioId,
            long memberPortfolioId,
            PortfolioScheduleQueryRequest request,
            String scope,
            long userId
    ) {
        requirePreviewReference(teamPortfolioId, memberPortfolioId, scope, userId);
        return minePortfolioService.submitPublishedReferencedPreviewScheduleQuery(
                memberPortfolioId, request);
    }

    /** 校验团队预览访问权限和对应配置作用域的成员作品集引用。 */
    private void requirePreviewReference(
            long teamPortfolioId,
            long memberPortfolioId,
            String scope,
            long userId
    ) {
        String normalizedScope = normalizeScope(scope);
        accessService.requireVisiblePortfolio(teamPortfolioId, userId);
        String configScope = PUBLISHED_PREVIEW_SCOPE.equals(normalizedScope)
                ? PortfolioConfigScopeDict.PUBLISHED.getCode()
                : PortfolioConfigScopeDict.DRAFT.getCode();
        Long referenceCount = referenceMapper.selectCount(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .eq(PortfolioReferenceEntity::getPortfolioId, teamPortfolioId)
                        .eq(PortfolioReferenceEntity::getConfigScope, configScope)
                        .eq(PortfolioReferenceEntity::getReferenceType,
                                ReferenceTypeDict.MEMBER_PORTFOLIO.getCode())
                        .eq(PortfolioReferenceEntity::getReferenceId, memberPortfolioId)
                        .eq(PortfolioReferenceEntity::getIsValid, VALID_REFERENCE));
        if (referenceCount == null || referenceCount <= 0) {
            throw new BusinessException(MEMBER_PORTFOLIO_NOT_REFERENCED_MESSAGE);
        }
    }

    /** 仅正式参数映射为正式作用域，其余值统一按草稿预览处理。 */
    private String normalizeScope(String scope) {
        return PUBLISHED_PREVIEW_SCOPE.equals(scope)
                ? PUBLISHED_PREVIEW_SCOPE : DRAFT_PREVIEW_SCOPE;
    }
}
