package com.jxc.wefolio.service.portfolioai;

import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.*;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.entity.*;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.*;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.PortfolioBackgroundAudioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioConfigValidator;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioRenderService;
import com.jxc.wefolio.service.teamportfolio.TeamTextBackgroundSupport;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 团队十四类 AI 提案通过真实编译器和普通校验器，含首次未保存候选上下文。 */
class PortfolioAiTeamComponentsTest {
    /** 测试输入始终经过生产严格解析器。 */
    private ObjectNode node(String raw) { return StrictAiJson.parseObject(raw, 262144, 16); }

    /** 所有团队 AI 组件均走真实组件 normalizer，只替换数据库和资产支持。 */
    @ParameterizedTest
    @ValueSource(strings = {"TEAM_PROFILE", "CAROUSEL", "SINGLE_WORK", "DIVIDER", "MEMBER_PORTFOLIO_GRID",
            "MEMBER_PORTFOLIO_LIST", "TEXT_SECTION", "SCHEDULE_QUERY", "CONTACT_FORM", "QR_CONTACT", "VIDEO_CAROUSEL",
            "STRUCTURED_TEXT_SECTION", "TEXT_GRID", "CONTACT_INFO"})
    void everyTeamComponentPassesRealDraftValidatorWithoutPersistedPortfolio(String type) {
        TeamPortfolioConfigValidator validator = validator();
        PortfolioAiConfigService validation = new PortfolioAiConfigService(new PortfolioAiProperties(), null, validator, null, null, null);
        ObjectNode request = request();
        ObjectNode proposal = node("{\"type\":\"" + type + "\",\"sourceNos\":[],\"params\":{},\"bindings\":[]}");
        ObjectNode params = (ObjectNode) proposal.path("params");
        ArrayNode sourceNos = (ArrayNode) proposal.path("sourceNos"), bindings = (ArrayNode) proposal.path("bindings");
        switch (type) {
            case "TEAM_PROFILE" -> bindings.addObject().put("slot", "team").put("ref", "profile_base");
            case "CAROUSEL", "SINGLE_WORK" -> sourceNos.add(1);
            case "VIDEO_CAROUSEL" -> { sourceNos.add(2).add(3).add(4); params.put("title", "团队视频"); }
            case "MEMBER_PORTFOLIO_GRID", "MEMBER_PORTFOLIO_LIST" -> sourceNos.add(5);
            case "TEXT_SECTION" -> params.put("content", "温暖而有力量的团队");
            case "STRUCTURED_TEXT_SECTION" -> params.set("blocks", node("{\"blocks\":[{\"type\":\"PARAGRAPH\",\"content\":\"温暖的团队\"}]}").path("blocks"));
            case "TEXT_GRID" -> {
                params.put("rows", 1).put("columns", 1);
                params.set("cells", node("{\"cells\":[{\"row\":0,\"column\":0,\"rowSpan\":1,\"columnSpan\":1,\"blocks\":[{\"runs\":[{\"text\":\"温暖瞬间\"}]}]}]}").path("cells"));
            }
            case "DIVIDER" -> params.put("color", "GRAY").put("heightPx", 16);
            case "SCHEDULE_QUERY" -> params.put("displayMode", "MODAL_CALENDAR");
            case "CONTACT_FORM" -> params.put("displayMode", "MODAL_FORM");
            case "CONTACT_INFO" -> bindings.addObject().put("slot", "contactPhone").put("ref", "phone_base");
            case "QR_CONTACT" -> { params.put("qrUrlSource", "CUSTOM"); bindings.addObject().put("slot", "qrUrl").put("ref", "qr_base"); }
            default -> throw new AssertionError(type);
        }
        ObjectNode output = node("{\"status\":\"CANDIDATE\",\"mode\":\"CREATE\",\"operations\":[],\"questions\":[],\"notes\":[],\"page\":{\"menuKey\":\"menu_current\",\"components\":[],\"settings\":{}}}");
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ((ArrayNode) output.path("page").path("components")).add(proposal);
        ObjectNode candidate = PortfolioAiCompiler.compile(802L, request, output);
        ObjectNode normalized = validation.validate((ObjectNode) request.path("input"), null, candidate);
        assertThat(normalized.path("components").size()).isEqualTo(1);
        assertThat(normalized.path("components").get(0).path("componentType").asText()).isEqualTo(type);
        assertThat(normalized.path("components").get(0).path("componentKey").asText()).isEqualTo("c_ai_802_1");
    }

