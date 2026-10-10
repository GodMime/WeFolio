package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 两工程共享的严格解析与协议负例，模型偏离不能产生合法候选。 */
class AiPortfolioProtocolTest {
    /** 构造代表性已有完整配置，包含必须原样保留的手动字段。 */
    private ObjectNode request(String mode) {
        return StrictAiJson.parseObject("""
                {"contractVersion":"ai-portfolio-v1","promptVersion":"portfolio-ai-2026-10-10.2",
                 "input":{"ownerType":"USER","mode":"%s","targetMenuKey":"__root__","prompt":"联系13800138000 https://example.test/secret","baseConfig":null},
                 "facts":{"safePrompt":"精简文案","baseConfig":{"schemaVersion":"standard-personal-v1","share":{"title":"我的作品"},"style":{"backgroundColor":"#FFFFFF"},"components":[
                   {"componentKey":"text-real-key","componentType":"TEXT_SECTION","sortOrder":1000,"enabled":true,"config":{"content":"旧内容","fontSizeRpx":32,"alignment":"CENTER","fontId":"font-real-id"}},
                   {"componentKey":"work-real-key","componentType":"SINGLE_WORK","sortOrder":2000,"enabled":true,"config":{"workId":101,"showTitle":true}}
                 ]},"sources":[{"sourceNo":3,"kind":"WORK","sourceId":"101","ownerUserId":77,"mediaType":"VIDEO","title":"作品","thumbUrl":"https://signed.test/private","canBind":true}],
                 "bindings":[{"ref":"font_1","kind":"FONT","value":"font-real-id","allowedSlots":["fontId"],"reuseOnly":true,"componentKey":"text-real-key"}]},
                 "modelConfig":{"provider":"TOKENHUB","model":"glm-5.3-flash"}}
                """.formatted(mode), StrictAiJson.RESPONSE_MAX_BYTES, 20);
    }

