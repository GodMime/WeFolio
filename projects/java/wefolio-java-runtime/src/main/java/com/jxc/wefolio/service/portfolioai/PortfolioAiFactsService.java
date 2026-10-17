package com.jxc.wefolio.service.portfolioai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioHyperlinkTargetResponse;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.PortfolioHyperlinkGraphService;
import com.jxc.wefolio.service.PortfolioContactInfoConfigSupport;
import com.jxc.wefolio.service.UserContactProfileService;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontSources;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 冻结经当前授权确认的来源事实；候选查询时再次验证，不把快照当授权凭据。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiFactsService {
    /** 服务端 JSON 节点工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** 团队维护角色。 */
    private static final Set<String> ROLES = Set.of(TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());
    /** 原文中无歧义的手机号、显式微信号和外链。 */
    private static final Pattern PHONE = Pattern.compile(PHONE_NUMBER_PATTERN);
    /** 明确微信号的原文捕获规则。 */
    private static final Pattern WECHAT = Pattern.compile(EXPLICIT_WECHAT_PATTERN);
    /** 明确 HTTP 外链的原文捕获规则。 */
    private static final Pattern URL = Pattern.compile(EXPLICIT_URL_PATTERN, Pattern.CASE_INSENSITIVE);
    /** 无法表达当前来源的固定原因。 */
    private static final String UNAVAILABLE_REASON = "来源当前不可用，不能用于新绑定";
    /** 模型可读取的公开媒体地址仅使用 HTTPS。 */
    private static final String PUBLIC_MEDIA_SCHEME = "https";
    /** 来源查询和账户、归属校验依赖。 */
    private final WorkEntityMapper works;
    /** 作品标签关联及用户自定义标签。 */
    private final WorkTagEntityMapper workTags;
    /** 当前用户自定义标签读取器。 */
    private final WfTagEntityMapper tags;
    /** 作品集查询器。 */
    private final PortfolioEntityMapper portfolios;
    /** 团队成员查询器。 */
    private final TeamMemberEntityMapper members;
    /** 用户公开资料查询器。 */
    private final UserEntityMapper users;
    /** 团队公开资料查询器。 */
    private final TeamEntityMapper teams;
    /** 现有团队身份门禁，创建前按团队本身检查。 */
    private final TeamPortfolioAccessService access;
    /** 当前字体目录提供固定版本句柄，不向模型发送本地路径。 */
    private final PortfolioFontSources fontSources;
    /** 超链接合法目标沿普通已发布个人策略和当前引用图查询。 */
    private final PortfolioHyperlinkGraphService hyperlinkGraph;
    /** 沿用公开联系资料的三态与损坏降级语义，不向模型发送原值。 */
    private final UserContactProfileService contactProfiles;
    /** 仅由服务端已授权作品的 COS 对象键生成模型可读媒体地址。 */
    private final CosService cos;

    /** 检查真实归属和当前维护权，返回已有高级作品集或新建空值。 */
    public PortfolioEntity requireOwner(ObjectNode input, Long requester) {
        Long owner = PortfolioAiInput.id(input, KEY_OWNER_ID, false);
        String type = PortfolioAiInput.text(input, KEY_OWNER_TYPE);
        if (PortfolioOwnerTypeDict.USER.getCode().equals(type)) {
            UserEntity user = users.selectById(owner);
            if (!Objects.equals(owner, requester) || user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
                throw unavailable();
            }
        } else { access.requireTeamRole(owner, requester, ROLES); }
        Long id = PortfolioAiInput.id(input, KEY_PORTFOLIO_ID, true);
        if (id == null) { return null; }
        PortfolioEntity portfolio = portfolios.selectById(id);
        if (portfolio == null || portfolio.getDeleted() != null && portfolio.getDeleted() != 0L
                || !type.equals(portfolio.getOwnerType()) || !owner.equals(portfolio.getOwnerId())
                || !PortfolioTemplateTypeDict.ADVANCED.getCode().equals(portfolio.getTemplateType())
                || !PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || !(PortfolioOwnerTypeDict.TEAM.getCode().equals(type)
                     ? TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1 : PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1)
                     .equals(portfolio.getSchemaVersion())) { throw unavailable(); }
        return portfolio;
    }

    /** 校验来源清单中的每项并冻结摘要；不展开成员作品集内部作品。 */
    public ObjectNode freeze(ObjectNode input, ObjectNode baseConfig) {
        ObjectNode facts = NODES.objectNode();
        ArrayNode sources = facts.putArray(KEY_SOURCES);
        for (JsonNode source : input.path(KEY_AI_CONTEXT).path(KEY_SOURCES)) {
            try { sources.add(resolve(input, source).put(KEY_CAN_BIND, true)); }
            catch (BusinessException | AiProtocolException exception) {
                // 失效来源仍保留编号；真实页面中的实际引用由完整普通校验器再次检查。
                sources.addObject().put(KEY_SOURCE_NO, source.path(KEY_SOURCE_NO).intValue())
                        .put(KEY_KIND, source.path(KEY_KIND).asText()).put(KEY_SOURCE_ID, source.path(KEY_SOURCE_ID).asText())
                        .put(KEY_CAN_BIND, false).put(KEY_UNAVAILABLE_REASON, UNAVAILABLE_REASON);
            }
        }
        facts.set(KEY_BASE_CONFIG, baseConfig.deepCopy());
        ArrayNode bindings = facts.putArray(KEY_BINDINGS);
        Long owner = PortfolioAiInput.id(input, KEY_OWNER_ID, false);
        ObjectNode profile = facts.putObject(KEY_PROFILE);
        if (PortfolioOwnerTypeDict.USER.getCode().equals(input.path(KEY_OWNER_TYPE).asText())) {
            UserEntity user = users.selectById(owner);
            profile.put(KEY_DISPLAY_NAME, safe(user.getNickname())).put(KEY_AVATAR_URL, safe(user.getAvatarUrl()))
                    .put(KEY_PROFESSION, safe(user.getProfession())).put(KEY_CITY, safe(user.getCity())).put(KEY_BIO, safe(user.getIntro()))
                    .put(KEY_WECHAT_QR_URL, safe(user.getWechatQrUrl()));
            freezeProfileContacts(user, bindings);
        } else {
            TeamEntity team = teams.selectById(owner);
            profile.put(KEY_TEAM_ID, owner).put(KEY_TEAM_NAME, safe(team.getName())).put(KEY_AVATAR_URL, safe(team.getAvatarUrl()))
                    .put(KEY_INTRO, safe(team.getIntro()));
        }
        boolean isTeam = PortfolioOwnerTypeDict.TEAM.getCode().equals(input.path(KEY_OWNER_TYPE).asText());
        addBinding(bindings, PROFILE_BASE_REF, isTeam ? COMPONENT_TEAM_PROFILE : COMPONENT_PROFILE, isTeam ? KEY_TEAM : KEY_PROFILE, profile, false, null);
        if (!isTeam) {
            freezeSourceCovers(sources, bindings);
            freezeProfileShareAvatar(profile, bindings);
        }
        if (!isTeam) { freezeHyperlinkTargets(input, owner, bindings); }
        Map<String, Object> catalog = fontSources.catalog();
        if (input.path(KEY_CLIENT_CAPABILITIES).path(KEY_PORTFOLIO_REMOTE_FONT).asInt() == 1 && Boolean.TRUE.equals(catalog.get(KEY_AVAILABLE))) {
            int index = 0;
            for (Object entry : (List<?>) catalog.get(KEY_FONTS)) {
                Map<?, ?> font = (Map<?, ?>) entry;
                ObjectNode value = NODES.objectNode().put(KEY_FONT_ID, String.valueOf(font.get(KEY_FONT_ID)));
                value.putObject(KEY_REFERENCE).put(KEY_FONT_VERSION, String.valueOf(font.get(KEY_FONT_VERSION)));
                addBinding(bindings, FONT_REF_PREFIX + (++index), RESOURCE_KIND_FONT, KEY_FONT_ID, value, false, null)
                        .put(KEY_LABEL, String.valueOf(font.get(KEY_DISPLAY_NAME)));
            }
        }
        for (JsonNode component : allComponents(baseConfig)) {
            freezeResources(component.path(KEY_CONFIG), component.path(KEY_COMPONENT_KEY).asText(), bindings, baseConfig);
        }
        explicitInputBindings(input.path(KEY_PROMPT).asText(), bindings);
        if (input.path(KEY_CLARIFICATION_ANSWER).path(KEY_ANSWER_TEXT).isTextual()) {
            explicitInputBindings(input.path(KEY_CLARIFICATION_ANSWER).path(KEY_ANSWER_TEXT).textValue(), bindings);
        }
        facts.put(KEY_SAFE_PROMPT, redact(input.path(KEY_PROMPT).textValue()));
        JsonNode answer = input.get(KEY_CLARIFICATION_ANSWER);
        if (answer != null && answer.isObject()) {
            ObjectNode safeAnswer = (ObjectNode) answer.deepCopy();
            safeAnswer.fields().forEachRemaining(e -> safeAnswer.put(e.getKey(), redact(e.getValue().asText())));
            facts.set(KEY_SAFE_CLARIFICATION_ANSWER, safeAnswer);
        }
        return facts;
    }

    /** 个人作品的原图或静态封面可绑定分享图片；团队仍沿自有作品集上传素材约束。 */
    private static void freezeSourceCovers(ArrayNode sources, ArrayNode bindings) {
        for (JsonNode source : sources) {
            if (!SOURCE_KIND_WORK.equals(source.path(KEY_KIND).asText()) || !source.path(KEY_CAN_BIND).asBoolean(false)) { continue; }
            String mediaType = source.path(KEY_MEDIA_TYPE).asText();
            JsonNode image;
            if (MediaTypeDict.IMAGE.getCode().equals(mediaType)) {
                image = source.hasNonNull(KEY_MEDIA_URL) ? source.path(KEY_MEDIA_URL) : source.path(KEY_COVER_URL);
            } else if (MediaTypeDict.VIDEO.getCode().equals(mediaType) || MediaTypeDict.ANIMATION.getCode().equals(mediaType)) {
                image = source.path(KEY_COVER_URL);
            } else { continue; }
            if (!image.isTextual() || image.asText().isBlank()) { continue; }
            int sourceNo = source.path(KEY_SOURCE_NO).intValue();
            addBinding(bindings, SOURCE_COVER_REF_PREFIX + sourceNo, RESOURCE_KIND_IMAGE, KEY_COVER_URL, image, false, null)
                    .put(KEY_SOURCE_NO, sourceNo).withArray(KEY_ALLOWED_SLOTS).add(KEY_AVATAR_URL);
        }
    }

    /** 本人公开头像只供分享头像使用，原值不发给模型，也不占用作品素材号。 */
    private static void freezeProfileShareAvatar(ObjectNode profile, ArrayNode bindings) {
        String avatarUrl = profile.path(KEY_AVATAR_URL).asText();
        if (isPublicMediaUrl(avatarUrl)) {
            addBinding(bindings, PROFILE_SHARE_AVATAR_REF, RESOURCE_KIND_IMAGE, KEY_AVATAR_URL,
                    NODES.textNode(avatarUrl), false, null).put(KEY_LABEL, PROFILE_SHARE_AVATAR_LABEL);
        }
    }

    /** 当前本人公开联系资料恢复为可新绑定句柄；原值仅留在冻结事实，模型投影剥离 value。 */
    private void freezeProfileContacts(UserEntity user, ArrayNode bindings) {
        UserContactProfileService.ContactProfile contact = contactProfiles.resolve(user);
        Map<String, Object> normalized = PortfolioContactInfoConfigSupport.normalize(Map.of(
                KEY_CONTACT_PHONE, contact.contactPhone(), KEY_CONTACT_WECHAT, contact.contactWechat()));
        String phone = (String) normalized.get(KEY_CONTACT_PHONE), wechat = (String) normalized.get(KEY_CONTACT_WECHAT);
        if (!phone.isBlank()) {
            addBinding(bindings, PROFILE_CONTACT_PHONE_REF, RESOURCE_KIND_CONTACT_PHONE,
                    KEY_CONTACT_PHONE, NODES.textNode(phone), false, null);
        }
        if (!wechat.isBlank()) {
            addBinding(bindings, PROFILE_CONTACT_WECHAT_REF, RESOURCE_KIND_CONTACT_WECHAT,
                    KEY_CONTACT_WECHAT, NODES.textNode(wechat), false, null);
        }
    }

    /** 原有隐藏值绑定只能在当前组件原位复用；模型不能复制到新组件。 */
    private static void freezeResources(JsonNode node, String componentKey, ArrayNode bindings, ObjectNode base) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String kind = switch (e.getKey()) {
                    case KEY_FONT_ID -> RESOURCE_KIND_FONT;
                    case KEY_CONTACT_PHONE -> RESOURCE_KIND_CONTACT_PHONE;
                    case KEY_CONTACT_WECHAT -> RESOURCE_KIND_CONTACT_WECHAT;
                    case KEY_QR_URL, KEY_WECHAT_QR_URL -> RESOURCE_KIND_QR_IMAGE;
                    case KEY_TARGET_PORTFOLIO_ID -> RESOURCE_KIND_PERSONAL_PORTFOLIO;
                    case KEY_EXTERNAL_CONTENT -> RESOURCE_KIND_EXTERNAL_LINK;
                    default -> null;
                };
                if (kind != null && !e.getValue().isNull() && !e.getValue().asText().isBlank()) {
                    JsonNode value = e.getValue();
                    if (RESOURCE_KIND_FONT.equals(kind)) {
                        ObjectNode fontValue = NODES.objectNode().put(KEY_FONT_ID, value.asText());
                        fontValue.set(KEY_REFERENCE, base.path(KEY_FONTS).path(value.asText())); value = fontValue;
                    }
                    ObjectNode resource = addBinding(bindings, EXISTING_REF_PREFIX + (bindings.size() + 1), kind, e.getKey(), value, true, componentKey);
                    for (String key : List.of(KEY_RUN_KEY, KEY_BLOCK_KEY, KEY_CELL_KEY)) {
                        if (node.has(key)) { resource.put(KEY_NODE_KEY, node.path(key).asText()); break; }
                    }
                }
                freezeResources(e.getValue(), componentKey, bindings, base);
            });
        } else if (node.isArray()) { node.forEach(n -> freezeResources(n, componentKey, bindings, base)); }
    }

    /** 绑定的原始值仅保存到冻结事实，模型投影剥离 value。 */
    private static ObjectNode addBinding(ArrayNode bindings, String ref, String kind, String slot,
                                          JsonNode value, boolean reuseOnly, String componentKey) {
        ObjectNode binding = bindings.addObject().put(KEY_REF, ref).put(KEY_KIND, kind).put(KEY_REUSE_ONLY, reuseOnly);
        binding.putArray(KEY_ALLOWED_SLOTS).add(slot); binding.set(KEY_VALUE, value.deepCopy());
        if (componentKey != null) { binding.put(KEY_COMPONENT_KEY, componentKey); }
        return binding;
    }

    /** 冻结原有资源包含所有菜单，投影仍只公开当前目标菜单。 */
    private static List<JsonNode> allComponents(ObjectNode config) {
        List<JsonNode> result = new ArrayList<>();
        config.path(KEY_COMPONENTS).forEach(result::add);
        for (JsonNode menu : config.path(KEY_BOTTOM_NAV).path(KEY_ITEMS)) { menu.path(KEY_COMPONENTS).forEach(result::add); }
        return result;
    }

    /** 仅复核输出确实绑定的素材号或来源图片句柄，未使用的失效来源不阻止合法候选。 */
    public void revalidate(ObjectNode request, ObjectNode output) {
        ObjectNode input = (ObjectNode) request.path(KEY_INPUT);
        Set<Integer> used = new LinkedHashSet<>();
        Set<String> usedRefs = new LinkedHashSet<>(); collectSourceNumbers(output, used, usedRefs);
        for (JsonNode binding : request.path(KEY_FACTS).path(KEY_BINDINGS)) {
            if (usedRefs.contains(binding.path(KEY_REF).asText()) && binding.path(KEY_SOURCE_NO).isIntegralNumber()) {
                used.add(binding.path(KEY_SOURCE_NO).intValue());
            }
        }
        for (JsonNode source : input.path(KEY_AI_CONTEXT).path(KEY_SOURCES)) {
            if (used.contains(source.path(KEY_SOURCE_NO).intValue())) { resolve(input, source); }
        }
    }

    /** 中间协议封闭字段中只读取实际来源号和资源句柄，不扫描用户说明中的数字或文字。 */
    private static void collectSourceNumbers(JsonNode node, Set<Integer> used, Set<String> usedRefs) {
        if (node.isObject()) {
            if (node.path(KEY_SOURCE_NO).isIntegralNumber()) { used.add(node.path(KEY_SOURCE_NO).intValue()); }
            for (JsonNode n : node.path(KEY_SOURCE_NOS)) { used.add(n.intValue()); }
            if (node.path(KEY_REF).isTextual()) { usedRefs.add(node.path(KEY_REF).textValue()); }
        }
        for (JsonNode child : node) { collectSourceNumbers(child, used, usedRefs); }
    }

    /** 解析来源 ID，真实成员身份由数据库反查。 */
    private ObjectNode resolve(ObjectNode input, JsonNode source) {
        String kind = PortfolioAiInput.text(source, KEY_KIND);
        Long id = PortfolioAiInput.id(source, KEY_SOURCE_ID, false);
        Long owner = PortfolioAiInput.id(input, KEY_OWNER_ID, false);
        boolean team = PortfolioOwnerTypeDict.TEAM.getCode().equals(input.path(KEY_OWNER_TYPE).asText());
        ObjectNode fact = NODES.objectNode().put(KEY_SOURCE_NO, source.path(KEY_SOURCE_NO).intValue())
                .put(KEY_KIND, kind).put(KEY_SOURCE_ID, id.toString());
        if (SOURCE_KIND_WORK.equals(kind)) {
            WorkEntity work = works.selectById(id);
            if (work == null || work.getDeleted() != null && work.getDeleted() != 0L
                    || !WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())
                    || !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())) { throw unavailable(); }
            UserEntity author = team ? requireMember(owner, work.getUserId(), true) : requireActiveUser(work.getUserId());
            if (!team && !Objects.equals(owner, work.getUserId())) { throw unavailable(); }
            if (team) { fact.put(KEY_MEMBER_NAME, safe(author.getNickname())); }
            fact.put(KEY_MEMBER_USER_ID, work.getUserId().toString()).put(KEY_MEDIA_TYPE, work.getMediaType())
                    .put(KEY_TITLE, safe(work.getTitle())).put(KEY_WIDTH, work.getWidth() == null ? 0 : work.getWidth())
                    .put(KEY_HEIGHT, work.getHeight() == null ? 0 : work.getHeight());
            freezeMediaUrl(fact, KEY_MEDIA_URL, work.getMediaObjectKey());
            freezeMediaUrl(fact, KEY_COVER_URL, work.getCoverObjectKey());
            ArrayNode summaryTags = fact.putArray(KEY_TAGS);
            List<WorkTagEntity> links = workTags.selectList(Wrappers.<WorkTagEntity>lambdaQuery()
                    .eq(WorkTagEntity::getWorkId, id).eq(WorkTagEntity::getUserId, work.getUserId()));
            if (!links.isEmpty()) {
                for (WfTagEntity tag : tags.selectBatchIds(links.stream().map(WorkTagEntity::getTagId).distinct().toList())) {
                    if (work.getUserId().equals(tag.getUserId()) && WfTagStatusDict.ACTIVE.getCode().equals(tag.getStatus())) { summaryTags.add(safe(tag.getName())); }
                }
            }
        } else if (SOURCE_KIND_MEMBER_PORTFOLIO.equals(kind) && team) {
            PortfolioEntity child = portfolios.selectById(id);
            if (child == null || child.getDeleted() != null && child.getDeleted() != 0L
                    || !PortfolioOwnerTypeDict.USER.getCode().equals(child.getOwnerType())
                    || !PortfolioStatusDict.ACTIVE.getCode().equals(child.getStatus())
                    || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(child.getPublicationStatus())
                    || child.getShareCode() == null || child.getShareCode().isBlank()
                    || !PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(child.getSchemaVersion())
                    || child.getPublishedRevision() == null || child.getPublishedRevision() <= 0
                    || child.getPublishedConfigJson() == null
                    || !Set.of(PortfolioTemplateTypeDict.STANDARD.getCode(), PortfolioTemplateTypeDict.ADVANCED.getCode())
                            .contains(child.getTemplateType())) { throw unavailable(); }
            UserEntity author = requireMember(owner, child.getOwnerId(), false);
            fact.put(KEY_MEMBER_NAME, safe(author.getNickname()));
            ObjectNode config = StrictAiJson.parseObject(child.getPublishedConfigJson(), 2 * 1024 * 1024, 24);
            fact.put(KEY_MEMBER_USER_ID, child.getOwnerId().toString()).put(KEY_TITLE, config.path(KEY_SHARE).path(KEY_TITLE).asText(""));
            fact.putArray(KEY_TAGS);
        } else { throw unavailable(); }
        return fact;
    }

    /** 只冻结已授权数据库对象键对应的 HTTPS 媒体地址，缺失公开域名时不发送相对对象键。 */
    private void freezeMediaUrl(ObjectNode fact, String field, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) { return; }
        String url = cos.publicUrl(objectKey);
        if (isPublicMediaUrl(url)) { fact.put(field, url); }
    }

    /** 只允许无凭据、查询签名或片段的 HTTPS 公开地址参与媒体绑定。 */
    private static boolean isPublicMediaUrl(String url) {
        if (url == null || url.isBlank()) { return false; }
        try {
            URI uri = new URI(url);
            return PUBLIC_MEDIA_SCHEME.equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (URISyntaxException ignored) { return false; }
    }

    /** 只提供当前本人普通已发布且可选择的内部目标，真实ID以隐藏句柄冻结。 */
    private void freezeHyperlinkTargets(ObjectNode input, Long owner, ArrayNode bindings) {
        PortfolioHyperlinkTargetResponse response = hyperlinkGraph.listTargets(owner,
                PortfolioAiInput.id(input, KEY_PORTFOLIO_ID, true), null, PortfolioTemplateTypeDict.ADVANCED.getCode());
        int sequence = 0;
        for (PortfolioHyperlinkTargetResponse.TargetItem target : response.getPortfolios()) {
            if (target.isSelectable()) {
                addBinding(bindings, TARGET_PORTFOLIO_REF_PREFIX + (++sequence), RESOURCE_KIND_PERSONAL_PORTFOLIO,
                        KEY_TARGET_PORTFOLIO_ID, NODES.numberNode(target.getPortfolioId()), false, null)
                        .put(KEY_LABEL, safe(target.getTitle()));
            }
        }
    }

    /** 作品与成员作品集分别检查 allowWorks 与 allowPortfolio，不相互替代。 */
    private UserEntity requireMember(Long teamId, Long memberId, boolean work) {
        UserEntity user = requireActiveUser(memberId);
        List<TeamMemberEntity> rows = members.selectList(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, teamId).eq(TeamMemberEntity::getUserId, memberId)
                .eq(TeamMemberEntity::getJoinStatus, JoinStatusDict.JOINED.getCode()));
        if (rows.isEmpty() || !Integer.valueOf(1).equals(work ? rows.getFirst().getAllowWorks() : rows.getFirst().getAllowPortfolio())) {
            throw unavailable();
        }
        return user;
    }

    /** 来源所属用户当前有效，冻结快照不能继续提供注销用户的资源。 */
    private UserEntity requireActiveUser(Long id) {
        UserEntity user = users.selectById(id);
        if (user == null || user.getDeleted() != null && user.getDeleted() != 0L || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) { throw unavailable(); }
        return user;
    }

    /** 明确原文只转换为隐藏句柄，精确值不交给模型；不从描述猜测二维码或站内身份。 */
    private static void explicitInputBindings(String text, ArrayNode bindings) {
        collectInputBindings(PHONE.matcher(text), bindings, RESOURCE_KIND_CONTACT_PHONE, KEY_CONTACT_PHONE, false);
        collectInputBindings(WECHAT.matcher(text), bindings, RESOURCE_KIND_CONTACT_WECHAT, KEY_CONTACT_WECHAT, true);
        Matcher links = URL.matcher(text);
        while (links.find()) {
            String exact = links.group();
            try {
                URI uri = new URI(exact);
                if (uri.getHost() == null || uri.getUserInfo() != null || exact.codePointCount(0, exact.length()) > 2048) { continue; }
                addInputBinding(bindings, RESOURCE_KIND_EXTERNAL_LINK, KEY_EXTERNAL_CONTENT, exact);
            } catch (URISyntaxException ignored) { /* 无损校验失败时保留手动参数面板填写路径。 */ }
        }
    }

    /** 按严格格式抽取已确认的联系方式，标签提示以普通参数限制为准。 */
    private static void collectInputBindings(Matcher matches, ArrayNode bindings, String kind, String slot, boolean group) {
        while (matches.find()) { addInputBinding(bindings, kind, slot, group ? matches.group(1) : matches.group()); }
    }

    /** 重复精确值只产生一个句柄，编号在冻结任务中稳定。 */
    private static void addInputBinding(ArrayNode bindings, String kind, String slot, String value) {
        for (JsonNode existing : bindings) {
            if (!existing.path(KEY_REUSE_ONLY).asBoolean() && kind.equals(existing.path(KEY_KIND).asText())
                    && value.equals(existing.path(KEY_VALUE).asText())) { return; }
        }
        addBinding(bindings, INPUT_REF_PREFIX + (bindings.size() + 1), kind, slot, NODES.textNode(value), false, null);
    }

    /** 发送侧去掉用户原文中的直接联系方式和资源地址，引导手动参数填写。 */
    private static String redact(String text) {
        return safe(text).replaceAll(REDACT_URL_PATTERN, REDACTED_LINK)
                .replaceAll(PHONE_NUMBER_PATTERN, REDACTED_PHONE)
                .replaceAll(REDACT_WECHAT_PATTERN, REDACTED_WECHAT);
    }

    /** 公开摘要空值按空串处理，禁止输出密文字段。 */
    private static String safe(String value) { return value == null ? "" : value; }
    /** 统一使用来源边界错误。 */
    private static BusinessException unavailable() { return new BusinessException(PortfolioAiMessage.SOURCE_UNAVAILABLE); }
}
