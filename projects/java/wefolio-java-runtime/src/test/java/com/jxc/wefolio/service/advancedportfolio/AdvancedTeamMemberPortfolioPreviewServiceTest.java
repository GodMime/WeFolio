package com.jxc.wefolio.service.advancedportfolio;

import com.jxc.wefolio.dto.MinePortfolioDetailResponse;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 高级团队下钻要求有效父引用，并逐次复验来源和作者状态。 */
class AdvancedTeamMemberPortfolioPreviewServiceTest {
    /** 当前父作品集可见权。 */
    private final TeamPortfolioAccessService access = mock(TeamPortfolioAccessService.class);
    /** 父配置当前引用。 */
    private final PortfolioReferenceEntityMapper references = mock(PortfolioReferenceEntityMapper.class);
    /** 子作品集只读算法。 */
    private final MinePortfolioService personal = mock(MinePortfolioService.class);
    /** 当前来源授权复验。 */
    private final AdvancedTeamSourceAccessService sources = mock(AdvancedTeamSourceAccessService.class);
    /** 高级团队下钻服务。 */
    private final AdvancedTeamMemberPortfolioPreviewService service = new AdvancedTeamMemberPortfolioPreviewService(access, references, personal, sources);

    /** 安排当前父作品集有效成员身份。 */
    @BeforeEach void setup() {
        AuthContextHolder.set(new AuthContext(7L, "test-maintainer")); TeamEntity team = new TeamEntity(); team.setId(31L);
        when(access.requireVisiblePortfolio(11L, 7L, PortfolioTemplateTypeDict.ADVANCED.getCode()))
                .thenReturn(new TeamPortfolioAccessService.TeamPortfolioAccess(null, team, null, false, true));
    }
    /** 清理线程身份。 */
    @AfterEach void clear() { AuthContextHolder.clear(); }

    /** 父有效引用并不能越过作者失效或成员撤权。 */
    @Test void currentSourceFailurePreventsChildRendering() {
        when(references.selectCount(any())).thenReturn(1L);
        doThrow(new BusinessException("来源已撤权")).when(sources).requireMemberPortfolio(31L, 21L);
        assertThatThrownBy(() -> service.preview(11L, 21L, "draft")).isInstanceOf(BusinessException.class);
        verify(personal, never()).previewPublishedReferencedPortfolio(any(), anyBoolean());
    }
    /** 仅当前有效来源可沿高级显式策略预览发布版，且不产生真实访问。 */
    @Test void validCurrentReferenceUsesPublishedReadOnlyAlgorithm() {
        when(references.selectCount(any())).thenReturn(1L);
        when(personal.previewPublishedReferencedPortfolio(21L, true)).thenReturn(new MinePortfolioDetailResponse());
        service.preview(11L, 21L, "published");
        verify(sources).requireMemberPortfolio(31L, 21L); verify(personal).previewPublishedReferencedPortfolio(21L, true);
    }
}
