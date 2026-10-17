package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceService;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dto.*;
import com.jxc.wefolio.dto.teamportfolio.*;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.service.MinePortfolioService;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.miniappcode.PortfolioMiniappCodeApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;
import java.util.Set;

/** 高级维护入口：依据数据库真实归属分派普通算法，AI 上下文仅在维护详情返回。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdvancedPortfolioApplicationService {
    /** 高级入口固定模板，不能由客户端替换。 */
    private static final String ADVANCED = PortfolioTemplateTypeDict.ADVANCED.getCode();
    /** 团队维护角色。 */
    private static final Set<String> ROLES = Set.of(TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode());
    /** 高级保存受理字段。 */
    private static final Set<String> SAVE_KEYS = Set.of("config", "clientRevision", "idempotencyKey", "clientCapabilities", "aiContext", "aiPrompt", "aiReferenceAttachments", "aiReferenceNextNo");
    /** 高级原始请求资源限额，配置仍沿完整普通手动编辑能力。 */
    private static final int REQUEST_MAX_BYTES = 2 * 1024 * 1024;
    /** 附件维护字段。 */
    private static final String REFERENCES = "aiReferenceAttachments";
    /** 附件编号高水位。 */
    private static final String REFERENCE_NEXT = "aiReferenceNextNo";
    /** 附件可信事实和生命周期。 */
    private final PortfolioAiReferenceService references;
    /** 个人普通业务算法。 */
    private final MinePortfolioService personal;
    /** 团队普通业务算法。 */
    private final MineTeamPortfolioService team;
    /** 作品集真实归属查询。 */
    private final PortfolioEntityMapper portfolios;
    /** 团队维护与成员可见性。 */
    private final TeamPortfolioAccessService access;
    /** 团队上传算法。 */
    private final TeamPortfolioAssetService assets;
    /** 高级来源维护查询。 */
    private final AdvancedPortfolioSourceService sources;
    /** 发布快照的小程序码。 */
    private final PortfolioMiniappCodeApplicationService miniappCodes;

    /** 创建可见高级基础草稿，不扣发布积分，不接受 AI 配置直接写入。 */
    public JSONObject create(String raw) {
        JSONObject request = parse(raw, Set.of("ownerType", "ownerId"));
        String ownerType = requireText(request, "ownerType");
        Long ownerId = positiveId(request.get("ownerId"));
        requireOwner(ownerType, ownerId);
        Object detail = PortfolioOwnerTypeDict.USER.getCode().equals(ownerType)
                ? personal.createStandardPersonal(new MinePortfolioCreateRequest(), ADVANCED)
                : team.createStandard(ownerId, new TeamPortfolioCreateRequest(), actor(), ADVANCED);
        return maintenanceDetail(detail, requireMaintainerPortfolio(idOf(detail)));
    }

    /** 高级维护详情是恢复上下文的唯一读取入口。 */
    public JSONObject getDetail(Long id) {
        PortfolioEntity portfolio = requireMaintainerPortfolio(id);
        Object detail = isTeam(portfolio) ? team.getDetail(id, actor(), ADVANCED) : personal.getDetail(id, ADVANCED);
        return maintenanceDetail(detail, portfolio);
    }

    /** 原始配置在宽松 DTO 转换前检查，高级元数据交给普通最终事务保存。 */
    public JSONObject saveDraft(Long id, String raw) {
        PortfolioEntity portfolio = requireMaintainerPortfolio(id);
        JSONObject request = parse(raw, SAVE_KEYS);
        validateConfig(request, isTeam(portfolio));
        boolean contextPresent = request.containsKey("aiContext");
        boolean promptPresent = request.containsKey("aiPrompt");
        if (contextPresent && !(request.get("aiContext") instanceof JSONObject)) { throw invalid(); }
        JSONObject context = (JSONObject) request.get("aiContext");
        String prompt = promptPresent ? requireString(request.get("aiPrompt")) : null;
        if (prompt != null && prompt.codePointCount(0, prompt.length()) > 2000) { throw invalid(); }
        if (contextPresent) { AdvancedPortfolioSaveMetadata.validateContext(context, portfolio.getAiContextJson()); }
        boolean referencesPresent = request.containsKey(REFERENCES);
        if (!referencesPresent && request.containsKey(REFERENCE_NEXT)) { throw invalid(); }
        ArrayNode referenceItems = null;
        Integer referenceNext = null;
        JSONObject fingerprint = new JSONObject(request);
        if (referencesPresent) {
            JsonNode rawItems = StrictAiJson.parseObject(request.toJSONString(), REQUEST_MAX_BYTES, 20).get(REFERENCES);
            if (!(rawItems instanceof ArrayNode items)) { throw invalid(); }
            referenceItems = items;
            fingerprint.put(REFERENCES, JSON.parseArray(PortfolioAiReferenceService.normalizeBusinessProjection(items).toString()));
            if (request.containsKey(REFERENCE_NEXT)) {
                if (!(request.get(REFERENCE_NEXT) instanceof Integer next) || next < 1) { throw invalid(); }
                referenceNext = next;
            }
        }
        AdvancedPortfolioSaveMetadata metadata = new AdvancedPortfolioSaveMetadata(context, contextPresent,
                prompt, promptPresent, JSON.toJSONString(fingerprint, JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue),
                referenceItems, referencesPresent, referenceNext);
        Object detail = isTeam(portfolio)
                ? team.saveDraft(id, request.toJavaObject(TeamPortfolioDraftSaveRequest.class), actor(), ADVANCED, metadata)
                : personal.saveDraft(id, request.toJavaObject(MinePortfolioDraftSaveRequest.class), ADVANCED, metadata);
        PortfolioEntity saved = requireMaintainerPortfolio(id);
        if (referencesPresent) {
            references.cleanupRemoved(portfolio.getOwnerType(), portfolio.getOwnerId(),
                    portfolio.getAiReferenceAttachmentsJson(), saved.getAiReferenceAttachmentsJson());
        }
        return maintenanceDetail(detail, saved, true);
    }

    /** 发布独立检查，费用从现有高级发布积分规则读取。 */
    public Object publish(Long id, String raw) {
        PortfolioEntity portfolio = requireMaintainerPortfolio(id);
        JSONObject request = parse(raw, Set.of("draftRevision", "idempotencyKey"));
        if (request.containsKey("draftRevision") && !(request.get("draftRevision") instanceof Integer)) { throw invalid(); }
        if (request.containsKey("idempotencyKey")) { requireString(request.get("idempotencyKey")); }
        return isTeam(portfolio) ? team.publish(id, request.toJavaObject(TeamPortfolioPublishRequest.class), actor(), ADVANCED)
                : personal.publish(id, request.toJavaObject(MinePortfolioPublishRequest.class), ADVANCED);
    }

    /** 高级草稿/发布版预览允许有效团队成员，响应不包含维护 AI 数据。 */
    public Object preview(Long id, boolean published) {
        PortfolioEntity portfolio = requireVisiblePortfolio(id);
        if (isTeam(portfolio)) { return published ? team.previewPublished(id, actor(), ADVANCED) : team.preview(id, actor(), ADVANCED); }
        return published ? personal.previewPublished(id, ADVANCED) : personal.preview(id, ADVANCED);
    }

    /** 删除只允许当前维护者，沿普通资产及引用清理顺序。 */
    public void delete(Long id) {
        PortfolioEntity portfolio = requireMaintainerPortfolio(id);
        if (portfolio.getAiReferenceAttachmentsJson() == null) { deleteOwned(id, portfolio); return; }
        references.withOwnerLock(portfolio.getOwnerType(), portfolio.getOwnerId(), () -> {
            deleteOwned(id, portfolio);
            return null;
        });
        references.cleanupRemoved(portfolio.getOwnerType(), portfolio.getOwnerId(), portfolio.getAiReferenceAttachmentsJson(), null);
    }

    /** 删除继续委派普通用例，所有原引用守卫和事务语义保持。 */
    private void deleteOwned(Long id, PortfolioEntity portfolio) {
        if (isTeam(portfolio)) { team.deletePortfolio(id, actor(), ADVANCED); }
        else { personal.deletePortfolio(id, ADVANCED); }
    }

    /** 独立高级素材票据，COS 始终按真实归属目录生成。 */
    public Object uploadTicket(Long id, String raw) {
        PortfolioEntity portfolio = requireMaintainerPortfolio(id);
        JSONObject request = parse(raw, Set.of("clientId", "assetType", "mimeType", "fileSize"));
        return isTeam(portfolio) ? assets.createUploadTicket(id, request.toJavaObject(TeamPortfolioAssetUploadTicketRequest.class), actor(), ADVANCED)
                : personal.createAssetUploadTicket(id, request.toJavaObject(MinePortfolioAssetUploadTicketRequest.class), ADVANCED);
    }

    /** 准备编辑字体，不保存配置或元数据。 */
    public PortfolioFontManifestDto prepareFonts(Long id, String raw) {
        PortfolioEntity portfolio = requireMaintainerPortfolio(id);
        JSONObject request = parse(raw, SAVE_KEYS);
        validateConfig(request, isTeam(portfolio));
        return isTeam(portfolio) ? team.prepareFonts(id, request.toJavaObject(TeamPortfolioDraftSaveRequest.class), actor(), ADVANCED)
                : personal.prepareFonts(id, request.toJavaObject(MinePortfolioDraftSaveRequest.class), ADVANCED);
    }

    /** 成员分享已发布版本，不读取维护上下文。 */
    public void shareRecord(Long id, String raw) {
        PortfolioEntity portfolio = requireVisiblePortfolio(id);
        JSONObject request = parse(raw, Set.of("shareChannel", "shareScene"));
        if (isTeam(portfolio)) { team.createShareRecord(id, request.toJavaObject(TeamPortfolioShareRecordRequest.class), actor(), ADVANCED); }
        else { personal.createShareRecord(id, request.toJavaObject(MinePortfolioShareRecordRequest.class), ADVANCED); }
    }

    /** 生成高级独立入口的小程序码，权限与快照由码服务前后复核。 */
    public PortfolioMiniappCodeResponse miniappCode(Long id) {
        PortfolioEntity portfolio = requireVisiblePortfolio(id);
        return miniappCodes.generateAdvanced(id, actor(), isTeam(portfolio));
    }

    /** 预览档期选项不写访问记录。 */
    public Object scheduleOptions(Long id, String month, String componentKey, String scope) {
        PortfolioEntity portfolio = requireVisiblePortfolio(id);
        return isTeam(portfolio) ? team.scheduleOptions(id, componentKey, scope, actor(), ADVANCED)
                : personal.queryPreviewScheduleOptions(id, month, componentKey, scope, ADVANCED);
    }

    /** 预览查档复用当前归属的只读算法。 */
    public Object scheduleQuery(Long id, String raw, String scope) {
        PortfolioEntity portfolio = requireVisiblePortfolio(id);
        JSONObject request = parse(raw, isTeam(portfolio) ? Set.of("componentKey", "queriedDate", "idempotencyKey")
                : Set.of("visitorKey", "componentKey", "queriedDate", "slotDefinitionId", "idempotencyKey"));
        return isTeam(portfolio) ? team.scheduleQueryPreview(id, request.toJavaObject(TeamPortfolioScheduleQueryRequest.class), scope, actor(), ADVANCED)
                : personal.submitPreviewScheduleQuery(id, request.toJavaObject(PortfolioScheduleQueryRequest.class), scope, ADVANCED);
    }

    /** 目标范围仍只包含本人已发布普通个人作品集。 */
    public PortfolioHyperlinkTargetResponse hyperlinkTargets(Long sourceId, Long selectedId) {
        if (sourceId != null && isTeam(requireMaintainerPortfolio(sourceId))) { throw invalid(); }
        return personal.listHyperlinkTargets(sourceId, selectedId, ADVANCED);
    }

    /** 创建和新任务共用的真实归属校验。 */
    public void requireOwner(String ownerType, Long ownerId) {
        if (PortfolioOwnerTypeDict.USER.getCode().equals(ownerType)) {
            if (!Objects.equals(ownerId, actor())) { throw new BusinessException(PortfolioMessage.PORTFOLIO_NOT_FOUND_MESSAGE); }
        } else if (PortfolioOwnerTypeDict.TEAM.getCode().equals(ownerType)) { access.requireTeamRole(ownerId, actor(), ROLES); }
        else { throw invalid(); }
    }

    /** 返回有当前维护权限的高级实体，供任务及来源复用。 */
    public PortfolioEntity requireMaintainerPortfolio(Long id) {
        PortfolioEntity portfolio = portfolios.selectById(id);
        validateAdvancedEntity(portfolio);
        return isTeam(portfolio) ? access.requireMaintainablePortfolio(id, actor(), ADVANCED).portfolio()
                : personal.requireOwnedPersonal(id, ADVANCED);
    }

    /** 返回成员可见的高级实体；该接口不能用来读取维护上下文。 */
    public PortfolioEntity requireVisiblePortfolio(Long id) {
        PortfolioEntity portfolio = portfolios.selectById(id);
        validateAdvancedEntity(portfolio);
        return isTeam(portfolio) ? access.requireVisiblePortfolio(id, actor(), ADVANCED).portfolio()
                : personal.requireOwnedPersonal(id, ADVANCED);
    }

    /** 对团队混合来源按团队自身维护权查询。 */
    public JSONObject listSources(String ownerType, Long ownerId, String kind, Long memberId, String keyword, int page, int pageSize) {
        if (!PortfolioOwnerTypeDict.TEAM.getCode().equals(ownerType)) { throw invalid(); }
        requireOwner(ownerType, ownerId);
        return sources.listSources(ownerId, kind, memberId, keyword, page, pageSize);
    }

    /** 先用 Jackson 原始树检查类型，再用原普通 DTO 进入存储算法。 */
    private void validateConfig(JSONObject request, boolean teamConfig) {
        try {
            if (!(request.get("config") instanceof JSONObject config)) { throw invalid(); }
            AdvancedPortfolioConfigGuard.validate(StrictAiJson.parseObject(config.toJSONString(), REQUEST_MAX_BYTES, 20), teamConfig);
            if (request.containsKey("clientRevision") && !(request.get("clientRevision") instanceof Integer)) { throw invalid(); }
            if (request.containsKey("idempotencyKey")) { requireString(request.get("idempotencyKey")); }
            Object capabilities = request.get("clientCapabilities");
            if (capabilities != null) {
                if (!(capabilities instanceof JSONObject object) || !Set.of("portfolioRemoteFont").containsAll(object.keySet())
                        || object.values().stream().anyMatch(value -> !(value instanceof Integer))) { throw invalid(); }
            }
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    /** 独立解析入口拒绝重复键、尾随数据、过深容器与额外字段。 */
    private JSONObject parse(String raw, Set<String> allowed) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > REQUEST_MAX_BYTES) { throw invalid(); }
        try {
            ObjectNode node = StrictAiJson.parseObject(raw, REQUEST_MAX_BYTES, 20);
            JSONObject object = JSON.parseObject(node.toString());
            if (object == null || !allowed.containsAll(object.keySet())) { throw invalid(); }
            return object;
        } catch (Exception exception) { throw invalid(); }
    }

    /** 维护详情追加当前主表上下文，公开 DTO 始终不改动。 */
    private JSONObject maintenanceDetail(Object detail, PortfolioEntity portfolio) {
        return maintenanceDetail(detail, portfolio, false);
    }

    /** 保存事务成功后允许延后附件展示刷新，客户端保留原清单；详情读取仍完整校验。 */
    private JSONObject maintenanceDetail(Object detail, PortfolioEntity portfolio, boolean savedDraft) {
        JSONObject result = JSON.parseObject(JSON.toJSONString(detail));
        result.put("aiContext", portfolio.getAiContextJson() == null ? null : JSON.parseObject(portfolio.getAiContextJson()));
        result.put("aiPrompt", portfolio.getAiPrompt());
        if (portfolio.getAiReferenceAttachmentsJson() == null) {
            result.put(REFERENCES, List.of());
            result.put(REFERENCE_NEXT, 1);
        } else {
            try {
                result.putAll(references.maintenance(portfolio.getAiReferenceAttachmentsJson(), portfolio.getOwnerType(), portfolio.getOwnerId()));
            } catch (BusinessException exception) {
                if (!savedDraft) { throw exception; }
                log.warn("高级草稿已保存，附件展示刷新延后: portfolioId={}, errorType={}", portfolio.getId(), exception.getClass().getSimpleName());
            }
        }
        return result;
    }
    /** 从普通详情获取创建后的真实 ID。 */
    private Long idOf(Object detail) { return JSON.parseObject(JSON.toJSONString(detail)).getLong("portfolioId"); }
    /** 当前请求维护者。 */
    private long actor() { return AuthContextHolder.requireUserId(); }
    /** 根据真实实体判断团队归属。 */
    private boolean isTeam(PortfolioEntity portfolio) { return PortfolioOwnerTypeDict.TEAM.getCode().equals(portfolio.getOwnerType()); }
    /** 高级入口固定当前展示模型，拒绝逻辑删除、停用或不支持的个人配置。 */
    private void validateAdvancedEntity(PortfolioEntity portfolio) {
        if (portfolio == null || !ADVANCED.equals(portfolio.getTemplateType())
                || !Objects.equals(portfolio.getDeleted(), 0L)
                || !PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || !isTeam(portfolio) && (!PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                    || !PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(portfolio.getSchemaVersion()))) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_NOT_FOUND_MESSAGE);
        }
    }
    /** 新入口 ID 允许字符串或真实整数，拒绝浮点及非正数。 */
    private Long positiveId(Object raw) {
        if (raw instanceof String value && value.matches("[1-9][0-9]{0,18}")) {
            try { return Long.valueOf(value); }
            catch (NumberFormatException exception) { throw invalid(); }
        }
        if (raw instanceof Integer || raw instanceof Long) { long value = ((Number) raw).longValue(); if (value > 0) { return value; } }
        throw invalid();
    }
    /** 原始字符串不做隐式转换。 */
    private String requireString(Object value) { if (!(value instanceof String text)) { throw invalid(); } return text; }
    /** 必填文本检查。 */
    private String requireText(JSONObject request, String key) { String text = requireString(request.get(key)); if (text.isBlank()) { throw invalid(); } return text; }
    /** 新入口固定参数错误。 */
    private BusinessException invalid() { return new BusinessException(AdvancedPortfolioMessage.INVALID_REQUEST); }
}