    /** 合法修改信封，空操作为合法无改动。 */
    private ObjectNode envelope(String mode) {
        ObjectNode result = StrictAiJson.mapper().createObjectNode();
        result.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION)
                .put("status", "CANDIDATE").put("mode", mode).putNull("page");
        result.putArray("operations"); result.putArray("questions"); result.putArray("notes"); return result;
    }

    /** 组件级合法单字段修改。 */
    private ObjectNode operation(ObjectNode request, String op) {
        ObjectNode result = StrictAiJson.mapper().createObjectNode().put("op", op).put("reason", "精简说明");
        result.putObject("target").put("menuKey", AiPortfolioProtocol.MENU_ALIAS).put("componentKey", "cmp_1");
        result.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveComponent(request, "cmp_1")));
        result.putObject("payload").put("field", "content").put("value", "新内容"); return result;
    }

    @Test
    void rejectsDuplicateTrailingLenientAndInvalidUnicodeWhilePreservingValidUnicode() {
        for (String raw : List.of("{\"a\":1,\"a\":2}", "{\"a\":{\"b\":1,\"b\":2}}", "{} {}", "{}text", "```json\n{}\n```",
                "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":1e10000}", "{\"a\":\"\\ud800\"}", "{\"a\":\"\\udc00\"}", "{\"a\":1,}", "{a:1}", "{'a':1}", "/*x*/{}")) {
            assertThatThrownBy(() -> StrictAiJson.parseObject(raw, 10000, 16)).isInstanceOf(AiProtocolException.class);
        }
        assertThat(StrictAiJson.parseObject("{\"emoji\":\"\\ud83d\\ude00\"}", 100, 16).get("emoji").textValue()).isEqualTo("😀");
    }

    @Test
    void limitsDepthAndUtf8BeforeBuildingFullTree() {
        assertThat(StrictAiJson.parseObject("{\"a\":[{}]}", 100, 3)).isNotNull();
        assertThatThrownBy(() -> StrictAiJson.parseObject("{\"a\":[{}]}", 100, 2)).isInstanceOf(AiProtocolException.class);
        assertThatThrownBy(() -> StrictAiJson.parseObject("{\"a\":\"中文\"}", 10, 16)).isInstanceOf(AiProtocolException.class);
        assertThat(StrictAiJson.canonicalHash(StrictAiJson.parseObject("{\"b\":2,\"a\":1}", 100, 16)))
                .isEqualTo(StrictAiJson.canonicalHash(StrictAiJson.parseObject("{\"a\":1,\"b\":2}", 100, 16)));
    }

    @Test
    void safeProjectionHasDeterministicAliasesAndNoRealKeysIdsUrlsOrRawPrompt() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode projection = AiPortfolioProtocol.project(request);
        assertThat(projection.path("ownerType").asText()).isEqualTo("PERSONAL");
        assertThat(projection.path("target").fieldNames()).toIterable().containsExactly("menuKey");
        assertThat(projection.path("userIntent").asText()).isEqualTo("精简文案");
        assertThat(projection.toString()).doesNotContain("text-real-key", "work-real-key", "font-real-id", "13800138000", "https://", "ownerUserId", "sourceId", "thumbUrl");
        assertThat(projection.path("currentPage").path("components").get(1).path("sourceNos").get(0).asInt()).isEqualTo(3);
        assertThat(AiPortfolioProtocol.project(request)).isEqualTo(projection);
    }

    /** 只把已授权作品的 COS 直链交给模型，其他资源仍按隐藏句柄管理。 */
    @Test
    void projectionIncludesOnlySelectedWorkCosLinks() {
        ObjectNode request = request("MODIFY_PAGE");
        var sources = request.withObject("facts").withArray("sources");
        ObjectNode selected = (ObjectNode) sources.get(0);
        selected.put("mediaUrl", "https://cos.example.test/WF001/work/video/demo.mp4")
                .put("coverUrl", "https://cos.example.test/WF001/work/image/cover.jpg");
        sources.addObject().put("sourceNo", 4).put("kind", "WORK").put("canBind", false)
                .put("mediaUrl", "https://cos.example.test/revoked.mp4");
        sources.addObject().put("sourceNo", 5).put("kind", "MEMBER_PORTFOLIO").put("canBind", true)
                .put("mediaUrl", "https://cos.example.test/member-private.mp4");
        sources.addObject().put("sourceNo", 6).put("kind", "WORK").put("canBind", true)
                .put("mediaUrl", "https://user:secret@cos.example.test/private.mp4")
                .put("coverUrl", "https://cos.example.test/cover.jpg?signature=secret");
        JsonNode projected = AiPortfolioProtocol.project(request).path("sources");
        assertThat(projected.get(0).path("mediaUrl").textValue()).isEqualTo(selected.path("mediaUrl").textValue());
        assertThat(projected.get(0).path("coverUrl").textValue()).isEqualTo(selected.path("coverUrl").textValue());
        for (int i = 1; i < projected.size(); i++) {
            assertThat(projected.get(i).has("mediaUrl")).isFalse();
            assertThat(projected.get(i).has("coverUrl")).isFalse();
        }
        assertThat(projected.toString()).doesNotContain("sourceId", "thumbUrl", "revoked", "member-private", "secret");
    }

    /** 已移除的页面模式在投影与模型调用前拒绝，整体修改保留组件语义操作。 */
    @Test
    void onlyCreationAndPageModificationModesAreAvailable() {
        assertThat(AiPortfolioProtocol.contract(request("CREATE")).path("allowedOps")).isEmpty();
        assertThat(AiPortfolioProtocol.contract(request("MODIFY_PAGE")).path("allowedOps"))
                .extracting(JsonNode::asText).containsExactly("UPDATE_TEXT", "UPDATE_PARAMS", "BIND_RESOURCES",
                        "MOVE_COMPONENT", "INSERT_COMPONENT", "REMOVE_COMPONENT", "UPDATE_GLOBAL");
        for (String removedMode : List.of("MODIFY_COMPONENT", "REGENERATE_PAGE")) {
            assertThatThrownBy(() -> AiPortfolioProtocol.project(request(removedMode)))
                    .isInstanceOf(AiProtocolException.class).hasMessage("任务模式不支持");
        }
    }

    /** 整体描述可同时定位当前页多个组件，不需要另传请求级组件目标。 */
    @Test
    void pageModificationCanUpdateMultipleExistingComponents() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode second = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(1);
        second.put("componentType", "TEXT_SECTION");
        second.putObject("config").put("content", "第二处文字");
        ObjectNode output = envelope("MODIFY_PAGE");
        output.withArray("operations").add(operation(request, "UPDATE_TEXT"));
        ObjectNode secondOperation = operation(request, "UPDATE_TEXT");
        secondOperation.withObject("target").put("componentKey", "cmp_2");
        secondOperation.put("expectedHash", AiPortfolioProtocol.expectedHash(second));
        output.withArray("operations").add(secondOperation);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    @Test
    void existingSourceProjectionSeparatesKindsAndChecksRealMemberIdentity() {
        ObjectNode request = request("MODIFY_PAGE");
        request.withObject("input").put("ownerType", "TEAM");
        ObjectNode facts = request.withObject("facts");
        ObjectNode work = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(1);
        work.withObject("config").put("memberUserId", 77);
        ((ObjectNode) facts.withArray("sources").get(0)).put("memberUserId", "77");
        facts.withArray("sources").addObject().put("sourceNo", 4).put("kind", "MEMBER_PORTFOLIO")
                .put("sourceId", "101").put("memberUserId", "77").put("title", "成员作品集");
        facts.withArray("sources").addObject().put("sourceNo", 5).put("kind", "WORK")
                .put("sourceId", "101").put("memberUserId", "88").put("title", "错误成员同号项");
        ObjectNode member = AiPortfolioProtocol.currentComponents(request).addObject().put("componentKey", "member-real")
                .put("componentType", "MEMBER_PORTFOLIO_GRID");
        member.putObject("config").putArray("items").addObject().put("portfolioId", 101).put("memberUserId", 77);
        JsonNode projected = AiPortfolioProtocol.project(request).path("currentPage").path("components");
        assertThat(projected.get(1).path("sourceNos").toString()).isEqualTo("[3]");
        assertThat(projected.get(2).path("sourceNos").toString()).isEqualTo("[4]");
    }

    @Test
    void existingGroupedWorksUseConfigurationOrderInsteadOfSourcePoolOrder() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode work = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(1);
        work.put("componentType", "WORK_GRID");
        ObjectNode config = work.withObject("config"); config.remove("workId");
        config.putArray("groups").addObject().putArray("workIds").add(99).add(101);
        request.withObject("facts").withArray("sources").addObject().put("sourceNo", 7).put("kind", "WORK")
                .put("sourceId", "99").put("mediaType", "IMAGE").put("title", "先展示的作品");
        assertThat(AiPortfolioProtocol.project(request).path("currentPage").path("components").get(1)
                .path("sourceNos").toString()).isEqualTo("[7,3]");
    }

    @Test
    void publicProfileAndSourceTextAreRedactedBeforeModelProjection() {
        ObjectNode request = request("MODIFY_PAGE");
        request.withObject("facts").putObject("profile").put("displayName", "公开名称")
                .put("bio", "微信：secret_wechat 微信号：second_secret 微信号: third_secret 联系13800138000 https://signed.test/private");
        ((ObjectNode) request.path("facts").path("bindings").get(0)).put("label", "已确认字体名称");
        ((ObjectNode) request.path("facts").path("sources").get(0)).put("title", "wechat: source_secret");
        String projected = AiPortfolioProtocol.project(request).toString();
        assertThat(projected).contains("公开名称", "已确认字体名称").doesNotContain("secret_wechat", "second_secret", "third_secret", "source_secret", "13800138000", "https://");
    }

    @Test
    void teamMemberProjectionSharesAliasesAcrossKindsButSeparatesPeopleWithTheSameName() {
        ObjectNode request = request("MODIFY_PAGE"); request.withObject("input").put("ownerType", "TEAM");
        var sources = request.withObject("facts").putArray("sources");
        sources.addObject().put("sourceNo", 7).put("kind", "WORK").put("sourceId", "800000000000000001")
                .put("memberUserId", "700000000000000099").put("memberName", "已撤权姓名").put("canBind", false);
        sources.addObject().put("sourceNo", 3).put("kind", "WORK").put("sourceId", "800000000000000002")
                .put("memberUserId", "700000000000000001").put("memberName", "张三").put("canBind", true);
        sources.addObject().put("sourceNo", 4).put("kind", "MEMBER_PORTFOLIO").put("sourceId", "800000000000000003")
                .put("memberUserId", "700000000000000001").put("memberName", "张三").put("canBind", true);
        sources.addObject().put("sourceNo", 5).put("kind", "WORK").put("sourceId", "800000000000000004")
                .put("memberUserId", "700000000000000002").put("memberName", "张三").put("canBind", true);
        sources.addObject().put("sourceNo", 6).put("kind", "WORK").put("sourceId", "800000000000000005")
                .put("memberUserId", "700000000000000003").put("memberName", "李四 微信号：privateMember https://secret.test/private").put("canBind", true);
        ObjectNode projected = AiPortfolioProtocol.project(request);
        JsonNode safe = projected.path("sources");
        assertThat(safe.get(0).has("memberRef")).isFalse(); assertThat(safe.get(0).has("memberName")).isFalse();
        assertThat(safe.get(1).path("memberRef").asText()).isEqualTo("member_1");
        assertThat(safe.get(2).path("memberRef").asText()).isEqualTo("member_1");
        assertThat(safe.get(3).path("memberRef").asText()).isEqualTo("member_2");
        assertThat(safe.get(4).path("memberRef").asText()).isEqualTo("member_3");
        assertThat(safe.get(1).path("memberName").asText()).isEqualTo(safe.get(3).path("memberName").asText()).isEqualTo("张三");
        assertThat(projected).isEqualTo(AiPortfolioProtocol.project(request.deepCopy()));
        assertThat(projected.toString()).doesNotContain("memberUserId", "sourceId", "700000000000000", "800000000000000", "已撤权姓名", "privateMember", "https://");
    }

    @Test
    void personalSourcesDoNotProjectTeamMemberIdentityOrNames() {
        ObjectNode request = request("MODIFY_PAGE");
        ((ObjectNode) request.path("facts").path("sources").get(0)).put("memberUserId", "700000000000000001").put("memberName", "成员名称");
        JsonNode source = AiPortfolioProtocol.project(request).path("sources").get(0);
        assertThat(source.has("memberRef")).isFalse(); assertThat(source.has("memberName")).isFalse();
    }

    @Test
    void memberAliasesCannotAppearInUserVisibleNotesReasonsOrClarificationText() {
        ObjectNode request = request("MODIFY_PAGE"); ObjectNode output = envelope("MODIFY_PAGE");
        output.withArray("notes").add("请查看member_1");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        output.withArray("notes").removeAll();
        output.withArray("operations").add(operation(request, "UPDATE_TEXT").put("reason", "member_2的说明"));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        output.withArray("operations").removeAll(); output.put("status", "NEEDS_CLARIFICATION");
        ObjectNode question = output.withArray("questions").addObject().put("id", "q1").put("prompt", "是哪位成员？");
        ObjectNode option = question.putArray("options").addObject().put("id", "o1").put("label", "张三的3号作品").put("answerText", "展示张三的3号作品");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        for (String field : List.of("prompt", "label", "answerText")) {
            ObjectNode target = field.equals("prompt") ? question : option;
            String original = target.path(field).textValue(); target.put(field, "member_1");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
            target.put(field, original);
        }
    }

    /** 授权 COS 地址仅供来源上下文使用，模型不能复制到说明、原因或任何澄清文字。 */
    @Test
    void authorizedCosUrlsCannotAppearInAnyUserVisibleOutputText() {
        ObjectNode request = request("MODIFY_PAGE");
        ((ObjectNode) request.path("facts").path("sources").get(0))
                .put("mediaUrl", "https://cos.example.test/WF001/work/video/demo.mp4");
        String mediaUrl = AiPortfolioProtocol.project(request).path("sources").get(0).path("mediaUrl").textValue();
        assertThat(mediaUrl).isNotBlank();

        ObjectNode output = envelope("MODIFY_PAGE");
        output.withArray("notes").add("请查看作品：" + mediaUrl);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                .isInstanceOfSatisfying(AiProtocolException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_OUTPUT"));
        output.withArray("notes").removeAll();
        output.withArray("operations").add(operation(request, "UPDATE_TEXT").put("reason", "作品地址：" + mediaUrl));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                .isInstanceOfSatisfying(AiProtocolException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_OUTPUT"));

        output.withArray("operations").removeAll(); output.put("status", "NEEDS_CLARIFICATION");
        ObjectNode question = output.withArray("questions").addObject().put("id", "q1").put("prompt", "是否调整3号作品？");
        ObjectNode option = question.putArray("options").addObject().put("id", "o1")
                .put("label", "调整3号作品").put("answerText", "调整3号作品的展示顺序");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        for (String field : List.of("prompt", "label", "answerText")) {
            ObjectNode target = field.equals("prompt") ? question : option;
            String original = target.path(field).textValue(); target.put(field, original + mediaUrl);
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                    .isInstanceOfSatisfying(AiProtocolException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_OUTPUT"));
            target.put(field, original);
        }
    }

    @Test
    void acceptsNoChangeButRejectsMalformedNoChangeOrCreateWithoutPage() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode output = envelope("MODIFY_PAGE");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        output.put("hasChanges", false);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request("CREATE"), envelope("CREATE").toString())).isInstanceOf(AiProtocolException.class);
    }

    @Test
    void requiresCompleteEnvelopeAndStrictTypeAtEveryLayer() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode output = envelope("MODIFY_PAGE");
        output.withArray("operations").add(operation(request, "UPDATE_TEXT"));
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        for (String field : Set.of("schemaVersion", "promptVersion", "status", "mode", "page", "operations", "questions", "notes")) {
            ObjectNode invalid = output.deepCopy(); invalid.remove(field);
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, invalid.toString())).isInstanceOf(AiProtocolException.class);
        }
        for (String field : Set.of("op", "target", "expectedHash", "payload", "reason")) {
            ObjectNode invalid = output.deepCopy(); ((ObjectNode) invalid.path("operations").get(0)).put(field, true);
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, invalid.toString())).isInstanceOf(AiProtocolException.class);
        }
        for (String layer : List.of("operation", "target", "payload")) {
            ObjectNode invalid = output.deepCopy(); ObjectNode op = (ObjectNode) invalid.path("operations").get(0);
            (layer.equals("operation") ? op : (ObjectNode) op.get(layer)).put("unknown", "x");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, invalid.toString())).isInstanceOf(AiProtocolException.class);
        }
    }

    @Test
    void rejectsWrongMenuUnknownComponentHashAndOverlappingWrites() {
        ObjectNode request = request("MODIFY_PAGE"); ObjectNode valid = envelope("MODIFY_PAGE");
        valid.withArray("operations").add(operation(request, "UPDATE_TEXT"));
        for (Map.Entry<String,String> invalidValue : Map.of("menuKey", "menu_other", "componentKey", "cmp_999").entrySet()) {
            ObjectNode invalid = valid.deepCopy(); ((ObjectNode) invalid.path("operations").get(0).get("target")).put(invalidValue.getKey(), invalidValue.getValue());
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, invalid.toString())).isInstanceOf(AiProtocolException.class);
        }
        ObjectNode wrongHash = valid.deepCopy(); ((ObjectNode) wrongHash.path("operations").get(0)).put("expectedHash", "wrong");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, wrongHash.toString())).isInstanceOf(AiProtocolException.class);
        valid.withArray("operations").add(operation(request, "UPDATE_TEXT"));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, valid.toString())).isInstanceOf(AiProtocolException.class);
    }

    @Test
    void candidateClarificationAreMutuallyExclusiveAndQuestionsBudgetIsUnicode() {
        ObjectNode request = request("MODIFY_PAGE"); ObjectNode output = envelope("MODIFY_PAGE").put("status", "NEEDS_CLARIFICATION");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        ObjectNode question = output.withArray("questions").addObject().put("id", "q1").put("prompt", "修改哪一项？");
        question.putArray("options").addObject().put("id", "o1").put("label", "页面第1项：文字").put("answerText", "修改文字");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        question.put("prompt", "😀".repeat(500));
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isNotNull();
        question.put("prompt", "😀".repeat(501));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        question.put("prompt", "cmp_1");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    @Test
    void allComponentsHaveClosedParamsAndOwnerCatalogDoesNotLeakPersonalOnlyTypes() {
        ObjectNode personal = AiPortfolioProtocol.contract(request("CREATE"));
        assertThat(personal.path("componentCatalog").size()).isEqualTo(15);
        ObjectNode teamRequest = request("CREATE"); ((ObjectNode) teamRequest.get("input")).put("ownerType", "TEAM");
        ObjectNode team = AiPortfolioProtocol.contract(teamRequest);
        assertThat(team.path("componentCatalog").size()).isEqualTo(14);
        assertThat(team.path("componentCatalog").has("WORK_GRID")).isFalse();
        assertThat(team.path("componentCatalog").has("HYPERLINK")).isFalse();
        assertThat(team.path("createSettings").path("style").path("params").has("componentSpacingRpx")).isFalse();
        assertThat(team.path("createSettings").path("share").path("bindingSlots").has("avatarUrl")).isFalse();
        assertThat(team.path("structureActions").isEmpty()).isTrue();
    }

    @Test
    void mediaNumbersUnknownNestedParamsAndReuseOnlyResourcesAreRejected() {
        ObjectNode request = request("CREATE"); ObjectNode output = envelope("CREATE");
        ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
        page.putObject("settings");
        ObjectNode component = page.putArray("components").addObject().put("type", "SINGLE_WORK");
        component.putArray("sourceNos").add(3); component.putObject("params").put("showTitle", true); component.putArray("bindings");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        for (String rawNumber : List.of("\"3\"", "3.0", "true", "2", "-1")) {
            String invalid = output.toString().replace("\"sourceNos\":[3]", "\"sourceNos\":[" + rawNumber + "]");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, invalid)).isInstanceOf(AiProtocolException.class);
        }
        component.withObject("params").put("showTitle", "false");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        component.withObject("params").remove("showTitle"); component.withObject("params").put("workId", 101);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        component.withObject("params").remove("workId"); component.put("type", "TEXT_SECTION"); component.withObject("params").put("content", "文本"); component.withArray("sourceNos").removeAll();
        component.withArray("bindings").addObject().put("slot", "fontId").put("ref", "font_1");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    @Test
    void currentMenuAndNestedAliasesResolveTheOriginalStableKeys() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode component = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0);
        component.put("componentType", "TEXT_GRID");
        ObjectNode cell = component.withObject("config").putArray("cells").addObject().put("cellKey", "real-cell");
        ObjectNode block = cell.putArray("blocks").addObject().put("blockKey", "real-block");
        block.putArray("runs").addObject().put("runKey", "real-run").put("text", "旧文字").put("fontSizeRpx", 32);
        ObjectNode target = StrictAiJson.mapper().createObjectNode().put("menuKey", "menu_current").put("componentKey", "cmp_1")
                .put("cellKey", "cell_1").put("blockKey", "block_1").put("runKey", "run_1");
        assertThat(AiPortfolioProtocol.resolveNode(request, target).path("runKey").asText()).isEqualTo("real-run");
        assertThat(AiPortfolioProtocol.project(request).toString()).doesNotContain("real-cell", "real-block", "real-run");
        ObjectNode output = envelope("MODIFY_PAGE"); ObjectNode op = operation(request, "UPDATE_TEXT");
        op.set("target", target); op.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveNode(request, target)));
        op.withObject("payload").put("field", "text").put("value", "新文字"); output.withArray("operations").add(op);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    @Test
    void parameterUpdatesEnforceContactFieldCombinationAndScheduleRangeSemantics() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode component = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0);
        component.put("componentType", "CONTACT_FORM"); component.putObject("config").putArray("fields").add("contactName").add("phone");
        ObjectNode output = envelope("MODIFY_PAGE"); ObjectNode op = operation(request, "UPDATE_PARAMS");
        op.putObject("payload").putObject("params").putArray("fields").add("contactName").add("needs");
        output.withArray("operations").add(op);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        op.withObject("payload").withObject("params").putArray("fields").add("contactName").add("wechat");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        component.put("componentType", "SCHEDULE_QUERY"); component.putObject("config").putObject("queryRange").put("type", "UNLIMITED");
        op.put("expectedHash", AiPortfolioProtocol.expectedHash(component));
        ObjectNode range = op.putObject("payload").putObject("params").putObject("queryRange").put("type", "UNLIMITED").put("futureDays", 7);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        range.remove("futureDays");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        range.put("type", "DATE_RANGE").put("startDate", "2026-02-30").put("endDate", "2026-03-01");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    @Test
    void gridFontsOnlyBindRunsAndRunCatalogDoesNotAdvertiseDiscardedLineHeight() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode component = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0);
        component.put("componentType", "TEXT_GRID");
        ObjectNode cell = component.putObject("config").putArray("cells").addObject().put("cellKey", "real-cell");
        ObjectNode block = cell.putArray("blocks").addObject().put("blockKey", "real-block");
        block.putArray("runs").addObject().put("runKey", "real-run").put("text", "文字");
        ((ObjectNode) request.path("facts").path("bindings").get(0)).put("reuseOnly", false);
        ObjectNode contract = AiPortfolioProtocol.contract(request);
        assertThat(contract.path("nodeParams").path("GRID_RUN").has("lineHeight")).isFalse();
        assertThat(contract.path("componentCatalog").path("TEXT_GRID").path("params").path("cells").path("items")
                .path("properties").path("blocks").path("items").path("properties").path("runs").path("items")
                .path("properties").has("lineHeight")).isFalse();
        ObjectNode output = envelope("MODIFY_PAGE"); ObjectNode op = operation(request, "BIND_RESOURCES");
        ObjectNode target = op.withObject("target").put("cellKey", "cell_1");
        op.putObject("payload").putArray("sourceNos");
        op.withObject("payload").putArray("bindings").addObject().put("slot", "fontId").put("ref", "font_1");
        output.withArray("operations").add(op);
        for (String level : List.of("cell", "block", "run")) {
            if (level.equals("block")) { target.put("blockKey", "block_1"); }
            if (level.equals("run")) { target.put("runKey", "run_1"); }
            op.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveNode(request, target)));
            if (level.equals("run")) { assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output); }
            else { assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class); }
        }
    }

    @Test
    void structuredSpacerAcceptsSpacingButRejectsTextStyleAndFontBinding() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode component = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0);
        component.put("componentType", "STRUCTURED_TEXT_SECTION");
        var blocks = component.putObject("config").putArray("blocks");
        blocks.addObject().put("blockKey", "paragraph").put("type", "PARAGRAPH").put("content", "正文");
        blocks.addObject().put("blockKey", "spacer").put("type", "SPACER").put("heightRpx", 32);
        ObjectNode output = envelope("MODIFY_PAGE"); ObjectNode op = operation(request, "UPDATE_TEXT");
        ObjectNode target = op.withObject("target").put("blockKey", "block_2");
        op.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveNode(request, target)));
        output.withArray("operations").add(op);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        op.put("op", "UPDATE_PARAMS"); op.putObject("payload").putObject("params").put("heightRpx", 64);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        op.withObject("payload").withObject("params").put("fontSizeRpx", 32);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        ((ObjectNode) request.path("facts").path("bindings").get(0)).put("reuseOnly", false);
        op.put("op", "BIND_RESOURCES"); op.putObject("payload").putArray("sourceNos");
        op.withObject("payload").putArray("bindings").addObject().put("slot", "fontId").put("ref", "font_1");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    @Test
    void structuredTextUpdatesRejectBlankContentAndListItems() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode component = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0);
        component.put("componentType", "STRUCTURED_TEXT_SECTION");
        ObjectNode block = component.putObject("config").putArray("blocks").addObject().put("blockKey", "real-block")
                .put("type", "PARAGRAPH").put("content", "原正文");
        ObjectNode output = envelope("MODIFY_PAGE"); ObjectNode op = operation(request, "UPDATE_TEXT");
        ObjectNode target = op.withObject("target").put("blockKey", "block_1");
        op.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveNode(request, target)));
        op.withObject("payload").put("value", " \n "); output.withArray("operations").add(op);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        block.put("type", "LIST"); block.remove("content"); block.putArray("items").add("原列表项");
        op.put("expectedHash", AiPortfolioProtocol.expectedHash(block)); op.withObject("payload").put("field", "items").putArray("value").add("有效项").add(" ");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        op.withObject("payload").putArray("value").add("有效项");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    @Test
    void runtimeAndJobSourcesAndCatalogAreExactlyTheSame() throws IOException {
        Path local = Path.of("").toAbsolutePath(); Path root = local.getParent();
        if (local.getFileName().toString().equals("wefolio-java-runtime")) { root = local.getParent(); }
        for (String file : List.of("AiPortfolioProtocol.java", "AiProtocolException.java", "StrictAiJson.java")) {
            String relative = "src/main/java/com/jxc/wefolio/service/portfolioai/" + file;
            assertThat(Files.readString(root.resolve("wefolio-java-runtime/" + relative)))
                    .isEqualTo(Files.readString(root.resolve("wefolio-java-job/" + relative)));
        }
        assertThat(Files.readString(root.resolve("wefolio-java-runtime/src/main/resources/portfolioai/contract-v1.json")))
                .isEqualTo(Files.readString(root.resolve("wefolio-java-job/src/main/resources/portfolioai/contract-v1.json")));
    }
}
