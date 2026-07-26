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
    @Mock
    private TeamScheduleQueryComponentValidator scheduleValidator;
    @Mock
    private TeamContactFormComponentValidator contactValidator;
    @Mock
    private TeamQrContactComponentValidator qrValidator;

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
     * 顶层应只按类型分发，并以稳定排序输出十种受支持组件。
     */
    @Test
    void normalizeAndValidateShouldDispatchExactlyTenTypesAndSortStably() {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 3);
        configureNormalizers(context);

        List<TeamPortfolioConfigDto.ComponentEnvelope> components = new ArrayList<>();
        int sortOrder = 9000;
        for (TeamPortfolioComponentTypeDict type : TeamPortfolioComponentTypeDict.values()) {
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
            }
        }
    }

    private TeamPortfolioConfigValidator service() {
        return new TeamPortfolioConfigValidator(teamProfileValidator, carouselValidator, singleWorkValidator, dividerValidator, gridValidator,
                listValidator, textValidator, scheduleValidator, contactValidator, qrValidator);
    }

    private void verifyNoComponentValidatorInteractions() {
        verifyNoInteractions(teamProfileValidator, carouselValidator, singleWorkValidator, dividerValidator, gridValidator, listValidator,
                textValidator, scheduleValidator, contactValidator, qrValidator);
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
}
