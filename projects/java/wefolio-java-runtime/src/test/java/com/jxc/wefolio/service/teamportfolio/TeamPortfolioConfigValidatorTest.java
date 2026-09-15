package com.jxc.wefolio.service.teamportfolio;

import java.util.Map;
import com.jxc.wefolio.dto.BackgroundAudioConfigDto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.service.PortfolioContactInfoConfigSupport;
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

    /** 团队联系信息边框经过草稿序列化、发布及旧版跨菜单移动后完整保留。 */
    @Test
    void contactBorderSurvivesDraftPublishAndLegacyMenuMove() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        var appearance = Map.<String, Object>of("contactBorder", true, "contactBorderWidthRpx", 8,
                "contactBorderColor", "#AABBCC", "horizontalMarginRpx", 32, "verticalMarginRpx", 24);
        var values = new JSONObject(appearance); values.put("contactPhone", "123"); values.put("contactBorderColor", "#aabbcc");
        var contact = component("contact", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true);
        contact.setConfig(values);
        var input = config(List.of(contact)); input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        var saved = JSON.parseObject(JSON.toJSONString(service().normalizeForDraft(input, null, context)), TeamPortfolioConfigDto.class);
        assertThat(saved.getComponents().getFirst().getConfig()).containsAllEntriesOf(appearance);
        service().validateForPublish(saved, context);
        for (boolean explicitDefaults : List.of(false, true)) {
            var editedValues = new JSONObject(); editedValues.put("contactPhone", "456");
            if (explicitDefaults) { editedValues.putAll(PortfolioContactInfoConfigSupport.normalize(Map.of())); editedValues.put("contactPhone", "456"); }
            var edited = component("contact", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true);
            edited.setConfig(editedValues);
            var home = component("other", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true);
            home.setConfig(JSONObject.of("contactWechat", "小映"));
            var incoming = config(List.of(home)); incoming.setEditorSchemaRevision(8);
            incoming.setBottomNav(bottomNav(menu("nav_home", "首页", null), menu("nav_contact", "联系", List.of(edited))));
            var merged = service().normalizeForDraft(incoming, saved, context);
            assertThat(merged.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig())
                    .containsAllEntriesOf(appearance).containsEntry("contactPhone", "456");
            assertThat(editedValues).doesNotContainEntry("contactBorder", true);
            incoming.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
            var current = service().normalizeForDraft(incoming, saved, context);
            assertThat(current.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig())
                    .containsEntry("contactBorder", false).containsEntry("horizontalMarginRpx", 0);
        }
        values.put("contactBorder", false);
        assertThat(service().normalizeForDraft(input, saved, context).getComponents().getFirst().getConfig())
                .containsEntry("contactBorder", false).containsEntry("contactBorderWidthRpx", 8)
                .containsEntry("contactBorderColor", "#AABBCC").containsEntry("horizontalMarginRpx", 32)
                .containsEntry("verticalMarginRpx", 24);
    }

    /** 团队根组件与菜单组件在所有复制路径中保留显式空值，交由草稿与发布校验拒绝。 */
    @Test
    void rejectsExplicitNullContactAppearanceInRootAndMenu() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        var existing = config(List.of()); existing.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        for (String field : PortfolioContactInfoConfigSupport.APPEARANCE_FIELDS) {
            for (boolean inMenu : List.of(false, true)) {
                var values = JSONObject.of("contactPhone", "123"); values.put(field, null);
                var contact = component("contact", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true);
                contact.setConfig(values);
                var home = component("other", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true);
                home.setConfig(JSONObject.of("contactWechat", "小映"));
                var input = config(inMenu ? List.of(home) : List.of(contact));
                input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
                if (inMenu) { input.setBottomNav(bottomNav(menu("nav_home", "首页", null), menu("nav_contact", "联系", List.of(contact)))); }
                for (TeamPortfolioConfigDto previous : new TeamPortfolioConfigDto[]{null, existing}) {
                    assertThatThrownBy(() -> service().normalizeForDraft(input, previous, context))
                            .as("联系信息外观显式空值 %s，菜单位 %s", field, inMenu).isInstanceOf(BusinessException.class)
                            .hasMessageContaining(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
                }
                assertThatThrownBy(() -> service().validateForPublish(input, context)).isInstanceOf(BusinessException.class)
                        .hasMessageContaining(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
            }
        }
    }

    /** 团队双列留白经过序列化和发布保留，旧版保存保护未知字段，新版允许清零。 */
    @Test void gridMarginsSurviveDraftPublishAndLegacySave() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        var grid = component("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), 1000, true);
        var input = config(List.of(grid)); input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        JSONObject values = JSON.parseObject(JSON.toJSONString(service().normalizeForDraft(input, null, context)
                .getComponents().getFirst().getConfig()));
        values.getJSONArray("cells").getJSONObject(0).getJSONArray("blocks").getJSONObject(0)
                .getJSONArray("runs").getJSONObject(0).put("text", "双列文字");
        values.put("horizontalMarginRpx", 32); values.put("verticalMarginRpx", 24); grid.setConfig(values);
        var saved = JSON.parseObject(JSON.toJSONString(service().normalizeForDraft(input, null, context)), TeamPortfolioConfigDto.class);
        assertThat(saved.getComponents().getFirst().getConfig()).containsEntry("horizontalMarginRpx", 32)
                .containsEntry("verticalMarginRpx", 24);
        service().validateForPublish(saved, context);
        for (boolean explicitDefault : List.of(false, true)) {
            if (explicitDefault) { values.put("horizontalMarginRpx", 0); values.put("verticalMarginRpx", 0); }
            else { values.remove("horizontalMarginRpx"); values.remove("verticalMarginRpx"); }
            input.setEditorSchemaRevision(7);
            assertThat(service().normalizeForDraft(input, saved, context).getComponents().getFirst().getConfig())
                    .containsEntry("columns", 2).containsEntry("horizontalMarginRpx", 32).containsEntry("verticalMarginRpx", 24);
            input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
            assertThat(service().normalizeForDraft(input, saved, context).getComponents().getFirst().getConfig())
                    .containsEntry("horizontalMarginRpx", 0).containsEntry("verticalMarginRpx", 0);
        }
    }

    /** 团队兼容复制不能把留白字段的显式空值转成缺省合法值，菜单中的网格也须检查。 */
    @Test void rejectsExplicitNullGridMarginsInDraftAndPublish() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        for (String field : List.of("horizontalMarginRpx", "verticalMarginRpx")) {
            var grid = component("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), 1000, true);
            var input = config(List.of(grid)); input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
            grid.setConfig(service().normalizeForDraft(input, null, context).getComponents().getFirst().getConfig());
            grid.getConfig().put(field, null);
            for (boolean inMenu : List.of(false, true)) {
                if (inMenu) {
                    input.setComponents(List.of(component("contact", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true)));
                    input.setBottomNav(bottomNav(menu("nav_home", "首页", null), menu("nav_works", "作品", List.of(grid))));
                }
                assertThatThrownBy(() -> service().normalizeForDraft(input, null, context))
                        .as("草稿不能丢弃显式空值 %s", field).isInstanceOf(BusinessException.class);
                assertThatThrownBy(() -> service().validateForPublish(input, context))
                        .as("发布不能丢弃显式空值 %s", field).isInstanceOf(BusinessException.class);
            }
        }
    }

    /** 八行合并网格经过团队草稿保存和 JSON 往返后仍保留完整跨度并允许发布。 */
    @Test void eightRowGridSurvivesDraftSerializationAndPublishValidation() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        var grid = component("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), 1000, true);
        grid.setConfig(JSONObject.of("rows", 8, "columns", 1, "columnWeights", List.of(1),
                "rowMinHeightsRpx", List.of(180, 180, 180, 180, 180, 180, 180, 180),
                "cells", List.of(Map.of("cellKey", "merged", "row", 0, "column", 0, "rowSpan", 8, "columnSpan", 1,
                        "blocks", List.of(Map.of("blockKey", "block", "runs", List.of(Map.of("runKey", "run", "text", "八行文字"))))))));
        var input = config(List.of(grid));
        input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        var saved = JSON.parseObject(JSON.toJSONString(service().normalizeForDraft(input, null, context)), TeamPortfolioConfigDto.class);
        var savedGrid = saved.getComponents().getFirst().getConfig();
        assertThat(savedGrid).containsEntry("rows", 8).containsEntry("columns", 1);
        assertThat(savedGrid.getJSONArray("rowMinHeightsRpx")).hasSize(8);
        assertThat(savedGrid.getJSONArray("cells").getJSONObject(0)).containsEntry("rowSpan", 8);
        service().validateForPublish(saved, context);
    }

    /** 新边框字段经过真实草稿入口和 JSON 往返保留，上一版编辑器保存不清空。 */
    @Test void gridBorderOptionsSurviveDraftSerializationAndPreviousEditorSave() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        var grid = component("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), 1000, true);
        var input = config(List.of(grid)); input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        var initial = service().normalizeForDraft(input, null, context);
        var values = initial.getComponents().getFirst().getConfig();
        values.put("cellBorder", true); values.put("cellBorderWidthRpx", 8); values.put("cellBorderColor", "#aabbcc");
        grid.setConfig(values);
        var saved = JSON.parseObject(JSON.toJSONString(service().normalizeForDraft(input, null, context)), TeamPortfolioConfigDto.class);
        assertThat(saved.getComponents().getFirst().getConfig()).containsEntry("cellBorderWidthRpx", 8)
                .containsEntry("cellBorderColor", "#AABBCC");
        values.remove("cellBorderWidthRpx"); values.remove("cellBorderColor"); values.put("cellBorder", false);
        input.setEditorSchemaRevision(5);
        var legacySave = service().normalizeForDraft(input, saved, context);
        assertThat(legacySave.getComponents().getFirst().getConfig()).containsEntry("cellBorder", false)
                .containsEntry("cellBorderWidthRpx", 8).containsEntry("cellBorderColor", "#AABBCC");
        input.setEditorSchemaRevision(TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT);
        values.put("cellBorderWidthRpx", null);
        assertThatThrownBy(() -> service().normalizeForDraft(input, saved, context)).isInstanceOf(BusinessException.class);
        values.remove("cellBorderWidthRpx"); values.put("cellBorderColor", null);
        assertThatThrownBy(() -> service().normalizeForDraft(input, saved, context)).isInstanceOf(BusinessException.class);
    }

    /** 联系和网格均走真实纯规则，菜单空网格阻止发布。 */
    @Test void newTextComponentsUseSharedDraftAndPublishRules() {
        var contact = component("contact", TeamPortfolioComponentTypeDict.CONTACT_INFO.getCode(), 1000, true);
        contact.setConfig(JSONObject.of("contactWechat", "  小映 "));
        var grid = component("grid", TeamPortfolioComponentTypeDict.TEXT_GRID.getCode(), 1000, true);
        var input = config(List.of(contact)); input.setEditorSchemaRevision(5);
        input.setBottomNav(bottomNav(menu("nav_home", "首页", null), menu("nav_works", "作品", List.of(grid))));
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        var draft = service().normalizeForDraft(input, null, context);
        assertThat(draft.getComponents().getFirst().getConfig()).containsEntry("contactWechat", "小映");
        assertThat(draft.getBottomNav().getItems().get(1).getComponents().getFirst().getConfig().getJSONArray("cells")).hasSize(4);
        assertThatThrownBy(() -> service().validateForPublish(draft, context)).isInstanceOf(BusinessException.class).hasMessage("请至少添加一处文字");
    }

    /** 背景字段缺省保留，显式提交直接生效，不依赖能力头。 */
    @Test
    void teamBackgroundAudioMergeShouldUseFieldPresence() {
        TeamPortfolioConfigDto base = config(List.of(component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 1);
        TeamPortfolioConfigDto stored = service().normalizeForDraft(base, null, context);
        assertThat(stored.getBackgroundAudio().getEnabled()).isFalse();
        stored.getBackgroundAudio().setWorkId(19L);
        stored.getBackgroundAudio().setEnabled(true);
        assertThat(service().normalizeForDraft(base, stored, context).getBackgroundAudio().getWorkId()).isEqualTo(19L);
        base.setBackgroundAudio(new BackgroundAudioConfigDto());
        assertThat(service().normalizeForDraft(base, stored, context).getBackgroundAudio().getWorkId()).isNull();
        assertThat(stored.getBackgroundAudio().getWorkId()).isEqualTo(19L);
        verify(singleWorkValidator).validateAudio(19L, context);
    }

    /** 新字段版本保护与音频缺省/显式关闭独立组合，当前端 false 仍能生效。 */
    @Test void displayOptionsAndAudioPresenceRulesComposeForLegacyAndCurrentEditors() {
        var context = new TeamPortfolioComponentContext(11L, 22L, 1);
        when(singleWorkValidator.normalizeAndValidate(any(JSONObject.class), eq(context))).thenAnswer(call -> {
            JSONObject result = new JSONObject((JSONObject) call.getArgument(0));
            result.put("openMode", com.jxc.wefolio.service.PortfolioComponentDisplayOptionsSupport.openMode(result));
            result.put("detailOptions", com.jxc.wefolio.service.PortfolioComponentDisplayOptionsSupport.detailOptions(result));
            return result;
        });
        var saved = component("single", "SINGLE_WORK", 1000, true);
        saved.setConfig(JSONObject.of("memberUserId", 7L, "workId", 9L, "openMode", "DETAIL_PAGE",
                "detailOptions", JSONObject.of("showTitle", true, "showDescription", true)));
        var existing = config(List.of(saved)); existing.setEditorSchemaRevision(5);
        var audio = new BackgroundAudioConfigDto(); audio.setEnabled(true); existing.setBackgroundAudio(audio);
        for (Integer revision : new Integer[]{null, 4, 5}) {
            for (boolean explicitAudio : List.of(false, true)) {
                for (boolean explicitOptions : List.of(false, true)) {
                    var incomingSingle = component("single", "SINGLE_WORK", 1000, true);
                    var values = JSONObject.of("memberUserId", 7L, "workId", 9L, "showDescription", false);
                    if (explicitOptions) {
                        values.put("openMode", "INLINE");
                        values.put("detailOptions", JSONObject.of("showTitle", false, "showDescription", false));
                    }
                    incomingSingle.setConfig(values);
                    var incoming = config(List.of(incomingSingle)); incoming.setEditorSchemaRevision(revision);
                    if (explicitAudio) { incoming.setBackgroundAudio(new BackgroundAudioConfigDto()); }
                    var normalized = service().normalizeForDraft(incoming, existing, context);
                    var actual = normalized.getComponents().getFirst().getConfig();
                    boolean current = Integer.valueOf(5).equals(revision);
                    assertThat(actual.getString("openMode")).isEqualTo(current ? "INLINE" : "DETAIL_PAGE");
                    assertThat(actual.getJSONObject("detailOptions").getBoolean("showTitle"))
                            .isEqualTo(!current || !explicitOptions);
                    assertThat(actual.getBoolean("showDescription")).isFalse();
                    assertThat(normalized.getBackgroundAudio().getEnabled()).isEqualTo(!explicitAudio);
                    assertThat(existing.getBackgroundAudio().getEnabled()).isTrue();
                }
            }
        }
    }

    /** 团队旧请求保留背景音频，开启但无选择的草稿不能发布。 */
    @Test
    void backgroundAudioShouldBePreservedForLegacyTeamSave() {
        TeamPortfolioConfigDto base = config(List.of(component("divider", TeamPortfolioComponentTypeDict.DIVIDER.getCode(), 1000, true)));
        JSONObject json = JSON.parseObject(JSON.toJSONString(base));
        json.put("backgroundAudio", Map.of("enabled", true, "displayStyle", "MINI_PLAYER"));
        TeamPortfolioConfigDto stored = json.toJavaObject(TeamPortfolioConfigDto.class);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 22L, 1);
        TeamPortfolioConfigDto draft = service().normalizeForDraft(base, stored, context);
        JSONObject audio = JSON.parseObject(JSON.toJSONString(draft)).getJSONObject("backgroundAudio");
        assertThat(audio).isNotNull();
        assertThat(audio.getBoolean("enabled")).isTrue();
        assertThat(audio.getString("displayStyle")).isEqualTo("MINI_PLAYER");
        assertThatThrownBy(() -> service().validateForPublish(draft, context)).isInstanceOf(BusinessException.class)
                .hasMessage("开启背景音频后，请从音频作品中选择");
    }

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
