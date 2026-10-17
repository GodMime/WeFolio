package com.jxc.wefolio.service.portfolioai;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.JoinStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dict.WfTagStatusDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioHyperlinkTargetResponse;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamMemberEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.entity.WfTagEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.entity.WorkTagEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamMemberEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.message.MineProfileMessage;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.PortfolioBackgroundAudioService;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import com.jxc.wefolio.service.PortfolioRenderService;
import com.jxc.wefolio.service.UserContactCryptoService;
import com.jxc.wefolio.service.UserContactProfileService;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import com.jxc.wefolio.service.PortfolioHyperlinkGraphService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;

/** 真实事实冻结边界：隐藏精确原值、失效编号保留、标签隔离及当前来源授权。 */
@ExtendWith(MockitoExtension.class)
class PortfolioAiFactsServiceTest {
    /** 作品与其用户标签读取器。 */
    @Mock private WorkEntityMapper works;
    @Mock private WorkTagEntityMapper workTags;
    @Mock private WfTagEntityMapper tags;
    /** 作品集和当前有效成员读取器。 */
    @Mock private PortfolioEntityMapper portfolios;
    @Mock private TeamMemberEntityMapper members;
    /** 公开资料读取器，不接触真实账户配置。 */
    @Mock private UserEntityMapper users;
    @Mock private TeamEntityMapper teams;
    /** 团队入口与本地字体目录边界。 */
    @Mock private TeamPortfolioAccessService access;
    @Mock private PortfolioFontSources fontSources;
    /** 普通已发布个人超链接目标策略。 */
    @Mock private PortfolioHyperlinkGraphService hyperlinkGraph;
    /** 公开联系方式使用独立密文，默认值沿既有资料契约解析。 */
    @Mock private UserContactCryptoService contactCrypto;
    /** 模型媒体地址只通过已授权数据库对象键生成。 */
    @Mock private CosService cos;
    /** 待测真实应用事实服务。 */
    private PortfolioAiFactsService service;

    /** 所有测试只提供模拟数据库记录，不加载环境文件或网络配置。 */
    @BeforeEach void prepare() {
        service = new PortfolioAiFactsService(works, workTags, tags, portfolios, members, users, teams, access, fontSources, hyperlinkGraph, new UserContactProfileService(contactCrypto), cos);
        lenient().when(hyperlinkGraph.listTargets(any(), any(), any(), any())).thenReturn(new PortfolioHyperlinkTargetResponse());
    }

    /** 解析固定且无密钥的测试数据。 */
    private ObjectNode node(String json) { return StrictAiJson.parseObject(json, StrictAiJson.RESPONSE_MAX_BYTES, 20); }

    /** 构造当前本人在首次保存之前使用的完整编辑会话。 */
    private ObjectNode input() {
        return node("""
                {"ownerType":"USER","ownerId":"101","portfolioId":null,"mode":"MODIFY_PAGE",
                 "targetMenuKey":"__root__","prompt":"调整页面","aiContext":{"version":1,"nextSourceNo":1,"sources":[]}}
                """);
    }

    /** 最小完整普通基准，投影不可生成另一菜单的内容。 */
    private ObjectNode base() {
        return node("{\"schemaVersion\":\"standard-personal-v1\",\"fonts\":{},\"components\":[]}");
    }

    /** 从冻结事实构造共享协议投影，直接验证最终发给模型的数据。 */
    private ObjectNode projected(ObjectNode input, ObjectNode facts) {
        ObjectNode request = node("{}"); request.set("input", input); request.set("facts", facts);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        return AiPortfolioProtocol.project(request);
    }

    /** 构造本人或成员当前可使用的公开用户记录。 */
    private UserEntity user(long id) {
        UserEntity value = new UserEntity(); value.setId(id); value.setDeleted(0L);
        value.setStatus(UserStatusDict.ACTIVE.getCode()); value.setNickname("公开名称"); return value;
    }

    /** 构造审核通过的图片来源。 */
    private WorkEntity work(long id, long owner) {
        WorkEntity value = new WorkEntity(); value.setId(id); value.setUserId(owner); value.setDeleted(0L);
        value.setStatus(WorkStatusDict.ACTIVE.getCode()); value.setAuditStatus(WorkAuditStatusDict.PASSED.getCode());
        value.setMediaType("IMAGE"); value.setTitle("已授权作品"); return value;
    }

    /** 为会话增加来源，编号只来自编辑上下文。 */
    private void source(ObjectNode input, int number, String kind, long id) {
        input.withObject("aiContext").withArray("sources").addObject().put("sourceNo", number).put("kind", kind).put("sourceId", Long.toString(id));
    }

    /** 按类型读取隐藏句柄，便于核对原文精确保存而非模型猜测。 */
    private JsonNode binding(ObjectNode facts, String kind) {
        for (JsonNode value : facts.path("bindings")) { if (kind.equals(value.path("kind").asText())) { return value; } }
        throw new AssertionError("预期隐藏句柄未生成：" + kind);
    }

    /** 本人公开 HTTPS 头像可提供分享头像句柄，原值只保留于冻结事实。 */
    @Test void personalProfileAvatarProvidesHiddenShareAvatarBinding() {
        UserEntity owner = user(101); owner.setAvatarUrl("https://cdn.test/WFOWNER/others/avatar.jpg");
        when(users.selectById(101L)).thenReturn(owner);

        ObjectNode facts = service.freeze(input(), base());
        JsonNode image = binding(facts, MediaTypeDict.IMAGE.getCode());

        assertThat(image.path("ref").asText()).isEqualTo("profile_share_avatar");
        assertThat(image.path("allowedSlots").toString()).isEqualTo("[\"avatarUrl\"]");
        assertThat(image.path("label").asText()).isEqualTo("本人头像（分享头像）");
        assertThat(image.path("value").asText()).isEqualTo(owner.getAvatarUrl());
        assertThat(image.path("reuseOnly").asBoolean()).isFalse();
        assertThat(image.has("sourceNo")).isFalse();
        assertThat(projected(input(), facts).path("resources").toString())
                .contains("profile_share_avatar").doesNotContain(owner.getAvatarUrl(), "\"value\"");
    }

