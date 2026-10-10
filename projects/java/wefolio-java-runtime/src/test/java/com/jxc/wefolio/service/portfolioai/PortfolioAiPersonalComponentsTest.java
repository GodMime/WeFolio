package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import com.jxc.wefolio.service.PortfolioRenderService;
import com.jxc.wefolio.service.PortfolioHyperlinkGraphService;
import com.jxc.wefolio.dto.PortfolioHyperlinkTargetResponse;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/** 个人十五类 AI 组件逐一通过真正普通校验器，避免只证明中间 JSON 能解析。 */
class PortfolioAiPersonalComponentsTest {
    /** 生产严格解析器作为测试输入边界。 */
    private ObjectNode node(String text) { return StrictAiJson.parseObject(text, 262144, 16); }

    /** 覆盖每种开放组件的生成、真实资源恢复、普通业务校验及显式字段保护。 */
    @ParameterizedTest
    @ValueSource(strings = {"PROFILE", "TEXT_SECTION", "STRUCTURED_TEXT_SECTION", "TEXT_GRID", "CAROUSEL",
            "VIDEO_CAROUSEL", "SINGLE_WORK", "WORK_GRID", "WORK_LIST", "DIVIDER", "SCHEDULE_QUERY", "CONTACT_FORM",
            "CONTACT_INFO", "QR_CONTACT", "HYPERLINK"})
    void everyPersonalComponentPassesRealDraftValidator(String type) {
        WorkEntityMapper works = mock(WorkEntityMapper.class);
        when(works.selectBatchIds(anyCollection())).thenReturn(List.of(work(201L, MediaTypeDict.IMAGE.getCode()),
                work(202L, MediaTypeDict.VIDEO.getCode()), work(203L, MediaTypeDict.VIDEO.getCode()), work(204L, MediaTypeDict.VIDEO.getCode())));
        PortfolioAiProperties properties = new PortfolioAiProperties();
        PortfolioAiConfigService validation = new PortfolioAiConfigService(properties, new PortfolioConfigValidator(works), null, null, null, null);
        ObjectNode request = node("""
                {"input":{"ownerType":"USER","ownerId":"101","mode":"CREATE","targetMenuKey":"__root__","baseDraftRevision":0,
                  "editorSchemaRevision":14,"prompt":"展示我的作品"},"facts":{"sources":[],"bindings":[],
                  "profile":{"displayName":"演示摄影师","avatarUrl":"","profession":"摄影","city":"上海","bio":"用心记录","wechatQrUrl":""}}}
                """);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ObjectNode facts = (ObjectNode) request.path("facts");
        facts.set("baseConfig", node("{\"schemaVersion\":\"standard-personal-v1\",\"editorSchemaRevision\":14,\"fonts\":{},\"components\":[],\"share\":{\"title\":\"个人作品\"},\"style\":{\"backgroundColor\":\"#FFFFFF\",\"componentSpacingRpx\":32}}"));
        for (int i = 0; i < 4; i++) {
            ((ArrayNode) facts.path("sources")).addObject().put("sourceNo", i + 1).put("kind", "WORK")
                    .put("sourceId", String.valueOf(201 + i)).put("memberUserId", "101").put("mediaType", i == 0 ? "IMAGE" : "VIDEO");
        }
        addBinding(facts, "profile_base", "PROFILE", "profile", facts.path("profile").toString());
        addBinding(facts, "phone_base", "CONTACT_PHONE", "contactPhone", "\"13800000000\"");
        addBinding(facts, "link_base", "EXTERNAL_LINK", "externalContent", "\"https://example.com\"");
        ObjectNode proposal = node("{\"type\":\"" + type + "\",\"sourceNos\":[],\"params\":{},\"bindings\":[]}");
        ObjectNode params = (ObjectNode) proposal.path("params");
        ArrayNode sourceNos = (ArrayNode) proposal.path("sourceNos"), bindings = (ArrayNode) proposal.path("bindings");
        switch (type) {
            case "PROFILE" -> bindings.addObject().put("slot", "profile").put("ref", "profile_base");
            case "TEXT_SECTION" -> params.put("content", "每一份幸福都值得珍藏");
            case "STRUCTURED_TEXT_SECTION" -> params.set("blocks", node("{\"blocks\":[{\"type\":\"PARAGRAPH\",\"content\":\"每一份幸福都值得珍藏\"}]}").path("blocks"));
            case "TEXT_GRID" -> {
                params.put("rows", 1).put("columns", 1);
                params.set("cells", node("{\"cells\":[{\"row\":0,\"column\":0,\"rowSpan\":1,\"columnSpan\":1,\"blocks\":[{\"runs\":[{\"text\":\"温暖瞬间\"}]}]}]}").path("cells"));
            }
            case "CAROUSEL", "SINGLE_WORK", "WORK_GRID", "WORK_LIST" -> sourceNos.add(1);
            case "VIDEO_CAROUSEL" -> { sourceNos.add(2).add(3).add(4); params.put("title", "视频作品"); }
            case "DIVIDER" -> params.put("color", "GRAY").put("heightPx", 16);
            case "SCHEDULE_QUERY" -> params.put("displayMode", "MODAL_CALENDAR");
            case "CONTACT_FORM" -> params.put("displayMode", "MODAL_FORM");
            case "CONTACT_INFO" -> bindings.addObject().put("slot", "contactPhone").put("ref", "phone_base");
            case "QR_CONTACT" -> params.put("qrUrlSource", "PROFILE");
            case "HYPERLINK" -> {
                sourceNos.add(1); params.put("actionType", "EXTERNAL_LINK").put("promptText", "了解更多");
                bindings.addObject().put("slot", "externalContent").put("ref", "link_base");
            }
            default -> throw new AssertionError(type);
        }
        ObjectNode output = node("{\"status\":\"CANDIDATE\",\"mode\":\"CREATE\",\"operations\":[],\"questions\":[],\"notes\":[],\"page\":{\"menuKey\":\"menu_current\",\"components\":[],\"settings\":{}}}");
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ((ArrayNode) output.path("page").path("components")).add(proposal);
        ObjectNode candidate = PortfolioAiCompiler.compile(801L, request, output);
        ObjectNode normalized = validation.validate((ObjectNode) request.path("input"), null, candidate);
        assertThat(normalized.path("components").size()).isEqualTo(1);
        assertThat(normalized.path("components").get(0).path("componentType").asText()).isEqualTo(type);
        assertThat(normalized.path("components").get(0).path("componentKey").asText()).isEqualTo("c_ai_801_1");
    }