    /** 新建团队候选的真实顶层渲染隐藏未保存身份，并支持只读联系表单组件。 */
    @Test void unsavedCandidateRendersWithoutFakePortfolioId() {
        TeamContactFormComponentValidator contact = new TeamContactFormComponentValidator();
        TeamPortfolioRenderService renderer = new TeamPortfolioRenderService(null, null, null, null, null, null, null, null,
                new TeamContactFormComponentRenderer(contact), null, null, null, mock(TeamTextBackgroundSupport.class),
                new PortfolioBackgroundAudioService(mock(WorkEntityMapper.class), mock(CosService.class), null));
        PortfolioAiConfigService service = new PortfolioAiConfigService(new PortfolioAiProperties(), null, validator(), null, null, renderer);
        ObjectNode input = (ObjectNode) request().path("input");
        ObjectNode config = node("{\"schemaVersion\":\"standard-team-v1\",\"editorSchemaRevision\":10,\"share\":{\"title\":\"高级团队候选\"},\"components\":[{\"componentKey\":\"contact\",\"componentType\":\"CONTACT_FORM\",\"sortOrder\":1000,\"enabled\":true,\"config\":{\"displayMode\":\"MODAL_FORM\"}}]}");
        ObjectNode normalized = service.validate(input, null, config);
        TeamPortfolioRenderDto rendered = (TeamPortfolioRenderDto) service.render(input, null, normalized);
        assertThat(rendered.getPortfolioId()).isNull(); assertThat(rendered.getShareCode()).isNull();
        assertThat(rendered.getTeamId()).isEqualTo(31L); assertThat(rendered.getComponents()).hasSize(1);
    }

