package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 团队作品集素材票据和保守清理基础服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamPortfolioAssetService {

    /** 作品集素材目录。 */
    private static final String PORTFOLIO_ASSET_FOLDER = "protfolio";

    /** 封面素材类型。 */
    private static final String ASSET_TYPE_COVER = "COVER";

    /** 团队展示图素材类型。 */
    private static final String ASSET_TYPE_TEAM_IMAGE = "TEAM_IMAGE";

    /** 二维码联系素材类型。 */
    private static final String ASSET_TYPE_QR_CONTACT = "QR_CONTACT";

    /** JPEG MIME 类型。 */
    private static final String MIME_IMAGE_JPEG = "image/jpeg";

    /** JPG MIME 别名。 */
    private static final String MIME_IMAGE_JPG = "image/jpg";

    /** PNG MIME 类型。 */
    private static final String MIME_IMAGE_PNG = "image/png";

    /** JPEG 文件扩展名。 */
    private static final String EXTENSION_JPG = "jpg";

    /** PNG 文件扩展名。 */
    private static final String EXTENSION_PNG = "png";

    /** 图片素材大小上限，与个人作品集一致。 */
    private static final long IMAGE_MAX_BYTES = 300L * 1024L;

    /** 上传票据有效分钟数。 */
    private static final int TICKET_EXPIRE_MINUTES = 15;

    /** 素材类型不支持提示。 */
    private static final String ASSET_TYPE_UNSUPPORTED_MESSAGE = "团队作品集素材类型不支持";

    /** 素材文件不能为空提示。 */
    private static final String ASSET_REQUIRED_MESSAGE = "请选择团队作品集图片";

    /** 素材大小超限提示。 */
    private static final String ASSET_SIZE_LIMIT_MESSAGE = "团队作品集图片不能超过300KB";

    /** 素材格式不支持提示。 */
    private static final String ASSET_FORMAT_UNSUPPORTED_MESSAGE = "团队作品集图片仅支持 JPG 或 PNG";

    /** 团队拥有者存储信息异常提示。 */
    private static final String TEAM_OWNER_STORAGE_INVALID_MESSAGE = "团队素材存储信息不可用";

    /** 已上传图片不可用统一提示，不透传 COS 底层异常。 */
    private static final String UPLOADED_IMAGE_INVALID_MESSAGE = "团队作品集图片未完成上传或不可用";

    /** UUID 文件名表达式。 */
    private static final String UUID_FILE_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png)";

    /** 团队作品集访问控制服务。 */
    private final TeamPortfolioAccessService accessService;

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

    /** 用户数据访问器。 */
    private final UserEntityMapper userEntityMapper;

    /** COS 基础服务。 */
    private final CosService cosService;

    /**
     * 创建团队作品集图片素材直传票据。
     *
     * @param portfolioId 作品集 ID
     * @param request 素材请求
     * @param userId 当前用户 ID
     * @return 直传票据
     */
    public TeamPortfolioAssetUploadTicketResponse createUploadTicket(
            long portfolioId,
            TeamPortfolioAssetUploadTicketRequest request,
            long userId
    ) {
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                accessService.requireMaintainablePortfolio(portfolioId, userId);
        String assetType = normalizeAssetType(request);
        String contentType = normalizeContentType(request);
        TeamEntity team = teamEntityMapper.selectById(access.team().getId());
        UserEntity owner = team == null || team.getOwnerUserId() == null
                ? null : userEntityMapper.selectById(team.getOwnerUserId());
        if (owner == null || owner.getUniqueCode() == null || owner.getUniqueCode().isBlank()) {
            throw new BusinessException(TEAM_OWNER_STORAGE_INVALID_MESSAGE);
        }
        String objectKey = buildObjectKey(owner.getUniqueCode(), team.getId(), portfolioId, contentType);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TICKET_EXPIRE_MINUTES);
        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                objectKey, contentType, IMAGE_MAX_BYTES, expiresAt);
        return buildResponse(request, assetType, ticket);
    }

    /**
     * 校验图片地址属于当前团队作品集且对应 COS 对象已完整上传。
     *
     * @param teamId 团队 ID
     * @param portfolioId 作品集 ID
     * @param url 待校验图片公开地址
     */
    public void validateUploadedImageUrl(long teamId, long portfolioId, String url) {
        String ownerUniqueCode = requireOwnerUniqueCode(teamId);
        String objectKey = resolveExactOwnedObjectKey(ownerUniqueCode, teamId, portfolioId, url, false);
        if (objectKey == null) {
            throw new BusinessException(UPLOADED_IMAGE_INVALID_MESSAGE);
        }
        try {
            CosService.ObjectHead head = cosService.headObject(objectKey);
            if (!isValidUploadedImageHead(head)) {
                throw new BusinessException(UPLOADED_IMAGE_INVALID_MESSAGE);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(UPLOADED_IMAGE_INVALID_MESSAGE);
        }
    }

    /**
     * 在事务提交后保守清理当前团队作品集配置中的自有素材。
     *
     * @param teamId 团队 ID
     * @param portfolioId 作品集 ID
     * @param draftConfigJson 草稿配置 JSON
     * @param publishedConfigJson 正式配置 JSON
     */
    public void deletePortfolioAssetsAfterCommit(
            long teamId,
            long portfolioId,
            String draftConfigJson,
            String publishedConfigJson
    ) {
        String ownerUniqueCode = resolveOwnerUniqueCodeForCleanup(teamId);
        if (ownerUniqueCode == null) {
            return;
        }
        Set<String> objectKeys = new LinkedHashSet<>();
        collectOwnedObjectKeys(draftConfigJson, ownerUniqueCode, teamId, portfolioId, objectKeys);
        collectOwnedObjectKeys(publishedConfigJson, ownerUniqueCode, teamId, portfolioId, objectKeys);
        if (objectKeys.isEmpty()) {
            return;
        }
        Runnable deleteTask = () -> objectKeys.forEach(objectKey -> deleteQuietly(portfolioId, objectKey));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTask.run();
                }
            });
            return;
        }
        deleteTask.run();
    }

    /**
     * 在事务提交后清理已被新正式配置替换的自有素材。
     *
     * @param teamId 团队 ID
     * @param portfolioId 作品集 ID
     * @param oldPublishedConfigJson 旧正式配置
     * @param newPublishedConfigJson 新正式配置
     */
    public void deleteReplacedPublishedAssetsAfterCommit(
            long teamId,
            long portfolioId,
            String oldPublishedConfigJson,
            String newPublishedConfigJson
    ) {
        String ownerUniqueCode = resolveOwnerUniqueCodeForCleanup(teamId);
        if (ownerUniqueCode == null) {
            return;
        }
        Set<String> oldObjectKeys = new LinkedHashSet<>();
        Set<String> newObjectKeys = new LinkedHashSet<>();
        collectOwnedObjectKeys(oldPublishedConfigJson, ownerUniqueCode, teamId, portfolioId, oldObjectKeys);
        collectOwnedObjectKeys(newPublishedConfigJson, ownerUniqueCode, teamId, portfolioId, newObjectKeys);
        oldObjectKeys.removeAll(newObjectKeys);
        if (oldObjectKeys.isEmpty()) {
            return;
        }
        Runnable deleteTask = () -> oldObjectKeys.forEach(objectKey -> deleteQuietly(portfolioId, objectKey));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTask.run();
                }
            });
            return;
        }
        deleteTask.run();
    }

    /**
     * 规范化素材类型。
     */
    private String normalizeAssetType(TeamPortfolioAssetUploadTicketRequest request) {
        String assetType = request == null || request.getAssetType() == null
                ? "" : request.getAssetType().strip().toUpperCase(Locale.ROOT);
        if (ASSET_TYPE_COVER.equals(assetType)
                || ASSET_TYPE_TEAM_IMAGE.equals(assetType)
                || ASSET_TYPE_QR_CONTACT.equals(assetType)) {
            return assetType;
        }
        throw new BusinessException(ASSET_TYPE_UNSUPPORTED_MESSAGE);
    }

    /**
     * 校验图片大小并规范化 MIME 类型。
     */
    private String normalizeContentType(TeamPortfolioAssetUploadTicketRequest request) {
        if (request == null || request.getFileSize() == null || request.getFileSize() <= 0L) {
            throw new BusinessException(ASSET_REQUIRED_MESSAGE);
        }
        if (request.getFileSize() > IMAGE_MAX_BYTES) {
            throw new BusinessException(ASSET_SIZE_LIMIT_MESSAGE);
        }
        String mimeType = request.getMimeType() == null
                ? "" : request.getMimeType().strip().toLowerCase(Locale.ROOT);
        if (MIME_IMAGE_JPEG.equals(mimeType) || MIME_IMAGE_JPG.equals(mimeType)) {
            return MIME_IMAGE_JPEG;
        }
        if (MIME_IMAGE_PNG.equals(mimeType)) {
            return MIME_IMAGE_PNG;
        }
        throw new BusinessException(ASSET_FORMAT_UNSUPPORTED_MESSAGE);
    }

    /**
     * 按团队拥有者唯一码构建对象键。
     */
    private String buildObjectKey(
            String ownerUniqueCode,
            long teamId,
            long portfolioId,
            String contentType
    ) {
        String extension = MIME_IMAGE_PNG.equals(contentType) ? EXTENSION_PNG : EXTENSION_JPG;
        return ownerUniqueCode + "/" + PORTFOLIO_ASSET_FOLDER
                + "/team-" + teamId
                + "/portfolio-" + portfolioId
                + "/" + UUID.randomUUID() + "." + extension;
    }

    /**
     * 组装票据响应。
     */
    private TeamPortfolioAssetUploadTicketResponse buildResponse(
            TeamPortfolioAssetUploadTicketRequest request,
            String assetType,
            CosService.PostUploadTicket ticket
    ) {
        TeamPortfolioAssetUploadTicketResponse response = new TeamPortfolioAssetUploadTicketResponse();
        response.setClientId(request.getClientId());
        response.setAssetType(assetType);
        response.setObjectKey(ticket.objectKey());
        response.setPublicUrl(cosService.publicUrl(ticket.objectKey()));
        response.setUploadUrl(ticket.uploadUrl());
        response.setContentType(ticket.contentType());
        response.setFormData(ticket.formData());
        response.setExpiresAt(ticket.expiresAt());
        response.setMaxBytes(ticket.maxBytes());
        return response;
    }

    /**
     * 使用结构化 JSON 遍历收集严格属于目标作品集前缀的对象键。
     */
    private void collectOwnedObjectKeys(
            String configJson,
            String ownerUniqueCode,
            long teamId,
            long portfolioId,
            Set<String> objectKeys
    ) {
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        try {
            collectValue(JSON.parse(configJson), ownerUniqueCode, teamId, portfolioId, objectKeys);
        } catch (RuntimeException exception) {
            log.warn("团队作品集素材清理跳过无效配置: teamId={}, portfolioId={}", teamId, portfolioId);
        }
    }

    /**
     * 递归遍历 JSON 节点中的字符串值。
     */
    private void collectValue(
            Object value,
            String ownerUniqueCode,
            long teamId,
            long portfolioId,
            Set<String> objectKeys
    ) {
        if (value instanceof JSONObject object) {
            object.values().forEach(child -> collectValue(
                    child, ownerUniqueCode, teamId, portfolioId, objectKeys));
            return;
        }
        if (value instanceof JSONArray array) {
            array.forEach(child -> collectValue(
                    child, ownerUniqueCode, teamId, portfolioId, objectKeys));
            return;
        }
        if (value instanceof String text) {
            String objectKey = resolveExactOwnedObjectKey(
                    ownerUniqueCode, teamId, portfolioId, text, true);
            if (objectKey != null) {
                objectKeys.add(objectKey);
            }
        }
    }

    /**
     * 从公开地址或对象键中解析锚定当前 owner 的精确对象键。
     */
    private String resolveExactOwnedObjectKey(
            String ownerUniqueCode,
            long teamId,
            long portfolioId,
            String value,
            boolean allowRawObjectKey
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String objectPrefix = ownerUniqueCode + "/" + PORTFOLIO_ASSET_FOLDER
                + "/team-" + teamId + "/portfolio-" + portfolioId + "/";
        int prefixIndex = value.indexOf(objectPrefix);
        if (prefixIndex < 0) {
            return null;
        }
        String objectKey = value.substring(prefixIndex);
        Pattern exactPattern = Pattern.compile("^" + Pattern.quote(objectPrefix) + UUID_FILE_PATTERN + "$");
        if (!exactPattern.matcher(objectKey).matches()) {
            return null;
        }
        if (allowRawObjectKey && value.equals(objectKey)) {
            return objectKey;
        }
        return value.equals(cosService.publicUrl(objectKey)) ? objectKey : null;
    }

    /**
     * 校验 COS 图片对象头。
     */
    private boolean isValidUploadedImageHead(CosService.ObjectHead head) {
        if (head == null || head.contentLength() <= 0L || head.contentLength() > IMAGE_MAX_BYTES) {
            return false;
        }
        String contentType = head.contentType() == null
                ? "" : head.contentType().strip().toLowerCase(Locale.ROOT);
        return MIME_IMAGE_JPEG.equals(contentType)
                || MIME_IMAGE_JPG.equals(contentType)
                || MIME_IMAGE_PNG.equals(contentType);
    }

    /**
     * 读取团队当前拥有者唯一码，业务校验失败时使用固定提示。
     */
    private String requireOwnerUniqueCode(long teamId) {
        String uniqueCode = resolveOwnerUniqueCode(teamId);
        if (uniqueCode == null) {
            throw new BusinessException(TEAM_OWNER_STORAGE_INVALID_MESSAGE);
        }
        return uniqueCode;
    }

    /**
     * 清理时读取团队当前拥有者唯一码，无法证明归属时安全跳过。
     */
    private String resolveOwnerUniqueCodeForCleanup(long teamId) {
        String uniqueCode = resolveOwnerUniqueCode(teamId);
        if (uniqueCode == null) {
            log.warn("团队作品集素材清理跳过无拥有者存储信息: teamId={}", teamId);
        }
        return uniqueCode;
    }

    /**
     * 解析团队当前拥有者唯一码。
     */
    private String resolveOwnerUniqueCode(long teamId) {
        TeamEntity team = teamEntityMapper.selectById(teamId);
        UserEntity owner = team == null || team.getOwnerUserId() == null
                ? null : userEntityMapper.selectById(team.getOwnerUserId());
        return owner == null || owner.getUniqueCode() == null || owner.getUniqueCode().isBlank()
                ? null : owner.getUniqueCode();
    }

    /**
     * 清理单个对象，失败时保留业务事务结果。
     */
    private void deleteQuietly(long portfolioId, String objectKey) {
        try {
            cosService.delete(objectKey);
        } catch (Exception exception) {
            log.warn("团队作品集素材删除失败: portfolioId={}, objectKey={}", portfolioId, objectKey, exception);
        }
    }
}
