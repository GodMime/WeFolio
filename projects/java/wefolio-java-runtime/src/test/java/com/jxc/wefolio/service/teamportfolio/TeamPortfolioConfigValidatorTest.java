package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队作品集顶层配置分发测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamPortfolioConfigValidatorTest {

    @Mock
    private TeamProfileComponentValidator teamProfileValidator;
    @Mock
    private TeamCarouselComponentValidator carouselValidator;
    @Mock
    private TeamSingleWorkComponentValidator singleWorkValidator;
    @Mock
    private TeamDividerComponentValidator dividerValidator;
    @Mock
    private TeamMemberPortfolioGridComponentValidator gridValidator;
    @Mock
    private TeamMemberPortfolioListComponentValidator listValidator;
    @Mock
    private TeamTextSectionComponentValidator textValidator;

    /** 结构化文字组件策略模拟。 */
    @Mock
    private TeamStructuredTextSectionComponentValidator structuredTextValidator;
    @Mock
    private TeamScheduleQueryComponentValidator scheduleValidator;
    @Mock
    private TeamContactFormComponentValidator contactValidator;
    @Mock
    private TeamQrContactComponentValidator qrValidator;
    @Mock
    private TeamVideoCarouselComponentValidator videoCarouselValidator;

    /**
     * revision 3 配置必须把视频轮播分发到独立校验器。
     */
    @Test
    void normalizeForDraftShouldDispatchVideoCarouselValidator() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        JSONObject normalizedData = new JSONObject();
        normalizedData.put("title", "视频作品");
        when(videoCarouselValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(normalizedData);
        TeamPortfolioConfigDto config = config(List.of(component(
                "video-carousel-1", TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), 1000, true)));
        config.setEditorSchemaRevision(3);

        TeamPortfolioConfigDto normalized = service().normalizeForDraft(config, null, context);

        assertThat(normalized.getComponents()).singleElement().satisfies(component -> {
            assertThat(component.getComponentType()).isEqualTo(TeamPortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode());
            assertThat(component.getConfig()).isEqualTo(normalizedData);
        });
        verify(videoCarouselValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
    }

    /**
     * 缺失、空值或纯空白的团队作品集标题必须在组件分发前被拒绝。
     */
    @Test
    void normalizeAndValidateShouldRequireShareTitleBeforeComponentDispatch() {
        TeamPortfolioConfigDto missingShare = config(List.of(
                component("divider-1", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        missingShare.setShare(null);
        TeamPortfolioConfigDto missingTitle = config(List.of(
                component("divider-1", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        missingTitle.getShare().setTitle(null);
        TeamPortfolioConfigDto blankTitle = config(List.of(
                component("divider-1", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        blankTitle.getShare().setTitle("  ");

        for (TeamPortfolioConfigDto invalid : List.of(missingShare, missingTitle, blankTitle)) {
            assertThatThrownBy(() -> service().normalizeAndValidate(
                    JSON.toJSONString(invalid), 11L, 22L, 3))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("请填写团队作品集标题");
        }
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 草稿和发布入口都必须明确拒绝缺失或非法的团队、作品集和修订上下文。
     */
    @Test
    void draftAndPublishShouldRejectInvalidContextAsBusinessError() {
        TeamPortfolioConfigDto valid = config(List.of(
                component("divider-1", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        List<TeamPortfolioComponentContext> invalidContexts = java.util.Arrays.asList(
                null,
                new TeamPortfolioComponentContext(0L, 22L, 0),
                new TeamPortfolioComponentContext(11L, 0L, 0),
                new TeamPortfolioComponentContext(11L, 22L, -1));

        for (TeamPortfolioComponentContext context : invalidContexts) {
            assertThatThrownBy(() -> service().normalizeForDraft(valid, null, context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("团队作品集组件上下文不正确");
            assertThatThrownBy(() -> service().validateForPublish(valid, context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("团队作品集组件上下文不正确");
        }
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 顶层应只按类型分发，并以稳定排序输出十种受支持组件。
     */
    @Test
    void normalizeAndValidateShouldDispatchExactlyTenTypesAndSortStably() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        configureNormalizers(context);

        List<TeamPortfolioConfigDto.ComponentEnvelope> components = new ArrayList<>();
        int sortOrder = 9000;
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            if (type.getIntroducedAtRevision() > 2) {
                continue;
            }
            components.add(component(type.getCode().toLowerCase(), type.getCode(), sortOrder, true));
            sortOrder -= 1000;
        }

        TeamPortfolioConfigDto normalized = service().normalizeAndValidate(JSON.toJSONString(config(components)),
                context.teamId(), context.portfolioId(), context.revision());

        assertThat(normalized.getComponents()).extracting(TeamPortfolioConfigDto.ComponentEnvelope::getComponentType)
                .containsExactly(
                        TeamPortfolioComponentTypeDict.QR_CONTACT.getCode(),
                        TeamPortfolioComponentTypeDict.CONTACT_FORM.getCode(),
                        TeamPortfolioComponentTypeDict.SCHEDULE_QUERY.getCode(),
                        TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(),
                        TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_LIST.getCode(),
                        TeamPortfolioComponentTypeDict.MEMBER_PORTFOLIO_GRID.getCode(),
                        TeamPortfolioComponentTypeDict.DIVIDER.getCode(),
                        TeamPortfolioComponentTypeDict.SINGLE_WORK.getCode(),
                        TeamPortfolioComponentTypeDict.CAROUSEL.getCode(),
                        TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode());
        assertThat(normalized.getComponents()).allSatisfy(component ->
                assertThat(component.getConfig().getString("dispatcher")).isEqualTo(component.getComponentType()));
        verify(teamProfileValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(carouselValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(singleWorkValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(dividerValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(gridValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(listValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(textValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(scheduleValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(contactValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
        verify(qrValidator).normalizeAndValidate(any(JSONObject.class), eq(context));
    }

    /**
     * 相同排序值和多个空排序值必须保留输入先后顺序。
     */
    @Test
    void normalizeAndValidateShouldPreserveInputOrderForEqualAndNullSortOrders() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(dividerValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(new JSONObject());
        List<TeamPortfolioConfigDto.ComponentEnvelope> components = List.of(
                component("equal-z", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("equal-a", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("null-z", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), null, true),
                component("null-a", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), null, true));

        TeamPortfolioConfigDto normalized = service().normalizeAndValidate(JSON.toJSONString(config(components)),
                context.teamId(), context.portfolioId(), context.revision());

        assertThat(normalized.getComponents()).extracting(TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("equal-z", "equal-a", "null-z", "null-a");
    }

    /**
     * 顶层只接受团队字典明确声明的组件类型。
     */
    @Test
    void normalizeAndValidateShouldRejectUnknownComponentType() {
        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(config(List.of(
                component("unknown", "WORK_GRID", 1000, true)))), 11L, 22L, 0))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 组件实例键必须唯一，避免引用重建时定位歧义。
     */
    @Test
    void normalizeAndValidateShouldRejectDuplicateComponentKeys() {
        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(config(List.of(
                component("duplicate", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("duplicate", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 2000, true)))),
                11L, 22L, 0)).isInstanceOf(BusinessException.class);
    }

    /**
     * 禁用组件也必须使用团队组件字典中的类型。
     */
    @Test
    void normalizeAndValidateShouldRejectUnknownDisabledComponentBeforeDispatch() {
        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(config(List.of(
                component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("unknown", "WORK_GRID", 2000, false)))), 11L, 22L, 0))
                .isInstanceOf(BusinessException.class);
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 禁用组件不能绕过组件实例键唯一约束。
     */
    @Test
    void normalizeAndValidateShouldRejectDuplicateKeyOnDisabledComponentBeforeDispatch() {
        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(config(List.of(
                component("duplicate", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true),
                component("duplicate", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 2000, false)))),
                11L, 22L, 0)).isInstanceOf(BusinessException.class);
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 禁用的第二个团队资料组件也必须触发全局数量约束。
     */
    @Test
    void normalizeAndValidateShouldRejectSecondDisabledTeamProfileBeforeDispatch() {
        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(config(List.of(
                component("profile-a", TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 1000, true),
                component("profile-b", TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 2000, false)))),
                11L, 22L, 0)).isInstanceOf(BusinessException.class);
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 团队资料组件至多一个，且必须使用团队 schema。
     */
    @Test
    void normalizeAndValidateShouldEnforceSchemaAndSingleTeamProfile() {
        TeamPortfolioConfigDto invalidSchema = config(List.of(
                component("profile", TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 1000, true)));
        invalidSchema.setSchemaVersion("standard-personal-v1");
        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(invalidSchema), 11L, 22L, 0))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service().normalizeAndValidate(JSON.toJSONString(config(List.of(
                component("profile-a", TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 1000, true),
                component("profile-b", TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 2000, true)))),
                11L, 22L, 0)).isInstanceOf(BusinessException.class);
    }

    /**
     * 新编辑器草稿必须规范化背景色和全部菜单组件，同时允许空次级菜单。
     */
    @Test
    void normalizeForDraftShouldNormalizeRevisionTwoStyleAndAllMenus() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        when(dividerValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(JSONObject.of("normalized", "divider"));
        when(textValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(JSONObject.of("normalized", "text"));
        TeamPortfolioConfigDto incoming = config(List.of(
                component("component-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 90, true)));
        incoming.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        TeamPortfolioConfigDto.Style style = new TeamPortfolioConfigDto.Style();
        style.setBackgroundColor("#a1b2c3");
        incoming.setStyle(style);
        incoming.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("component-text", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 70, true))),
                menu("nav_empty", "动态", List.of())
        ));

        TeamPortfolioConfigDto normalized = service().normalizeForDraft(incoming, null, context);

        assertThat(normalized.getEditorSchemaRevision())
                .isEqualTo(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(normalized.getStyle().getBackgroundColor()).isEqualTo("#A1B2C3");
        assertThat(normalized.getComponents()).extracting(
                        TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey,
                        TeamPortfolioConfigDto.ComponentEnvelope::getSortOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("component-home", 1000));
        assertThat(normalized.getBottomNav().getItems().getFirst().getComponents()).isNull();
        assertThat(normalized.getBottomNav().getItems().get(1).getComponents()).extracting(
                        TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey,
                        TeamPortfolioConfigDto.ComponentEnvelope::getSortOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("component-text", 1000));
        assertThat(normalized.getBottomNav().getItems().get(2).getComponents()).isEmpty();
    }

    /**
     * 发布必须重新校验并拒绝首个空次级菜单。
     */
    @Test
    void validateForPublishShouldRejectEmptySecondaryMenuWithMenuPrefix() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 4);
        when(dividerValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(new JSONObject());
        TeamPortfolioConfigDto draft = config(List.of(
                component("component-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        draft.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        draft.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of())
        ));

        assertThatThrownBy(() -> service().validateForPublish(draft, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("【作品】至少添加一个组件");
    }

    /**
     * 导航启用时第一菜单为空也必须返回可定位的菜单前缀。
     */
    @Test
    void draftAndPublishShouldPrefixEmptyFirstMenuError() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 4);
        TeamPortfolioConfigDto draft = config(List.of());
        draft.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        draft.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("component-secondary",
                                TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)))
        ));

        assertThatThrownBy(() -> service().normalizeForDraft(draft, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("【主页】至少添加一个组件");
        assertThatThrownBy(() -> service().validateForPublish(draft, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("【主页】至少添加一个组件");
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 顶层组件列表为空引用时必须按空列表处理并返回第一菜单业务文案，不能抛出空指针异常。
     */
    @Test
    void normalizeForDraftShouldTreatNullTopLevelComponentsAsEmptyList() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 4);
        TeamPortfolioConfigDto draft = revisionTwoConfig();
        draft.setComponents(null);

        assertThatThrownBy(() -> service().normalizeForDraft(draft, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("【主页】至少添加一个组件");
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 旧编辑器保存必须以请求顶层组件为准，并保留服务端草稿的新字段和次级菜单。
     */
    @Test
    void normalizeForDraftShouldMergeLegacyRequestWithExistingRevisionTwoFields() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 5);
        when(dividerValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(new JSONObject());
        when(textValidator.normalizeAndValidate(any(JSONObject.class), eq(context)))
                .thenReturn(new JSONObject());
        TeamPortfolioConfigDto incoming = config(List.of(
                component("component-new-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        TeamPortfolioConfigDto existing = config(List.of(
                component("component-old-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        TeamPortfolioConfigDto.Style style = new TeamPortfolioConfigDto.Style();
        style.setBackgroundColor("#151515");
        existing.setStyle(style);
        existing.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("component-secondary", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 1000, true)))
        ));

        TeamPortfolioConfigDto normalized = service().normalizeForDraft(incoming, existing, context);

        assertThat(normalized.getEditorSchemaRevision())
                .isEqualTo(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        assertThat(normalized.getStyle().getBackgroundColor()).isEqualTo("#151515");
        assertThat(normalized.getComponents()).extracting(
                        TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("component-new-home");
        assertThat(normalized.getBottomNav().getItems().get(1).getComponents()).extracting(
                        TeamPortfolioConfigDto.ComponentEnvelope::getComponentKey)
                .containsExactly("component-secondary");
    }

    /**
     * 旧编辑器请求不得覆盖由未来版本服务写入的草稿。
     */
    @Test
    void normalizeForDraftShouldRejectLegacyRequestWhenExistingDraftUsesFutureRevision() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 5);
        TeamPortfolioConfigDto incoming = config(List.of(
                component("component-new-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        TeamPortfolioConfigDto existing = revisionTwoConfig();
        existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT + 1);

        assertThatThrownBy(() -> service().normalizeForDraft(incoming, existing, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前服务暂不支持此团队作品集配置，请稍后重试");
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 当前编辑器请求也不得将未来版本草稿降级覆盖为当前版本。
     */
    @Test
    void normalizeForDraftShouldRejectCurrentRequestWhenExistingDraftUsesFutureRevision() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 5);
        TeamPortfolioConfigDto incoming = revisionTwoConfig();
        TeamPortfolioConfigDto existing = revisionTwoConfig();
        existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT + 1);

        assertThatThrownBy(() -> service().normalizeForDraft(incoming, existing, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前服务暂不支持此团队作品集配置，请稍后重试");
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 组件键和团队资料单例约束必须覆盖全部菜单。
     */
    @Test
    void normalizeForDraftShouldRejectCrossMenuKeyAndProfileConflicts() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 6);
        TeamPortfolioConfigDto duplicateKey = config(List.of(
                component("component-duplicate", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        duplicateKey.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        duplicateKey.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("component-duplicate", TeamPortfolioComponentTypeDict.TEXT_SECTION.getCode(), 1000, true)))
        ));
        TeamPortfolioConfigDto duplicateProfile = config(List.of(
                component("component-profile-home", TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 1000, true)));
        duplicateProfile.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        duplicateProfile.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of(
                        component("component-profile-secondary",
                                TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode(), 1000, true)))
        ));

        assertThatThrownBy(() -> service().normalizeForDraft(duplicateKey, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集组件标识不能重复");
        assertThatThrownBy(() -> service().normalizeForDraft(duplicateProfile, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队作品集最多只能包含一个团队资料组件");
        verifyNoComponentValidatorInteractions();
    }

    /**
     * 新导航和编辑器版本错误必须使用方案约定的稳定文案。
     */
    @Test
    void normalizeForDraftShouldUseStableNavigationAndEditorRevisionMessages() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 7);
        TeamPortfolioConfigDto unsupportedRevision = revisionTwoConfig();
        unsupportedRevision.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT + 1);
        assertThatThrownBy(() -> service().normalizeForDraft(unsupportedRevision, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前服务暂不支持此团队作品集配置，请稍后重试");

        TeamPortfolioConfigDto invalidCount = revisionTwoConfig();
        invalidCount.setBottomNav(bottomNav(menu("nav_home", "主页", null)));
        assertThatThrownBy(() -> service().normalizeForDraft(invalidCount, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("底部导航菜单数量必须为2到4个");

        TeamPortfolioConfigDto blankTitle = revisionTwoConfig();
        blankTitle.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", " ", List.of())));
        assertThatThrownBy(() -> service().normalizeForDraft(blankTitle, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单名称不能为空");

        TeamPortfolioConfigDto longTitle = revisionTwoConfig();
        longTitle.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "成员作品展示", List.of())));
        assertThatThrownBy(() -> service().normalizeForDraft(longTitle, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单名称不能超过5个字");

        TeamPortfolioConfigDto duplicateTitle = revisionTwoConfig();
        duplicateTitle.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "主页", List.of())));
        assertThatThrownBy(() -> service().normalizeForDraft(duplicateTitle, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单名称不能重复");

        TeamPortfolioConfigDto invalidKey = revisionTwoConfig();
        invalidKey.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("works", "作品", List.of())));
        assertThatThrownBy(() -> service().normalizeForDraft(invalidKey, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单标识格式不正确");

        TeamPortfolioConfigDto duplicateMenuKey = revisionTwoConfig();
        duplicateMenuKey.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_home", "作品", List.of())));
        assertThatThrownBy(() -> service().normalizeForDraft(duplicateMenuKey, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("菜单标识不能重复");

        TeamPortfolioConfigDto duplicatedFirstComponents = revisionTwoConfig();
        duplicatedFirstComponents.setBottomNav(bottomNav(
                menu("nav_home", "主页", List.of()),
                menu("nav_works", "作品", List.of())));
        assertThatThrownBy(() -> service().normalizeForDraft(duplicatedFirstComponents, null, context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("第一个菜单不能重复保存组件");
        verifyNoComponentValidatorInteractions();
    }

    private void configureNormalizers(TeamPortfolioComponentContext context) {
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
            JSONObject normalized = new JSONObject();
            normalized.put("dispatcher", type.getCode());
            switch (type) {
                case TEAM_PROFILE -> when(teamProfileValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case CAROUSEL -> when(carouselValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case SINGLE_WORK -> when(singleWorkValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case DIVIDER -> when(dividerValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case MEMBER_PORTFOLIO_GRID -> when(gridValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case MEMBER_PORTFOLIO_LIST -> when(listValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case TEXT_SECTION -> when(textValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case SCHEDULE_QUERY -> when(scheduleValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case CONTACT_FORM -> when(contactValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case QR_CONTACT -> when(qrValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenReturn(normalized);
                case VIDEO_CAROUSEL, STRUCTURED_TEXT_SECTION -> {
                    // revision 3 新组件由独立用例覆盖；本用例只验证 revision 2 已有组件集合。
                }
            }
        }
    }

    private TeamPortfolioConfigValidator service() {
        return new TeamPortfolioConfigValidator(teamProfileValidator, carouselValidator, singleWorkValidator, dividerValidator, gridValidator,
                listValidator, textValidator, scheduleValidator, contactValidator, qrValidator, videoCarouselValidator, structuredTextValidator);
    }

    private void verifyNoComponentValidatorInteractions() {
        verifyNoInteractions(teamProfileValidator, carouselValidator, singleWorkValidator, dividerValidator, gridValidator, listValidator,
                textValidator, scheduleValidator, contactValidator, qrValidator, videoCarouselValidator, structuredTextValidator);
    }

    private TeamPortfolioConfigDto config(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle("团队作品集");
        config.setShare(share);
        config.setComponents(components);
        return config;
    }

    private TeamPortfolioConfigDto revisionTwoConfig() {
        TeamPortfolioConfigDto config = config(List.of(
                component("component-home", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        config.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        config.setBottomNav(bottomNav(
                menu("nav_home", "主页", null),
                menu("nav_works", "作品", List.of())));
        return config;
    }

    private TeamPortfolioConfigDto.ComponentEnvelope component(
            String key,
            String type,
            Integer sortOrder,
            boolean enabled
    ) {
        TeamPortfolioConfigDto.ComponentEnvelope component = new TeamPortfolioConfigDto.ComponentEnvelope();
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setSortOrder(sortOrder);
        component.setEnabled(enabled);
        component.setConfig(new JSONObject());
        return component;
    }

    private TeamPortfolioConfigDto.BottomNav bottomNav(TeamPortfolioConfigDto.BottomNavItem... items) {
        TeamPortfolioConfigDto.BottomNav bottomNav = new TeamPortfolioConfigDto.BottomNav();
        bottomNav.setEnabled(true);
        bottomNav.setItems(List.of(items));
        return bottomNav;
    }

    private TeamPortfolioConfigDto.BottomNavItem menu(
            String key,
            String title,
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        TeamPortfolioConfigDto.BottomNavItem item = new TeamPortfolioConfigDto.BottomNavItem();
        item.setKey(key);
        item.setTitle(title);
        item.setComponents(components);
        return item;
    }
}
