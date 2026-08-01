package com.jxc.wefolio.service.teamportfolio.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentConfig;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准团队展示型组件的独立契约测试。
 */
@ExtendWith(MockitoExtension.class)
class TeamDisplayComponentsTest {

    /** 团队资料组件源码路径。 */
    private static final Path TEAM_PROFILE_SOURCE = Path.of("service/teamportfolio/component/teamprofile");

    /** 分割线组件源码路径。 */
    private static final Path DIVIDER_SOURCE = Path.of("service/teamportfolio/component/divider");

    /** 文字说明组件源码路径。 */
    private static final Path TEXT_SECTION_SOURCE = Path.of("service/teamportfolio/component/textsection");

    /** 团队状态正常值。 */
    private static final String TEAM_STATUS_ACTIVE = "ACTIVE";

    /** 组件键。 */
    private static final String COMPONENT_KEY = "team-profile-1";

    /** 组件路径。 */
    private static final String COMPONENT_PATH = "components[2]";

    /** 团队 Mapper。 */
    @Mock
    private TeamEntityMapper teamEntityMapper;

    /** 团队作品集素材服务。 */
    @Mock
    private TeamPortfolioAssetService teamPortfolioAssetService;

    /**
     * 团队资料应校验当前团队后保留作品集私有快照，并构建精确引用。
     */
    @Test
    void teamProfileShouldPreservePortfolioSnapshotAndExtractExactReference() {
        TeamEntity team = activeTeam(11L, "当前头像", "当前团队", "当前简介");
        when(teamEntityMapper.selectById(11L)).thenReturn(team);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        JSONObject input = JSON.parseObject("""
                {"team":{"teamId":11,"avatarUrl":"作品集头像","teamName":"作品集团队","intro":"作品集简介"}}
                """);
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);

        JSONObject normalized = validator.normalizeAndValidate(input, context);
        JSONObject rendered = new TeamProfileComponentRenderer().render(normalized, context);
        List<PortfolioReferenceEntity> references = new TeamProfileComponentReferenceExtractor()
                .extract(COMPONENT_KEY, COMPONENT_PATH, normalized, context);