    /** 缺少公开地址、HTTP 或带凭据和签名的头像均不能作为可新增的分享头像。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "WFOWNER/others/avatar.jpg", "http://cdn.test/avatar.jpg",
            "https://user:secret@cdn.test/avatar.jpg", "https://cdn.test/avatar.jpg?signature=secret",
            "https://cdn.test/avatar.jpg#secret", "https:///missing-host"})
    void invalidProfileAvatarDoesNotProvideShareAvatarBinding(String avatarUrl) {
        UserEntity owner = user(101); owner.setAvatarUrl(avatarUrl);
        when(users.selectById(101L)).thenReturn(owner);

        ObjectNode facts = service.freeze(input(), base());

        for (JsonNode resource : facts.path("bindings")) {
            assertThat(resource.path("ref").asText()).isNotEqualTo("profile_share_avatar");
        }
    }

    /** 已设置资料的两项联系原值可用于新组件，模型只能看到受控句柄。 */
    @Test void profileContactValuesBecomeHiddenBindingsWithoutModelExposure() {
        UserEntity user = user(101);
        user.setContactPhoneCiphertext("profile-phone-cipher"); user.setContactWechatCiphertext("profile-wechat-cipher");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("profile-phone-cipher")).thenReturn(" 13800138000 ");
        when(contactCrypto.decryptWechat("profile-wechat-cipher")).thenReturn(" profile_wechat ");
        ObjectNode input = input(), facts = service.freeze(input, base());
        JsonNode phone = binding(facts, "CONTACT_PHONE"), wechat = binding(facts, "CONTACT_WECHAT");
        assertThat(phone.path("ref").asText()).isEqualTo("profile_contact_phone");
        assertThat(wechat.path("ref").asText()).isEqualTo("profile_contact_wechat");
        assertThat(phone.path("value").asText()).isEqualTo("13800138000");
        assertThat(wechat.path("value").asText()).isEqualTo("profile_wechat");
        for (JsonNode value : List.of(phone, wechat)) {
            assertThat(value.path("reuseOnly").asBoolean()).isFalse();
            assertThat(value.has("componentKey")).isFalse();
        }
        assertThat(phone.path("allowedSlots").get(0).asText()).isEqualTo("contactPhone");
        assertThat(wechat.path("allowedSlots").get(0).asText()).isEqualTo("contactWechat");
        assertThat(projected(input, facts).toString()).contains("profile_contact_phone", "profile_contact_wechat")
                .doesNotContain("13800138000", "profile_wechat", "profile-phone-cipher", "profile-wechat-cipher", "\"value\"");
    }

    /** 任意单项已设置时都可独立绑定，另一项空白不阻止生成。 */
    @Test void singleConfiguredProfileContactIsBoundIndependently() {
        for (boolean hasPhone : List.of(true, false)) {
            UserEntity user = user(101);
            if (hasPhone) {
                user.setContactPhoneCiphertext("phone-cipher");
                when(contactCrypto.decryptPhone("phone-cipher")).thenReturn("13800138000");
            } else {
                user.setContactWechatCiphertext("wechat-cipher");
                when(contactCrypto.decryptWechat("wechat-cipher")).thenReturn("profile_wechat");
            }
            when(users.selectById(101L)).thenReturn(user);
            ObjectNode facts = service.freeze(input(), base());
            assertThat(binding(facts, hasPhone ? "CONTACT_PHONE" : "CONTACT_WECHAT").path("value").asText())
                    .isEqualTo(hasPhone ? "13800138000" : "profile_wechat");
            assertThat(facts.path("bindings")).noneMatch(value -> (hasPhone ? "CONTACT_WECHAT" : "CONTACT_PHONE").equals(value.path("kind").asText()));
        }
    }

    /** 未设置联系手机沿用公开资料的默认值，但默认原值仍不会发给模型。 */
    @Test void unsetProfilePhoneUsesExistingPublicDefaultWithoutModelExposure() {
        UserEntity user = user(101); user.setPhoneNumber("13911112222");
        when(users.selectById(101L)).thenReturn(user);
        ObjectNode input = input(), facts = service.freeze(input, base());
        assertThat(binding(facts, "CONTACT_PHONE").path("value").asText()).isEqualTo("13911112222");
        assertThat(facts.path("bindings")).noneMatch(value -> "CONTACT_WECHAT".equals(value.path("kind").asText()));
        assertThat(projected(input, facts).toString()).doesNotContain("13911112222");
        verifyNoInteractions(contactCrypto);
    }

    /** 明确清空不回填默认联系手机，空联系方式不生成隐藏句柄。 */
    @Test void explicitlyClearedContactsDoNotRestorePublicDefault() {
        UserEntity user = user(101); user.setPhoneNumber("13911112222");
        user.setContactPhoneCiphertext(""); user.setContactWechatCiphertext("");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("")).thenReturn(""); when(contactCrypto.decryptWechat("")).thenReturn("");
        ObjectNode facts = service.freeze(input(), base());
        assertThat(facts.path("bindings")).noneMatch(value -> Set.of("CONTACT_PHONE", "CONTACT_WECHAT").contains(value.path("kind").asText()));
        assertThat(facts.toString()).doesNotContain("13911112222");
    }

    /** 团队仅使用团队快照和明确输入，不能自动取当前操作者的个人联系资料。 */
    @Test void teamDoesNotLoadPersonalContactProfile() {
        ObjectNode input = input().put("ownerType", "TEAM").put("ownerId", "9");
        TeamEntity team = new TeamEntity(); team.setId(9L); team.setName("公开团队");
        when(teams.selectById(9L)).thenReturn(team);
        ObjectNode facts = service.freeze(input, base());
        assertThat(facts.path("bindings")).noneMatch(value -> Set.of("CONTACT_PHONE", "CONTACT_WECHAT").contains(value.path("kind").asText()));
        verify(users, never()).selectById(any()); verifyNoInteractions(contactCrypto);
    }

    /** 损坏手机密文沿公开资料回退默认值，另一项已设置微信仍可绑定。 */
    @Test void damagedContactPhoneUsesExistingPublicFallbackAndPreservesWechat() {
        UserEntity user = user(101); user.setPhoneNumber("13911112222");
        user.setContactPhoneCiphertext("damaged-phone-cipher"); user.setContactWechatCiphertext("wechat-cipher");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("damaged-phone-cipher")).thenThrow(new BusinessException(MineProfileMessage.CONTACT_CRYPTO_INVALID));
        when(contactCrypto.decryptWechat("wechat-cipher")).thenReturn("profile_wechat");
        ObjectNode facts = service.freeze(input(), base());
        assertThat(binding(facts, "CONTACT_WECHAT").path("value").asText()).isEqualTo("profile_wechat");
        assertThat(binding(facts, "CONTACT_PHONE").path("value").asText()).isEqualTo("13911112222");
        assertThat(projected(input(), facts).toString()).doesNotContain("13911112222", "profile_wechat", "damaged-phone-cipher");
    }

    /** 损坏微信密文按公开资料规则变为空串，不能影响有效手机隐藏句柄。 */
    @Test void damagedContactWechatFallsBackToEmptyWithoutDroppingPhone() {
        UserEntity user = user(101); user.setContactPhoneCiphertext("phone-cipher"); user.setContactWechatCiphertext("damaged-wechat-cipher");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("phone-cipher")).thenReturn("13800138000");
        when(contactCrypto.decryptWechat("damaged-wechat-cipher")).thenThrow(new BusinessException(MineProfileMessage.CONTACT_CRYPTO_INVALID));
        ObjectNode facts = service.freeze(input(), base());
        assertThat(binding(facts, "CONTACT_PHONE").path("value").asText()).isEqualTo("13800138000");
        assertThat(facts.path("bindings")).noneMatch(value -> "CONTACT_WECHAT".equals(value.path("kind").asText()));
    }

    /** 没有资料默认手机或显式联系方式时，不造号码或空值句柄。 */
    @Test void missingProfileContactsDoNotCreateEmptyBindings() {
        when(users.selectById(101L)).thenReturn(user(101));
        ObjectNode facts = service.freeze(input(), base());
        assertThat(facts.path("bindings")).noneMatch(value -> Set.of("CONTACT_PHONE", "CONTACT_WECHAT").contains(value.path("kind").asText()));
        verifyNoInteractions(contactCrypto);
    }

    /** 联系资料进入 AI 事实前沿普通联系文本规则校验，非法值不能变成可信句柄。 */
    @Test void invalidDecryptedProfileContactIsRejectedBeforeBinding() {
        UserEntity user = user(101); user.setContactPhoneCiphertext("phone-cipher");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("phone-cipher")).thenReturn("13800138000\n额外文本");
        assertThatThrownBy(() -> service.freeze(input(), base())).isInstanceOf(BusinessException.class)
                .hasMessage(MineProfileMessage.CONTACT_FORMAT_INVALID);
    }

    /** 提供新的公开联系句柄不意味着改写已保存快照，整体修改没有绑定操作时保持原值。 */
    @Test void profileContactHandlesDoNotSilentlyReplaceExistingSnapshot() {
        UserEntity user = user(101); user.setContactPhoneCiphertext("phone-cipher");
        when(users.selectById(101L)).thenReturn(user); when(contactCrypto.decryptPhone("phone-cipher")).thenReturn("13800138000");
        ObjectNode input = input().put("mode", "MODIFY_PAGE");
        ObjectNode base = base();
        base.withArray("components").addObject().put("componentKey", "existing-contact").put("componentType", "CONTACT_INFO")
                .put("enabled", true).put("sortOrder", 1000).putObject("config").put("contactPhone", "13911112222").put("contactWechat", "saved_wechat");
        ObjectNode request = node("{}"); request.set("input", input); request.set("facts", service.freeze(input, base));
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ObjectNode output = node("""
                {"status":"CANDIDATE","mode":"MODIFY_PAGE","operations":[],"questions":[],"notes":[],"page":null}
                """);
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        AiPortfolioProtocol.validate(request, output.toString());
        JsonNode snapshot = PortfolioAiCompiler.compile(802L, request, output).path("components").get(0).path("config");
        assertThat(snapshot.path("contactPhone").asText()).isEqualTo("13911112222");
        assertThat(snapshot.path("contactWechat").asText()).isEqualTo("saved_wechat");
    }

    /** 非密文损坏的系统异常继续上抛，避免把配置故障默认为资料缺失。 */
    @Test void contactCryptoSystemFailureIsNotSuppressed() {
        UserEntity user = user(101); user.setContactPhoneCiphertext("phone-cipher");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("phone-cipher")).thenThrow(new IllegalStateException("模拟加密配置不可用"));
        assertThatThrownBy(() -> service.freeze(input(), base())).isInstanceOf(IllegalStateException.class).hasMessage("模拟加密配置不可用");
    }

    /** 从公开资料冻结到真实编译、草稿校验与预览输出，保证候选可见且联系快照不被改写。 */
    @Test void profileContactBindingsReachCompiledCandidateAndRealPreview() {
        UserEntity user = user(101); user.setContactPhoneCiphertext("phone-cipher"); user.setContactWechatCiphertext("wechat-cipher");
        when(users.selectById(101L)).thenReturn(user);
        when(contactCrypto.decryptPhone("phone-cipher")).thenReturn("13800138000");
        when(contactCrypto.decryptWechat("wechat-cipher")).thenReturn("profile_wechat");
        ObjectNode input = input().put("mode", "CREATE").put("editorSchemaRevision", 14);
        ObjectNode facts = service.freeze(input, base()), request = node("{}");
        request.set("input", input); request.set("facts", facts);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ObjectNode projection = AiPortfolioProtocol.project(request);
        ObjectNode output = node("""
                {"status":"CANDIDATE","mode":"CREATE","operations":[],"questions":[],"notes":[],
                 "page":{"menuKey":"menu_current","settings":{},"components":[
                  {"type":"CONTACT_INFO","sourceNos":[],"params":{},"bindings":[]}]}}
                """);
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        for (JsonNode resource : projection.path("resources")) {
            if (Set.of("CONTACT_PHONE", "CONTACT_WECHAT").contains(resource.path("kind").asText())) {
                ((ObjectNode) output.withObject("page").withArray("components").get(0)).withArray("bindings").addObject()
                        .put("slot", resource.path("allowedSlots").get(0).asText()).put("ref", resource.path("ref").asText());
            }
        }
        AiPortfolioProtocol.validate(request, output.toString());
        ObjectNode candidate = PortfolioAiCompiler.compile(801L, request, output);
        PortfolioConfigDto normalized = new PortfolioConfigValidator(works).normalizeForDraft(101L,
                JSON.parseObject(candidate.toString(), PortfolioConfigDto.class), null);
        PortfolioRenderService renderer = new PortfolioRenderService(works, portfolios, mock(CosService.class), mock(PortfolioBackgroundAudioService.class));
        PortfolioEntity context = new PortfolioEntity(); context.setOwnerId(101L);
        PortfolioRenderDto rendered = renderer.render(context, normalized, true, false, null, null);
        assertThat(rendered.getComponents()).hasSize(1);
        assertThat(rendered.getComponents().getFirst().getContactInfo()).containsAllEntriesOf(Map.of(
                "contactPhone", "13800138000", "contactWechat", "profile_wechat"));
        assertThat(projection.toString()).doesNotContain("13800138000", "profile_wechat");
    }

    /** 精确原文可绑定，但手机、微信号冒号格式与带查询参数的外链都不发给模型。 */
    @Test void explicitPhoneWechatAndUrlAreHiddenAndProjectionContainsNoPrivateValue() {
        ObjectNode input = input();
        String url = "https://example.test/path?token=private_token";
        input.put("prompt", "请添加联系手机13800138000，微信号：private_wx，外链" + url);
        input.putObject("clarificationAnswer").put("answerText", "微信号: private_wx");
        when(users.selectById(101L)).thenReturn(user(101));
        ObjectNode facts = service.freeze(input, base());
        assertThat(binding(facts, "CONTACT_PHONE").path("value").asText()).isEqualTo("13800138000");
        assertThat(binding(facts, "CONTACT_WECHAT").path("value").asText()).isEqualTo("private_wx");
        assertThat(binding(facts, "EXTERNAL_LINK").path("value").asText()).isEqualTo(url);
        long wechatCount = 0;
        for (JsonNode value : facts.path("bindings")) { if ("CONTACT_WECHAT".equals(value.path("kind").asText())) { wechatCount++; } }
        assertThat(wechatCount).isEqualTo(1);
        String projection = projected(input, facts).toString();
        assertThat(projection).doesNotContain("13800138000", "private_wx", "private_token", "https://", "value", "avatarUrl");
    }

    /** 带用户名密码或缺少主机的外链不生成资源句柄，也不向模型泄露原文。 */
    @Test void ambiguousOrCredentialBearingUrlsAreNotTurnedIntoBindings() {
        ObjectNode input = input().put("prompt", "链接https://user:password@example.test/private 以及 https:///missing-host");
        when(users.selectById(101L)).thenReturn(user(101));
        ObjectNode facts = service.freeze(input, base());
        for (JsonNode value : facts.path("bindings")) { assertThat(value.path("kind").asText()).isNotEqualTo("EXTERNAL_LINK"); }
        assertThat(projected(input, facts).toString()).doesNotContain("password", "example.test", "missing-host");
    }

    /** 未使用的失效项继续保留素材号，模型只能得知不可新增绑定。 */
    @Test void unavailableUnusedSourceRetainsItsNumberWithoutLeakingDetails() {
        ObjectNode input = input(); source(input, 7, "WORK", 404);
        when(users.selectById(101L)).thenReturn(user(101));
        ObjectNode facts = service.freeze(input, base());
        JsonNode frozen = facts.path("sources").get(0);
        assertThat(frozen.path("sourceNo").intValue()).isEqualTo(7);
        assertThat(frozen.path("canBind").booleanValue()).isFalse();
        assertThat(frozen.has("title")).isFalse();
        JsonNode safe = projected(input, facts).path("sources").get(0);
        assertThat(safe.path("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(safe.has("sourceId")).isFalse();
        assertThat(frozen.has("mediaUrl")).isFalse(); assertThat(frozen.has("coverUrl")).isFalse();
        verifyNoInteractions(cos);
    }

    /** 已授权个人图片和视频只从数据库对象键生成媒体地址，客户端来源额外地址不参与冻结。 */
    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO"})
    void authorizedPersonalWorkFreezesOnlyServerGeneratedCosUrls(String mediaType) {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        ((ObjectNode) input.path("aiContext").path("sources").get(0)).put("mediaUrl", "https://untrusted.test/media");
        WorkEntity work = work(201, 101); work.setMediaType(mediaType);
        work.setMediaObjectKey("WFOWNER/work/video/authorized.mp4"); work.setCoverObjectKey("WFOWNER/work/image/cover.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/video/authorized.mp4");
        when(cos.publicUrl(work.getCoverObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/cover.jpg");

        JsonNode frozen = service.freeze(input, base()).path("sources").get(0);

        assertThat(frozen.path("mediaUrl").asText()).isEqualTo("https://cdn.test/WFOWNER/work/video/authorized.mp4");
        assertThat(frozen.path("coverUrl").asText()).isEqualTo("https://cdn.test/WFOWNER/work/image/cover.jpg");
        assertThat(frozen.path("canBind").asBoolean()).isTrue();
        assertThat(frozen.toString()).doesNotContain("untrusted.test");
        verify(cos).publicUrl(work.getMediaObjectKey()); verify(cos).publicUrl(work.getCoverObjectKey());
    }

    /** 图片作品封面句柄绑定原图，视频与动图只绑定已有静态封面。 */
    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "ANIMATION"})
    void personalWorkProvidesShareCoverBindingFromAuthorizedImage(String mediaType) {
        ObjectNode input = input(); source(input, 3, "WORK", 201);
        WorkEntity work = work(201, 101); work.setMediaType(mediaType);
        work.setMediaObjectKey("WFOWNER/work/image/original.jpg"); work.setCoverObjectKey("WFOWNER/work/image/cover.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/original.jpg");
        when(cos.publicUrl(work.getCoverObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/cover.jpg");

        ObjectNode facts = service.freeze(input, base());
        JsonNode image = binding(facts, MediaTypeDict.IMAGE.getCode());

        assertThat(image.path("ref").asText()).isEqualTo("source_cover_3");
        assertThat(image.path("sourceNo").intValue()).isEqualTo(3);
        assertThat(image.path("allowedSlots").toString()).isEqualTo("[\"coverUrl\",\"avatarUrl\"]");
        assertThat(image.path("reuseOnly").asBoolean()).isFalse();
        assertThat(image.path("value").asText()).isEqualTo(MediaTypeDict.IMAGE.getCode().equals(mediaType)
                ? "https://cdn.test/WFOWNER/work/image/original.jpg" : "https://cdn.test/WFOWNER/work/image/cover.jpg");
        assertThat(image.has("sourceId")).isFalse();
    }

    /** 图片缺少原图公开地址时可使用服务端已确认的封面，不能使用客户端提供的地址。 */
    @Test void imageShareCoverFallsBackToAuthorizedCover() {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        ((ObjectNode) input.path("aiContext").path("sources").get(0)).put("coverUrl", "https://untrusted.test/cover.jpg");
        WorkEntity work = work(201, 101); work.setCoverObjectKey("WFOWNER/work/image/cover.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getCoverObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/cover.jpg");

        ObjectNode facts = service.freeze(input, base());

        assertThat(binding(facts, MediaTypeDict.IMAGE.getCode()).path("value").asText())
                .isEqualTo("https://cdn.test/WFOWNER/work/image/cover.jpg");
        assertThat(facts.toString()).doesNotContain("untrusted.test");
    }

    /** 视频和动图没有静态图片封面时，不得把播放地址绑定为分享封面。 */
    @ParameterizedTest
    @ValueSource(strings = {"VIDEO", "ANIMATION"})
    void motionWorkWithoutImageCoverDoesNotProvideShareCoverBinding(String mediaType) {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        WorkEntity work = work(201, 101); work.setMediaType(mediaType);
        work.setMediaObjectKey("WFOWNER/work/video/film.mp4");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/video/film.mp4");

        ObjectNode facts = service.freeze(input, base());

        assertThat(facts.path("sources").get(0).path("canBind").asBoolean()).isTrue();
        for (JsonNode resource : facts.path("bindings")) {
            assertThat(resource.path("kind").asText()).isNotEqualTo(MediaTypeDict.IMAGE.getCode());
        }
    }

    /** 音频自选封面有独立的图片失效规则，未复用该规则前不作为新分享封面句柄。 */
    @Test void audioCoverDoesNotProvideShareCoverBinding() {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        WorkEntity work = work(201, 101); work.setMediaType(MediaTypeDict.AUDIO.getCode());
        work.setCoverObjectKey("WFOWNER/work/image/cover.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getCoverObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/cover.jpg");

        ObjectNode facts = service.freeze(input, base());

        for (JsonNode resource : facts.path("bindings")) {
            assertThat(resource.path("kind").asText()).isNotEqualTo(MediaTypeDict.IMAGE.getCode());
        }
    }

    /** 被候选使用的封面句柄必须回溯实际素材号，冻结之后删除的作品不能继续绑定。 */
    @Test void usedShareCoverReferenceRevalidatesItsCurrentWorkSource() {
        ObjectNode input = input(); source(input, 1, "WORK", 201); source(input, 7, "WORK", 404);
        WorkEntity work = work(201, 101); work.setMediaObjectKey("WFOWNER/work/image/photo.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/photo.jpg");
        ObjectNode facts = service.freeze(input, base()), request = node("{}");
        request.set("input", input); request.set("facts", facts);
        ObjectNode output = node("{\"page\":{\"settings\":{\"share\":{\"bindings\":[{\"slot\":\"coverUrl\",\"ref\":\"source_cover_1\"}]}}}}");
        clearInvocations(works);

        service.revalidate(request, output);

        verify(works).selectById(201L); verify(works, never()).selectById(404L);
        work.setDeleted(201L);
        assertThatThrownBy(() -> service.revalidate(request, output)).isInstanceOf(BusinessException.class);
    }

    /** 输出未使用的封面句柄不触发失效素材复核，保留候选的既有按需校验语义。 */
    @Test void unusedShareCoverReferenceDoesNotRevalidateItsSource() {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        WorkEntity work = work(201, 101); work.setMediaObjectKey("WFOWNER/work/image/photo.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFOWNER/work/image/photo.jpg");
        ObjectNode facts = service.freeze(input, base()), request = node("{}");
        request.set("input", input); request.set("facts", facts);
        work.setDeleted(201L); clearInvocations(works);

        service.revalidate(request, node("{\"operations\":[],\"notes\":[\"保留 source_cover_1\"]}"));

        verify(works, never()).selectById(201L);
    }

    /** 团队分享封面仍要求自有作品集上传素材，不能生成不可保存的成员作品封面句柄。 */
    @Test void authorizedTeamMemberImageDoesNotProvideShareCoverBinding() {
        ObjectNode input = input().put("ownerType", PortfolioOwnerTypeDict.TEAM.getCode()).put("ownerId", "9");
        source(input, 1, "WORK", 201);
        TeamEntity team = new TeamEntity(); team.setId(9L); team.setName("公开团队");
        team.setAvatarUrl("https://cdn.test/WFTEAM/others/avatar.jpg");
        when(teams.selectById(9L)).thenReturn(team); when(users.selectById(12L)).thenReturn(user(12));
        TeamMemberEntity member = new TeamMemberEntity(); member.setTeamId(9L); member.setUserId(12L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(1);
        when(members.selectList(any())).thenReturn(List.of(member));
        WorkEntity work = work(201, 12); work.setMediaObjectKey("WFMEMBER/work/image/photo.jpg");
        when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFMEMBER/work/image/photo.jpg");

        ObjectNode facts = service.freeze(input, base());

        assertThat(facts.path("sources").get(0).path("canBind").asBoolean()).isTrue();
        for (JsonNode resource : facts.path("bindings")) {
            assertThat(resource.path("kind").asText()).isNotEqualTo(MediaTypeDict.IMAGE.getCode());
        }
    }

    /** 审核未通过、处理中、已删除、非本人或作者注销时都不能生成媒体地址。 */
    @Test void unavailablePersonalWorkNeverGeneratesCosUrls() {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        UserEntity owner = user(101), foreign = user(102);
        when(users.selectById(101L)).thenReturn(owner); when(users.selectById(102L)).thenReturn(foreign);
        WorkEntity work = work(201, 101); work.setMediaObjectKey("WFOWNER/work/video/private.mp4");
        work.setCoverObjectKey("WFOWNER/work/image/private.jpg"); when(works.selectById(201L)).thenReturn(work);
        for (int scenario = 0; scenario < 5; scenario++) {
            work.setAuditStatus(WorkAuditStatusDict.PASSED.getCode()); work.setStatus(WorkStatusDict.ACTIVE.getCode());
            work.setDeleted(0L); work.setUserId(101L); owner.setDeleted(0L);
            switch (scenario) {
                case 0 -> work.setAuditStatus(WorkAuditStatusDict.REJECTED.getCode());
                case 1 -> work.setStatus(WorkStatusDict.PROCESSING.getCode());
                case 2 -> work.setDeleted(201L);
                case 3 -> work.setUserId(102L);
                default -> owner.setDeleted(101L);
            }
            JsonNode frozen = service.freeze(input, base()).path("sources").get(0);
            assertThat(frozen.path("canBind").asBoolean()).isFalse();
            assertThat(frozen.has("mediaUrl")).isFalse(); assertThat(frozen.has("coverUrl")).isFalse();
        }
        verifyNoInteractions(cos);
    }

    /** 相对键、HTTP、用户凭据和签名参数不作为模型可读取媒体地址。 */
    @ParameterizedTest
    @ValueSource(strings = {"WFOWNER/work/image/photo.jpg", "http://cdn.test/photo.jpg",
            "https://user:secret@cdn.test/photo.jpg", "https://cdn.test/photo.jpg?signature=secret",
            "https://cdn.test/photo.jpg#secret", "https:///missing-host"})
    void invalidOrCredentialBearingCosUrlsAreNotFrozen(String url) {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        WorkEntity work = work(201, 101); work.setMediaObjectKey("WFOWNER/work/image/photo.jpg");
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn(url);
        ObjectNode facts = service.freeze(input, base());
        JsonNode frozen = facts.path("sources").get(0);
        assertThat(frozen.path("canBind").asBoolean()).isTrue();
        assertThat(frozen.has("mediaUrl")).isFalse(); assertThat(frozen.has("coverUrl")).isFalse();
        for (JsonNode resource : facts.path("bindings")) {
            assertThat(resource.path("kind").asText()).isNotEqualTo(MediaTypeDict.IMAGE.getCode());
        }
    }

    /** 团队成员作品在当前 allowWorks 授权后提供视频链接，权限撤回后不继续冻结旧链接。 */
    @Test void teamWorkUrlsRequireCurrentMemberWorkPermission() {
        ObjectNode input = input().put("ownerType", "TEAM").put("ownerId", "9"); source(input, 1, "WORK", 201);
        TeamEntity team = new TeamEntity(); team.setId(9L); team.setName("公开团队");
        when(teams.selectById(9L)).thenReturn(team); when(users.selectById(12L)).thenReturn(user(12));
        TeamMemberEntity member = new TeamMemberEntity(); member.setTeamId(9L); member.setUserId(12L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(1); member.setAllowPortfolio(0);
        when(members.selectList(any())).thenReturn(List.of(member));
        WorkEntity work = work(201, 12); work.setMediaType("VIDEO"); work.setMediaObjectKey("WFMEMBER/work/video/film.mp4");
        when(works.selectById(201L)).thenReturn(work);
        when(cos.publicUrl(work.getMediaObjectKey())).thenReturn("https://cdn.test/WFMEMBER/work/video/film.mp4");
        JsonNode frozen = service.freeze(input, base()).path("sources").get(0);
        assertThat(frozen.path("mediaUrl").asText()).isEqualTo("https://cdn.test/WFMEMBER/work/video/film.mp4");
        assertThat(frozen.path("canBind").asBoolean()).isTrue();
        clearInvocations(cos);

        member.setAllowWorks(0);
        frozen = service.freeze(input, base()).path("sources").get(0);
        assertThat(frozen.path("canBind").asBoolean()).isFalse();
        assertThat(frozen.has("mediaUrl")).isFalse(); assertThat(frozen.has("coverUrl")).isFalse();
        verifyNoInteractions(cos);
    }

    /** 只投影作品所属用户的当前启用真实标签，不能混入别人或停用标签。 */
    @Test void workTagsUseActualCurrentUserLabelsAndExcludeForeignOrDisabledTags() {
        ObjectNode input = input(); source(input, 1, "WORK", 201);
        when(users.selectById(101L)).thenReturn(user(101)); when(works.selectById(201L)).thenReturn(work(201, 101));
        WorkTagEntity link = new WorkTagEntity(); link.setWorkId(201L); link.setUserId(101L); link.setTagId(301L);
        when(workTags.selectList(any())).thenReturn(List.of(link));
        WfTagEntity valid = new WfTagEntity(); valid.setId(301L); valid.setUserId(101L); valid.setStatus(WfTagStatusDict.ACTIVE.getCode()); valid.setName("真实婚礼标签");
        WfTagEntity foreign = new WfTagEntity(); foreign.setId(302L); foreign.setUserId(102L); foreign.setStatus(WfTagStatusDict.ACTIVE.getCode()); foreign.setName("他人私密标签");
        WfTagEntity disabled = new WfTagEntity(); disabled.setId(303L); disabled.setUserId(101L); disabled.setStatus(WfTagStatusDict.DISABLED.getCode()); disabled.setName("停用标签");
        when(tags.selectBatchIds(anyCollection())).thenReturn(List.of(valid, foreign, disabled));
        ObjectNode facts = service.freeze(input, base());
        assertThat(facts.path("sources").get(0).path("tags").toString()).isEqualTo("[\"真实婚礼标签\"]");
        assertThat(projected(input, facts).toString()).contains("真实婚礼标签").doesNotContain("他人私密标签", "停用标签");
    }

    /** 当前复验仅查询候选确实绑定的素材，未使用的失效号不阻塞合法候选。 */
    @Test void revalidationChecksOnlyActuallyBoundSourceNumbers() {
        ObjectNode input = input(); source(input, 1, "WORK", 201); source(input, 7, "WORK", 404);
        ObjectNode request = node("{}"); request.set("input", input);
        when(works.selectById(201L)).thenReturn(work(201, 101)); when(users.selectById(101L)).thenReturn(user(101));
        service.revalidate(request, node("{\"operations\":[{\"payload\":{\"sourceNos\":[1],\"bindings\":[]}}]}"));
        verify(works).selectById(201L); verify(works, never()).selectById(404L);
        assertThatThrownBy(() -> service.revalidate(request, node("{\"bindings\":[{\"slot\":\"backgroundWorkId\",\"sourceNo\":7}]}")))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.SOURCE_UNAVAILABLE);
    }

    /** 个人归属不能通过请求中的 ownerId 越权，已有高级作品集还必须匹配普通 schema。 */
    @Test void personalOwnerAndExistingPortfolioSchemaAreEnforced() {
        ObjectNode input = input(); when(users.selectById(101L)).thenReturn(user(101));
        assertThatThrownBy(() -> service.requireOwner(input, 102L)).isInstanceOf(BusinessException.class);
        input.put("portfolioId", "501");
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setId(501L); portfolio.setOwnerId(101L);
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode()); portfolio.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode()); portfolio.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        when(portfolios.selectById(501L)).thenReturn(portfolio);
        assertThatThrownBy(() -> service.requireOwner(input, 101L)).isInstanceOf(BusinessException.class);
        portfolio.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1);
        assertThat(service.requireOwner(input, 101L)).isSameAs(portfolio);
    }

    /** 尚无作品集 ID 的团队仍使用真实拥有者/管理者门禁，不能借未创建状态绕过权限。 */
    @Test void teamCreationRequiresCurrentMaintenanceRole() {
        ObjectNode input = input().put("ownerType", "TEAM").put("ownerId", "9");
        Set<String> roles = Set.of(TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());
        doThrow(new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE)).when(access).requireTeamRole(9L, 101L, roles);
        assertThatThrownBy(() -> service.requireOwner(input, 101L)).isInstanceOf(BusinessException.class);
        verify(access).requireTeamRole(9L, 101L, roles); verify(portfolios, never()).selectById(any());
    }

    /** 成员 allowWorks 与 allowPortfolio 独立授权，高级个人入口不可放宽成员作品权限。 */
    @Test void memberWorkAndPortfolioPermissionsAreIndependent() {
        ObjectNode input = input().put("ownerType", "TEAM").put("ownerId", "9");
        source(input, 1, "WORK", 201); source(input, 2, "MEMBER_PORTFOLIO", 501);
        TeamEntity team = new TeamEntity(); team.setId(9L); team.setName("公开团队");
        when(teams.selectById(9L)).thenReturn(team); when(users.selectById(12L)).thenReturn(user(12));
        when(works.selectById(201L)).thenReturn(work(201, 12));
        TeamMemberEntity member = new TeamMemberEntity(); member.setTeamId(9L); member.setUserId(12L);
        member.setJoinStatus(JoinStatusDict.JOINED.getCode()); member.setAllowWorks(0); member.setAllowPortfolio(1);
        when(members.selectList(any())).thenReturn(List.of(member));
        PortfolioEntity child = new PortfolioEntity(); child.setId(501L); child.setOwnerId(12L); child.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        child.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode()); child.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        child.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1); child.setPublishedRevision(1);
        child.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode()); child.setShareCode("member-501");
        child.setPublishedConfigJson("{\"share\":{\"title\":\"成员高级作品集\"}}");
        when(portfolios.selectById(501L)).thenReturn(child);
        ObjectNode facts = service.freeze(input, base());
        assertThat(facts.path("sources").get(0).path("canBind").booleanValue()).isFalse();
        assertThat(facts.path("sources").get(0).has("memberName")).isFalse();
        assertThat(facts.path("sources").get(1).path("canBind").booleanValue()).isTrue();
        assertThat(facts.path("sources").get(1).path("memberName").asText()).isEqualTo("公开名称");
        assertThat(facts.path("sources").get(1).has("mediaUrl")).isFalse();
        assertThat(facts.path("sources").get(1).has("coverUrl")).isFalse();
        verifyNoInteractions(cos);
        member.setAllowWorks(1); member.setAllowPortfolio(0);
        facts = service.freeze(input, base());
        assertThat(facts.path("sources").get(0).path("canBind").booleanValue()).isTrue();
        assertThat(facts.path("sources").get(0).path("memberName").asText()).isEqualTo("公开名称");
        assertThat(facts.path("sources").get(1).path("canBind").booleanValue()).isFalse();
        assertThat(facts.path("sources").get(1).has("memberName")).isFalse();
    }

    /** 超链接句柄仅来自普通目标策略的可选项，冻结原值不会出现在模型投影。 */
    @Test void personalHyperlinkTargetsUseOnlySelectableVerifiedHandles() {
        when(users.selectById(101L)).thenReturn(user(101));
        PortfolioHyperlinkTargetResponse response = new PortfolioHyperlinkTargetResponse();
        PortfolioHyperlinkTargetResponse.TargetItem valid = new PortfolioHyperlinkTargetResponse.TargetItem();
        valid.setPortfolioId(501L); valid.setTitle("正式作品集"); valid.setSelectable(true);
        PortfolioHyperlinkTargetResponse.TargetItem denied = new PortfolioHyperlinkTargetResponse.TargetItem();
        denied.setPortfolioId(999L); denied.setTitle("不可用高级目标"); denied.setSelectable(false);
        response.setPortfolios(List.of(valid, denied));
        when(hyperlinkGraph.listTargets(101L, null, null, PortfolioTemplateTypeDict.ADVANCED.getCode())).thenReturn(response);
        ObjectNode input = input(), facts = service.freeze(input, base());
        JsonNode resource = binding(facts, "PERSONAL_PORTFOLIO");
        assertThat(resource.path("value").asLong()).isEqualTo(501L);
        assertThat(resource.path("reuseOnly").asBoolean()).isFalse();
        assertThat(projected(input, facts).toString()).contains("正式作品集").doesNotContain("501", "999", "不可用高级目标");
    }

    /** 取消发布的成员来源不能借历史publishedRevision暴露旧标题或内容。 */
    @Test void memberPortfolioMustStillBePublishedBeforeFreezingAnyTitle() {
        ObjectNode input = input().put("ownerType", "TEAM").put("ownerId", "9"); source(input, 1, "MEMBER_PORTFOLIO", 501);
        TeamEntity team = new TeamEntity(); team.setId(9L); team.setName("公开团队"); when(teams.selectById(9L)).thenReturn(team);
        PortfolioEntity child = new PortfolioEntity(); child.setId(501L); child.setOwnerId(12L); child.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        child.setTemplateType(PortfolioTemplateTypeDict.ADVANCED.getCode()); child.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        child.setSchemaVersion(PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1); child.setPublishedRevision(1);
        child.setPublishedConfigJson("{\"share\":{\"title\":\"撤回的私密标题\"}}");
        when(portfolios.selectById(501L)).thenReturn(child);
        ObjectNode facts = service.freeze(input, base());
        assertThat(facts.path("sources").get(0).path("canBind").asBoolean()).isFalse();
        assertThat(projected(input, facts).toString()).doesNotContain("撤回的私密标题");
    }
}