    /** 零标识必须携带服务端独立候选许可，普通及普通高级构造仍拒绝。 */
    @Test void zeroIdIsAllowedOnlyForExplicitReadOnlyCandidate() {
        TeamContactFormComponentValidator validator = new TeamContactFormComponentValidator();
        for (TeamPortfolioComponentContext invalid : List.of(new TeamPortfolioComponentContext(31L, 0, 0),
                new TeamPortfolioComponentContext(31L, 0, 0, null, true),
                new TeamPortfolioComponentContext(31L, -1, 0, null, true, true))) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(new JSONObject(), invalid)).isInstanceOf(BusinessException.class);
        }
        TeamPortfolioComponentContext candidate = TeamPortfolioComponentContext.unsavedAdvancedCandidate(31L, 0);
        validator.normalizeAndValidate(new JSONObject(), candidate);
        assertThatThrownBy(() -> new TeamScheduleQueryComponentReferenceExtractor(new TeamScheduleQueryComponentRenderer())
                .extract("schedule", "components[0]", new JSONObject(), candidate)).isInstanceOf(BusinessException.class);
    }

    /** 冻结团队身份、作品和高级个人作品集来源，真实 ID 只由编译器恢复。 */
    private ObjectNode request() {
        ObjectNode request = node("""
                {"input":{"ownerType":"TEAM","ownerId":"31","mode":"CREATE","targetMenuKey":"__root__","baseDraftRevision":0,
                  "editorSchemaRevision":10,"prompt":"展示团队作品"},"facts":{"sources":[],"bindings":[],
                  "profile":{"teamId":31,"teamName":"演示团队","avatarUrl":"","intro":"一起记录幸福"}}}
                """);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ObjectNode facts = (ObjectNode) request.path("facts");
        facts.set("baseConfig", node("{\"schemaVersion\":\"standard-team-v1\",\"editorSchemaRevision\":10,\"fonts\":{},\"components\":[],\"share\":{\"title\":\"团队作品\"},\"style\":{\"backgroundColor\":\"#FFFFFF\"}}"));
        for (int i = 0; i < 4; i++) {
            ((ArrayNode) facts.path("sources")).addObject().put("sourceNo", i + 1).put("kind", "WORK")
                    .put("sourceId", String.valueOf(201 + i)).put("memberUserId", "101").put("mediaType", i == 0 ? "IMAGE" : "VIDEO");
        }
        ((ArrayNode) facts.path("sources")).addObject().put("sourceNo", 5).put("kind", "MEMBER_PORTFOLIO").put("sourceId", "401").put("memberUserId", "101");
        addBinding(facts, "profile_base", "TEAM_PROFILE", "team", facts.path("profile").toString());
        addBinding(facts, "phone_base", "CONTACT_PHONE", "contactPhone", "\"13800000000\"");
        addBinding(facts, "qr_base", "QR_IMAGE", "qrUrl", "\"https://assets.example/team/qr.png\"");
        return request;
    }
    /** 合法冻结资源句柄只供校验器恢复，不向模型开放原值。 */
    private void addBinding(ObjectNode facts, String ref, String kind, String slot, String rawValue) {
        ObjectNode binding = ((ArrayNode) facts.path("bindings")).addObject().put("ref", ref).put("kind", kind).put("reuseOnly", false);
        binding.putArray("allowedSlots").add(slot); binding.set("value", node("{\"value\":" + rawValue + "}").get("value"));
    }
    /** 使用真实十四种普通校验能力，仅用 Mapper/资产/背景支持替换外部状态。 */
    private TeamPortfolioConfigValidator validator() {
        TeamEntityMapper teams = mock(TeamEntityMapper.class); TeamMemberEntityMapper members = mock(TeamMemberEntityMapper.class);
        UserEntityMapper users = mock(UserEntityMapper.class); WorkEntityMapper works = mock(WorkEntityMapper.class);
        PortfolioEntityMapper portfolios = mock(PortfolioEntityMapper.class); TeamPortfolioAssetService assets = mock(TeamPortfolioAssetService.class);
        TeamTextBackgroundSupport backgrounds = mock(TeamTextBackgroundSupport.class);
        TeamEntity team = new TeamEntity(); team.setId(31L); team.setName("演示团队"); team.setStatus(TeamStatusDict.ACTIVE.getCode()); team.setIntro("一起记录幸福"); team.setAvatarUrl("");
        when(teams.selectById(31L)).thenReturn(team);
        TeamMemberEntity member = new TeamMemberEntity(); member.setTeamId(31L); member.setUserId(101L); member.setDeleted(0L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(1); member.setAllowPortfolio(1);
        when(members.selectList(any())).thenReturn(List.of(member));
        UserEntity user = new UserEntity(); user.setId(101L); user.setStatus(UserStatusDict.ACTIVE.getCode()); user.setDeleted(0L);
        when(users.selectBatchIds(anyCollection())).thenReturn(List.of(user));
        List<WorkEntity> media = List.of(work(201L, MediaTypeDict.IMAGE.getCode()), work(202L, MediaTypeDict.VIDEO.getCode()),
                work(203L, MediaTypeDict.VIDEO.getCode()), work(204L, MediaTypeDict.VIDEO.getCode()));
        when(works.selectList(any())).thenReturn(media); when(works.selectBatchIds(anyCollection())).thenReturn(media);
        PortfolioEntity child = new PortfolioEntity(); child.setId(401L); child.setOwnerType(PortfolioOwnerTypeDict.USER.getCode()); child.setOwnerId(101L);
        child.setShareCode("member-share-401");
        child.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode()); child.setStatus(PortfolioStatusDict.ACTIVE.getCode()); child.setDeleted(0L);
        child.setSchemaVersion("standard-personal-v1"); child.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode()); child.setPublishedRevision(1);
        child.setPublishedConfigJson("{\"share\":{\"title\":\"成员作品集\",\"coverUrl\":\"\"}}");
        when(portfolios.selectList(any())).thenReturn(List.of(child));
        return new TeamPortfolioConfigValidator(new TeamProfileComponentValidator(teams, assets),
                new TeamCarouselComponentValidator(members, users, works), new TeamSingleWorkComponentValidator(members, users, works),
                new TeamDividerComponentValidator(), new TeamMemberPortfolioGridComponentValidator(members, users, portfolios),
                new TeamMemberPortfolioListComponentValidator(members, users, portfolios), new TeamTextSectionComponentValidator(backgrounds),
                new TeamScheduleQueryComponentValidator(), new TeamContactFormComponentValidator(), new TeamQrContactComponentValidator(assets),
                new TeamVideoCarouselComponentValidator(members, users, works), new TeamStructuredTextSectionComponentValidator(backgrounds));
    }
    /** 成员媒体来源使用当前实际作者、有效状态与审核结果。 */
    private WorkEntity work(Long id, String type) {
        WorkEntity work = new WorkEntity(); work.setId(id); work.setUserId(101L); work.setMediaType(type); work.setDeleted(0L);
        work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode()); return work;
    }
}