        assertThat(normalized.getJSONObject("team"))
                .containsEntry("avatarUrl", "作品集头像")
                .containsEntry("teamName", "作品集团队")
                .containsEntry("intro", "作品集简介");
        assertThat(normalized.getJSONObject("team").keySet())
                .containsExactlyInAnyOrder("teamId", "avatarUrl", "teamName", "intro");
        assertThat(normalized.getJSONObject("team").getLong("teamId")).isEqualTo(11L);
        assertThat(normalized.getJSONObject("visibleFields"))
                .containsEntry("avatar", true)
                .containsEntry("teamName", true)
                .containsEntry("intro", true);
        assertThat(rendered).isNotSameAs(normalized);
        assertThat(rendered.getJSONObject("team").keySet())
                .containsExactlyInAnyOrder("teamId", "avatarUrl", "teamName", "intro");
        assertThat(rendered.getJSONObject("team").getLong("teamId")).isEqualTo(11L);
        assertThat(rendered.getJSONObject("team").getString("avatarUrl")).isEqualTo("作品集头像");
        assertThat(rendered.getJSONObject("team").getString("teamName")).isEqualTo("作品集团队");
        assertThat(rendered.getJSONObject("team").getString("intro")).isEqualTo("作品集简介");
        assertThat(rendered.getJSONObject("visibleFields"))
                .containsEntry("avatar", true)
                .containsEntry("teamName", true)
                .containsEntry("intro", true);
        assertThat(references).hasSize(1);
        PortfolioReferenceEntity reference = references.getFirst();
        assertThat(reference.getPortfolioId()).isEqualTo(21L);
        assertThat(reference.getReferenceType()).isEqualTo("TEAM_PROFILE");
        assertThat(reference.getReferenceId()).isEqualTo(11L);
        assertThat(reference.getComponentKey()).isEqualTo(COMPONENT_KEY);
        assertThat(reference.getComponentPath()).isEqualTo(COMPONENT_PATH + ".config.team");
        assertThat(reference.getSortOrder()).isZero();
        assertThat(reference.getIsValid()).isEqualTo(1);
        assertThat(reference.getConfigScope()).isNull();
        JSONObject referenceSnapshot = JSON.parseObject(reference.getSnapshotJson());
        assertThat(referenceSnapshot.keySet())
                .containsExactlyInAnyOrder("teamId", "avatarUrl", "teamName", "intro");
        assertThat(referenceSnapshot.getLong("teamId")).isEqualTo(normalized.getJSONObject("team").getLong("teamId"));
        assertThat(referenceSnapshot.getString("avatarUrl"))
                .isEqualTo(normalized.getJSONObject("team").getString("avatarUrl"));
        assertThat(referenceSnapshot.getString("teamName"))
                .isEqualTo(normalized.getJSONObject("team").getString("teamName"));
        assertThat(referenceSnapshot.getString("intro"))
                .isEqualTo(normalized.getJSONObject("team").getString("intro"));
        verify(teamEntityMapper, times(1)).selectById(11L);
    }

    /**
     * 团队资料作品集快照应裁剪文本并拒绝空名称或超长字段。
     */
    @Test
    void teamProfileShouldNormalizeAndValidatePortfolioSnapshotFields() {
        TeamEntity team = activeTeam(11L, "当前头像", "当前团队", "当前简介");
        when(teamEntityMapper.selectById(11L)).thenReturn(team);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);

        JSONObject normalized = validator.normalizeAndValidate(JSON.parseObject("""
                {"team":{"teamId":11,"avatarUrl":"","teamName":"  作品集团队  ","intro":"  作品集简介  "}}
                """), context);
        assertThat(normalized.getJSONObject("team"))
                .containsEntry("avatarUrl", "")
                .containsEntry("teamName", "作品集团队")
                .containsEntry("intro", "作品集简介");

        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"team\":{\"teamId\":11,\"teamName\":\"   \"}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请填写团队名称");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"team\":{\"teamId\":11,\"teamName\":\"%s\"}}"
                        .formatted("名".repeat(101))), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队名称不能超过100字");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"team\":{\"teamId\":11,\"teamName\":\"团队\",\"intro\":\"%s\"}}"
                        .formatted("介".repeat(1001))), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队简介不能超过1000字");
    }

    /**
     * 团队当前头像可直接复用，自定义头像必须通过当前作品集素材校验。
     */
    @Test
    void teamProfileShouldValidateOnlyCustomPortfolioAvatar() {
        TeamEntity team = activeTeam(11L, "https://cdn.example.com/current-team.png", "当前团队", "当前简介");
        when(teamEntityMapper.selectById(11L)).thenReturn(team);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);

        validator.normalizeAndValidate(JSON.parseObject("""
                {"team":{"teamId":11,"avatarUrl":"https://cdn.example.com/current-team.png","teamName":"作品集团队"}}
                """), context);
        validator.normalizeAndValidate(JSON.parseObject("""
                {"team":{"teamId":11,"avatarUrl":"","teamName":"作品集团队"}}
                """), context);
        validator.normalizeAndValidate(JSON.parseObject("""
                {"team":{"teamId":11,"avatarUrl":"https://cdn.example.com/custom-portfolio.png","teamName":"作品集团队"}}
                """), context);

        verify(teamPortfolioAssetService, times(1)).validateUploadedImageUrl(
                11L, 21L, "https://cdn.example.com/custom-portfolio.png");
    }

    /**
     * 团队资料组件必须自行拥有默认 config 和 envelope，顶层服务只消费工厂结果。
     */
    @Test
    void teamProfileShouldOwnDefaultConfigAndEnvelopeFactories() {
        JSONObject defaultConfig = TeamProfileComponentConfig.defaultConfig();
        TeamPortfolioConfigDto.ComponentEnvelope envelope = TeamProfileComponentConfig.defaultEnvelope();

        assertThat(defaultConfig).isEmpty();
        assertThat(envelope.getComponentKey()).isEqualTo("team-profile");
        assertThat(envelope.getComponentType()).isEqualTo("TEAM_PROFILE");
        assertThat(envelope.getSortOrder()).isEqualTo(1000);
        assertThat(envelope.getEnabled()).isTrue();
        assertThat(envelope.getConfig()).isEqualTo(defaultConfig).isNotSameAs(defaultConfig);
    }

    /**
     * 团队标识缺失或显式为空时，应忽略客户端快照并由服务端重建完整资料。
     */
    @Test
    void teamProfileShouldRebuildServerSnapshotWhenTeamIdIsOmittedOrNull() {
        TeamEntity team = activeTeam(11L, "当前头像", "当前团队", "当前简介");
        when(teamEntityMapper.selectById(11L)).thenReturn(team);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);
        List<JSONObject> inputs = List.of(
                JSON.parseObject("{\"team\":{}}"),
                JSON.parseObject("{\"team\":{\"avatarUrl\":\"伪造头像\",\"teamName\":\"伪造名称\",\"intro\":\"伪造简介\"}}"),
                JSON.parseObject("{\"team\":{\"teamId\":null}}")
        );

        for (JSONObject input : inputs) {
            JSONObject snapshot = validator.normalizeAndValidate(input, context).getJSONObject("team");

            assertThat(snapshot.keySet())
                    .containsExactlyInAnyOrder("teamId", "avatarUrl", "teamName", "intro");
            assertThat(snapshot.getLong("teamId")).isEqualTo(11L);
            assertThat(snapshot.getString("avatarUrl")).isEqualTo("当前头像");
            assertThat(snapshot.getString("teamName")).isEqualTo("当前团队");
            assertThat(snapshot.getString("intro")).isEqualTo("当前简介");
        }

        verify(teamEntityMapper, times(3)).selectById(11L);
    }

    /**
     * 团队资料应将客户端异常结构统一转换为资料不匹配提示。
     */
    @Test
    void teamProfileShouldRejectMalformedClientTeamSnapshot() {
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        List<JSONObject> malformedInputs = List.of(
                JSON.parseObject("{\"team\":\"invalid\"}"),
                JSON.parseObject("{\"team\":[{\"teamId\":11}]}"),
                JSON.parseObject("{\"team\":{\"teamId\":{\"id\":11}}}"),
                JSON.parseObject("{\"team\":{\"teamId\":\"not-a-number\"}}")
        );

        for (JSONObject malformedInput : malformedInputs) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(malformedInput, context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("团队资料与当前作品集不匹配");
        }

        verify(teamEntityMapper, times(0)).selectById(11L);
    }

    /**
     * 团队标识必须在 Bean 转换前拒绝小数和超出 Long 范围的整数。
     */
    @Test
    void teamProfileShouldRejectLossyOrOutOfRangeTeamIdBeforeBeanConversion() {
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);
        TeamProfileComponentRenderer renderer = new TeamProfileComponentRenderer();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        List<Object> invalidTeamIds = List.of(
                new BigDecimal("11.9"),
                new BigInteger("9223372036854775808")
        );

        for (Object invalidTeamId : invalidTeamIds) {
            JSONObject team = new JSONObject();
            team.put("teamId", invalidTeamId);
            JSONObject config = new JSONObject();
            config.put("team", team);

            assertThatThrownBy(() -> validator.normalizeAndValidate(config, context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("团队资料与当前作品集不匹配");
            assertThatThrownBy(() -> renderer.render(config, context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("团队资料与当前作品集不匹配");
        }

        verify(teamEntityMapper, times(0)).selectById(11L);
    }

    /**
     * 团队资料应拒绝与当前作品集不一致的团队标识。
     */
    @Test
    void teamProfileShouldRejectMismatchedTeamIdBeforeLoadingTeam() {
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);
        JSONObject input = JSON.parseObject("{\"team\":{\"teamId\":12}}");

        assertThatThrownBy(() -> validator.normalizeAndValidate(
                input, new TeamPortfolioComponentContext(11L, 21L, 3)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队资料与当前作品集不匹配");

        verify(teamEntityMapper, times(0)).selectById(11L);
    }

    /**
     * 团队资料应拒绝不存在或已停用的当前团队。
     */
    @Test
    void teamProfileShouldRejectMissingOrInactiveTeam() {
        TeamProfileComponentValidator validator = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        when(teamEntityMapper.selectById(11L)).thenReturn(null);

        assertThatThrownBy(() -> validator.normalizeAndValidate(new JSONObject(), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队资料不存在或不可用");

        TeamEntity inactive = activeTeam(11L, "头像", "团队", "简介");
        inactive.setStatus("DISSOLVED");
        when(teamEntityMapper.selectById(11L)).thenReturn(inactive);
        assertThatThrownBy(() -> validator.normalizeAndValidate(new JSONObject(), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("团队资料不存在或不可用");
    }

    /**
     * 团队资料渲染结果必须与规范化输入脱离。
     */
    @Test
    void teamProfileRendererShouldReturnDetachedSnapshot() {
        JSONObject normalized = JSON.parseObject("{\"team\":{\"teamId\":11,\"teamName\":\"团队\"}}");

        JSONObject rendered = new TeamProfileComponentRenderer().render(
                normalized, new TeamPortfolioComponentContext(11L, 21L, 3));
        normalized.getJSONObject("team").put("teamName", "被修改");

        assertThat(rendered.getJSONObject("team").getString("teamName")).isEqualTo("团队");
    }

    /**
     * 团队资料展示开关应保留到配置，并在预览和访客共用的渲染结果中清空隐藏字段。
     */
    @Test
    void teamProfileShouldApplyVisibleFieldsToRenderedSnapshot() {
        TeamEntity team = activeTeam(11L, "当前头像", "当前团队", "当前简介");
        when(teamEntityMapper.selectById(11L)).thenReturn(team);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        JSONObject normalized = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService)
                .normalizeAndValidate(JSON.parseObject("""
                        {
                          "team": {
                            "teamId": 11,
                            "avatarUrl": "当前头像",
                            "teamName": "作品集团队",
                            "intro": "作品集简介"
                          },
                          "visibleFields": {
                            "avatar": false,
                            "teamName": true,
                            "intro": false
                          }
                        }
                        """), context);

        JSONObject rendered = new TeamProfileComponentRenderer().render(normalized, context);

        assertThat(normalized.getJSONObject("visibleFields"))
                .containsEntry("avatar", false)
                .containsEntry("teamName", true)
                .containsEntry("intro", false);
        assertThat(normalized.getJSONObject("team"))
                .containsEntry("avatarUrl", "当前头像")
                .containsEntry("teamName", "作品集团队")
                .containsEntry("intro", "作品集简介");
        assertThat(rendered.getJSONObject("visibleFields"))
                .containsEntry("avatar", false)
                .containsEntry("teamName", true)
                .containsEntry("intro", false);
        assertThat(rendered.getJSONObject("team"))
                .containsEntry("avatarUrl", "")
                .containsEntry("teamName", "作品集团队")
                .containsEntry("intro", "");
    }

    /**
     * 团队快照中的空头像和简介必须作为显式 null 保留到渲染和引用快照。
     */
    @Test
    void teamProfileShouldKeepExplicitNullFieldsInNormalizedRenderedAndReferenceSnapshots() {
        TeamEntity team = activeTeam(11L, null, "当前团队", null);
        when(teamEntityMapper.selectById(11L)).thenReturn(team);
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);

        JSONObject normalized = new TeamProfileComponentValidator(
                teamEntityMapper, teamPortfolioAssetService)
                .normalizeAndValidate(new JSONObject(), context);
        JSONObject rendered = new TeamProfileComponentRenderer().render(normalized, context);
        PortfolioReferenceEntity reference = new TeamProfileComponentReferenceExtractor()
                .extract(COMPONENT_KEY, COMPONENT_PATH, normalized, context)
                .getFirst();
        JSONObject referenceSnapshot = JSON.parseObject(reference.getSnapshotJson());

        assertExplicitNullTeamSnapshot(normalized.getJSONObject("team"));
        assertExplicitNullTeamSnapshot(rendered.getJSONObject("team"));
        assertExplicitNullTeamSnapshot(referenceSnapshot);
        verify(teamEntityMapper, times(1)).selectById(11L);
    }

    /**
     * 分割线应补齐默认值，并只返回自己的展示字段。
     */
    @Test
    void dividerShouldApplyDefaultsAndRenderDetachedData() {
        TeamDividerComponentValidator validator = new TeamDividerComponentValidator();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);

        JSONObject normalized = validator.normalizeAndValidate(new JSONObject(), context);
        JSONObject rendered = new TeamDividerComponentRenderer().render(normalized, context);
        normalized.put("color", "BLACK");

        assertThat(normalized).containsEntry("heightPx", 16);
        assertThat(rendered).isEqualTo(JSON.parseObject("{\"color\":\"GRAY\",\"heightPx\":16}"));
        assertThat(new TeamDividerComponentReferenceExtractor()
                .extract(COMPONENT_KEY, COMPONENT_PATH, rendered, context))
                .isEmpty();
    }

    /**
     * 分割线应拒绝不支持颜色以及非正整数高度。
     */
    @Test
    void dividerShouldRejectUnsupportedColorAndInvalidHeight() {
        TeamDividerComponentValidator validator = new TeamDividerComponentValidator();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);

        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"color\":\"RED\"}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线颜色不支持");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"heightPx\":0}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线高度必须大于0");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"heightPx\":\"16\"}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线高度必须大于0");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"color\":{\"value\":\"BLACK\"}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线颜色不支持");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"heightPx\":{\"value\":16}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("分割线高度必须大于0");
    }

    /**
     * 文字说明应保留作者输入、补齐默认对齐方式，并返回脱离输入的展示数据。
     */
    @Test
    void textSectionShouldPreserveContentAndApplyDefaultAlignment() {
        TeamTextSectionComponentValidator validator = new TeamTextSectionComponentValidator();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        JSONObject normalized = validator.normalizeAndValidate(
                JSON.parseObject("{\"content\":\"  保留空格  \"}"), context);
        JSONObject rendered = new TeamTextSectionComponentRenderer().render(normalized, context);
        normalized.put("content", "被修改");

        assertThat(rendered).isEqualTo(JSON.parseObject("""
                {
                  "content":"  保留空格  ",
                  "alignment":"LEFT",
                  "fontFamily":"SYSTEM",
                  "fontSizeRpx":32
                }
                """));
        assertThat(new TeamTextSectionComponentReferenceExtractor()
                .extract(COMPONENT_KEY, COMPONENT_PATH, rendered, context))
                .isEmpty();
    }

    /**
     * 团队文字说明应在 DTO 转换前校验排版值并保留合法配置。
     */
    @Test
    void textSectionShouldValidateTypographyBeforeDtoConversion() {
        TeamTextSectionComponentValidator validator = new TeamTextSectionComponentValidator();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);

        JSONObject normalized = validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"团队说明",
                  "alignment":"CENTER",
                  "fontFamily":"WECHAT_SANS_SS",
                  "fontSizeRpx":48
                }
                """), context);
        assertThat(normalized)
                .containsEntry("fontFamily", "WECHAT_SANS_SS")
                .containsEntry("fontSizeRpx", 48);
        assertThat(validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"团队说明",
                  "fontFamily":"SYSTEM",
                  "fontSizeRpx":20
                }
                """), context))
                .containsEntry("fontFamily", "SYSTEM")
                .containsEntry("fontSizeRpx", 20);

        for (String json : List.of(
                "{\"content\":\"说明\",\"fontSizeRpx\":28.5}",
                "{\"content\":\"说明\",\"fontSizeRpx\":\"28\"}",
                "{\"content\":\"说明\",\"fontSizeRpx\":2147483648}",
                "{\"content\":\"说明\",\"fontSizeRpx\":19}",
                "{\"content\":\"说明\",\"fontSizeRpx\":49}"
        )) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject(json), context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文字说明字号必须为20至48之间的整数");
        }
        for (String fontFamily : List.of("WECHAT_SANS_STD", "UNKNOWN")) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(
                    JSON.parseObject("{\"content\":\"说明\",\"fontFamily\":\"%s\"}"
                            .formatted(fontFamily)), context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文字说明字体不支持");
        }
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"content\":\"说明\",\"fontFamily\":{\"value\":\"SYSTEM\"}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明字体不支持");
    }

    /**
     * 团队文字说明组合错误应稳定按正文、长度、对齐、字体、字号顺序返回。
     */
    @Test
    void textSectionShouldKeepValidationErrorPriority() {
        TeamTextSectionComponentValidator validator = new TeamTextSectionComponentValidator();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);

        assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"   ",
                  "alignment":{"value":"LEFT"},
                  "fontFamily":{"value":"SYSTEM"},
                  "fontSizeRpx":"28"
                }
                """), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明内容不能为空");
        assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"%s",
                  "alignment":{"value":"LEFT"},
                  "fontFamily":{"value":"SYSTEM"},
                  "fontSizeRpx":"28"
                }
                """.formatted("字".repeat(201))), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明最多200个字符");
        assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"说明",
                  "alignment":{"value":"LEFT"},
                  "fontFamily":{"value":"SYSTEM"},
                  "fontSizeRpx":"28"
                }
                """), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明对齐方式不支持");
        assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"说明",
                  "alignment":"LEFT",
                  "fontFamily":{"value":"SYSTEM"},
                  "fontSizeRpx":"28"
                }
                """), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明字体不支持");
        assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject("""
                {
                  "content":"说明",
                  "alignment":"LEFT",
                  "fontFamily":"SYSTEM",
                  "fontSizeRpx":"28"
                }
                """), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明字号必须为20至48之间的整数");
    }

    /**
     * 团队文字说明渲染器应防御未经校验的原始排版配置。
     */
    @Test
    void textSectionRendererShouldRejectLossyOrInvalidTypography() {
        TeamTextSectionComponentRenderer renderer = new TeamTextSectionComponentRenderer();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);

        for (String json : List.of(
                "{\"content\":\"说明\",\"fontSizeRpx\":28.5}",
                "{\"content\":\"说明\",\"fontSizeRpx\":\"28\"}"
        )) {
            assertThatThrownBy(() -> renderer.render(JSON.parseObject(json), context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文字说明字号必须为20至48之间的整数");
        }
        for (String fontFamily : List.of("WECHAT_SANS_STD", "UNKNOWN")) {
            assertThatThrownBy(() -> renderer.render(
                    JSON.parseObject("{\"content\":\"说明\",\"fontFamily\":\"%s\"}"
                            .formatted(fontFamily)), context))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文字说明字体不支持");
        }
        assertThatThrownBy(() -> renderer.render(
                JSON.parseObject("{\"content\":\"说明\",\"fontFamily\":{\"value\":\"SYSTEM\"}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明字体不支持");
    }

    /**
     * 文字说明应拒绝空白内容、超过 Unicode 字符上限的内容和不支持对齐。
     */
    @Test
    void textSectionShouldValidateContentAndUnicodeCodePointBoundary() {
        TeamTextSectionComponentValidator validator = new TeamTextSectionComponentValidator();
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(11L, 21L, 3);
        String twoCodeUnitCharacter = "😀";

        assertThat(validator.normalizeAndValidate(JSON.parseObject("{\"content\":\""
                + twoCodeUnitCharacter.repeat(200) + "\"}"), context).getString("content"))
                .hasSize(twoCodeUnitCharacter.length() * 200);
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"content\":\"   \"}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明内容不能为空");
        assertThatThrownBy(() -> validator.normalizeAndValidate(JSON.parseObject("{\"content\":\""
                + twoCodeUnitCharacter.repeat(201) + "\"}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明最多200个字符");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"content\":\"说明\",\"alignment\":\"JUSTIFY\"}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明对齐方式不支持");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"content\":{\"value\":\"说明\"}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明内容不能为空");
        assertThatThrownBy(() -> validator.normalizeAndValidate(
                JSON.parseObject("{\"content\":\"说明\",\"alignment\":{\"value\":\"LEFT\"}}"), context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文字说明对齐方式不支持");
    }

    /**
     * 配置模型字段和嵌套团队快照应符合组件契约。
     */
    @Test
    void configModelsShouldExposeOnlyComponentContractFields() {
        assertFields(TeamProfileComponentConfig.class, "team", "visibleFields");
        assertFields(TeamProfileComponentConfig.TeamSnapshot.class, "teamId", "avatarUrl", "teamName", "intro");
        assertFields(TeamDividerComponentConfig.class, "color", "heightPx");
        assertFields(TeamTextSectionComponentConfig.class,
                "content", "alignment", "fontFamily", "fontSizeRpx");
    }

    /**
     * 三个组件包不得依赖个人作品集实现或彼此交叉导入。
     *
     * @throws Exception 读取源码失败
     */
    @Test
    void displayComponentsShouldRemainIndependent() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/jxc/wefolio");
        Set<Path> packagePaths = Set.of(TEAM_PROFILE_SOURCE, DIVIDER_SOURCE, TEXT_SECTION_SOURCE);
        List<Path> sourceFiles = packagePaths.stream()
                .flatMap(packagePath -> listJavaFiles(sourceRoot.resolve(packagePath)).stream())
                .toList();
        Map<Path, List<String>> forbiddenCrossComponentPackages = Map.of(
                TEAM_PROFILE_SOURCE, List.of(
                        "com.jxc.wefolio.service.teamportfolio.component.divider",
                        "com.jxc.wefolio.service.teamportfolio.component.textsection"),
                DIVIDER_SOURCE, List.of(
                        "com.jxc.wefolio.service.teamportfolio.component.teamprofile",
                        "com.jxc.wefolio.service.teamportfolio.component.textsection"),
                TEXT_SECTION_SOURCE, List.of(
                        "com.jxc.wefolio.service.teamportfolio.component.teamprofile",
                        "com.jxc.wefolio.service.teamportfolio.component.divider")
        );
        List<String> forbiddenPersonalReferences = List.of(
                "dto.Portfolio",
                "service.Portfolio",
                "MinePortfolioService",
                "VisitorPortfolioService",
                "PortfolioConfigValidator",
                "PortfolioRenderService",
                "com.jxc.wefolio.dto.Portfolio",
                "com.jxc.wefolio.service.MinePortfolioService",
                "com.jxc.wefolio.service.PortfolioConfigValidator",
                "com.jxc.wefolio.service.PortfolioRenderService",
                "com.jxc.wefolio.dict.PortfolioComponentTypeDict"
        );

        assertThat(sourceFiles).hasSize(12)
                .containsExactlyInAnyOrderElementsOf(expectedComponentSourceFiles(sourceRoot));
        for (Path sourceFile : sourceFiles) {
            String source = Files.readString(sourceFile);
            Path componentPath = sourceRoot.relativize(sourceFile.getParent());
            assertThat(source).doesNotContain(forbiddenPersonalReferences.toArray(String[]::new));
            assertThat(source).doesNotContain(forbiddenCrossComponentPackages.get(componentPath).toArray(String[]::new));
        }
    }

    /**
     * 创建状态正常的团队实体。
     *
     * @param id 团队 ID
     * @param avatarUrl 团队头像
     * @param name 团队名称
     * @param intro 团队简介
     * @return 团队实体
     */
    private TeamEntity activeTeam(long id, String avatarUrl, String name, String intro) {
        TeamEntity team = new TeamEntity();
        team.setId(id);
        team.setAvatarUrl(avatarUrl);
        team.setName(name);
        team.setIntro(intro);
        team.setStatus(TEAM_STATUS_ACTIVE);
        return team;
    }

    /**
     * 断言团队快照的约定字段及显式空值。
     *
     * @param teamSnapshot 团队快照
     */
    private void assertExplicitNullTeamSnapshot(JSONObject teamSnapshot) {
        assertThat(teamSnapshot.keySet())
                .containsExactlyInAnyOrder("teamId", "avatarUrl", "teamName", "intro");
        assertThat(teamSnapshot.getLong("teamId")).isEqualTo(11L);
        assertThat(teamSnapshot.containsKey("avatarUrl")).isTrue();
        assertThat(teamSnapshot.get("avatarUrl")).isNull();
        assertThat(teamSnapshot.getString("teamName")).isEqualTo("当前团队");
        assertThat(teamSnapshot.containsKey("intro")).isTrue();
        assertThat(teamSnapshot.get("intro")).isNull();
    }

    /**
     * 断言模型的实例字段集合。
     *
     * @param type 模型类型
     * @param expectedNames 预期字段名
     */
    private void assertFields(Class<?> type, String... expectedNames) {
        assertThat(Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
                .containsExactlyInAnyOrder(expectedNames);
    }

    /**
     * 列出组件包中的 Java 源码。
     *
     * @param packagePath 组件包路径
     * @return 源码文件列表
     */
    private List<Path> listJavaFiles(Path packagePath) {
        try (var files = Files.list(packagePath)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 返回三个组件包必须扫描的完整生产源码集合。
     *
     * @param sourceRoot 生产源码根目录
     * @return 预期源码文件
     */
    private List<Path> expectedComponentSourceFiles(Path sourceRoot) {
        return List.of(
                sourceRoot.resolve(TEAM_PROFILE_SOURCE).resolve("TeamProfileComponentConfig.java"),
                sourceRoot.resolve(TEAM_PROFILE_SOURCE).resolve("TeamProfileComponentValidator.java"),
                sourceRoot.resolve(TEAM_PROFILE_SOURCE).resolve("TeamProfileComponentRenderer.java"),
                sourceRoot.resolve(TEAM_PROFILE_SOURCE).resolve("TeamProfileComponentReferenceExtractor.java"),
                sourceRoot.resolve(DIVIDER_SOURCE).resolve("TeamDividerComponentConfig.java"),
                sourceRoot.resolve(DIVIDER_SOURCE).resolve("TeamDividerComponentValidator.java"),
                sourceRoot.resolve(DIVIDER_SOURCE).resolve("TeamDividerComponentRenderer.java"),
                sourceRoot.resolve(DIVIDER_SOURCE).resolve("TeamDividerComponentReferenceExtractor.java"),
                sourceRoot.resolve(TEXT_SECTION_SOURCE).resolve("TeamTextSectionComponentConfig.java"),
                sourceRoot.resolve(TEXT_SECTION_SOURCE).resolve("TeamTextSectionComponentValidator.java"),
                sourceRoot.resolve(TEXT_SECTION_SOURCE).resolve("TeamTextSectionComponentRenderer.java"),
                sourceRoot.resolve(TEXT_SECTION_SOURCE).resolve("TeamTextSectionComponentReferenceExtractor.java")
        );
    }
}