    /** 冻结合法资源句柄，不直接开放原值给模型。 */
    private void addBinding(ObjectNode facts, String ref, String kind, String slot, String rawValue) {
        ObjectNode binding = ((ArrayNode) facts.path("bindings")).addObject().put("ref", ref).put("kind", kind).put("reuseOnly", false);
        binding.putArray("allowedSlots").add(slot); binding.set("value", node("{\"value\":" + rawValue + "}").get("value"));
    }
    /** 构造实际归属、可用与审核通过的测试媒体。 */
    private WorkEntity work(Long id, String mediaType) {
        WorkEntity work = new WorkEntity(); work.setId(id); work.setUserId(101L); work.setMediaType(mediaType);
        work.setStatus(WorkStatusDict.ACTIVE.getCode()); work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        return work;
    }

    /** 首次创建无需真实作品集ID，但资源渲染仍必须获得实际用户归属。 */
    @Test void unsavedCandidateRenderRetainsRealOwnerContext() {
        PortfolioRenderService renderer = mock(PortfolioRenderService.class);
        PortfolioAiConfigService service = new PortfolioAiConfigService(new PortfolioAiProperties(), null, null, null, renderer, null);
        service.render(node("{\"ownerType\":\"USER\",\"ownerId\":\"101\"}"), null, node("{\"schemaVersion\":\"standard-personal-v1\",\"components\":[]}"));
        ArgumentCaptor<PortfolioEntity> context = ArgumentCaptor.forClass(PortfolioEntity.class);
        verify(renderer).render(context.capture(), any(), eq(true), eq(false), isNull(), isNull());
        assertThat(context.getValue().getOwnerId()).isEqualTo(101L);
        assertThat(context.getValue().getTemplateType()).isEqualTo("ADVANCED");
        assertThat(context.getValue().getId()).isNull(); assertThat(context.getValue().getShareCode()).isNull();
    }

    /** 无来源新会话的普通基础资料配置应先正常通过业务校验，不能因空字体表失败。 */
    @Test void initialConfigPassesOrdinaryNormalization() {
        PortfolioAiConfigService service = new PortfolioAiConfigService(new PortfolioAiProperties(), new PortfolioConfigValidator(mock(WorkEntityMapper.class)), null, null, null, null);
        ObjectNode input = node("{\"ownerType\":\"USER\",\"ownerId\":\"101\",\"editorSchemaRevision\":14}");
        assertThat(service.validate(input, null, service.initialConfig(input)).path("components").size()).isEqualTo(1);
    }

    /** 未保存会话也不能伪造高级或外人内部目标，合法普通目标仍可使用。 */
    @Test void unsavedInternalHyperlinkRequiresCurrentSelectableOrdinaryTarget() {
        WorkEntityMapper works = mock(WorkEntityMapper.class);
        when(works.selectBatchIds(anyCollection())).thenReturn(List.of(work(201L, MediaTypeDict.IMAGE.getCode())));
        PortfolioHyperlinkGraphService graph = mock(PortfolioHyperlinkGraphService.class);
        PortfolioHyperlinkTargetResponse targets = new PortfolioHyperlinkTargetResponse();
        when(graph.listTargets(101L, null, 501L, PortfolioTemplateTypeDict.ADVANCED.getCode())).thenReturn(targets);
        PortfolioAiConfigService validation = new PortfolioAiConfigService(new PortfolioAiProperties(), new PortfolioConfigValidator(works), null, graph, null, null);
        ObjectNode input = node("{\"ownerType\":\"USER\",\"ownerId\":\"101\"}"), config = node("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"测试\"},\"components\":[{\"componentKey\":\"link\",\"componentType\":\"HYPERLINK\",\"sortOrder\":1000,\"enabled\":true,\"config\":{\"workId\":201,\"actionType\":\"INTERNAL_PORTFOLIO\",\"targetPortfolioId\":501}}]}");
        assertThatThrownBy(() -> validation.validate(input, null, config)).isInstanceOf(RuntimeException.class);
        PortfolioHyperlinkTargetResponse.TargetItem valid = new PortfolioHyperlinkTargetResponse.TargetItem();
        valid.setPortfolioId(501L); valid.setSelectable(true); targets.setPortfolios(List.of(valid));
        assertThat(validation.validate(input, null, config).path("components").size()).isEqualTo(1);
    }
}
