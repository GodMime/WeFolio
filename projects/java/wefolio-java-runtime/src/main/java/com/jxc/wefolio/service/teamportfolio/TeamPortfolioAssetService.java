package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioAssetUploadTicketResponse;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /** 二维码联系素材类型。 */
    private static final String ASSET_TYPE_QR_CONTACT = "QR_CONTACT";

    /** 团队资料头像素材类型。 */
    private static final String ASSET_TYPE_TEAM_PROFILE_AVATAR = "TEAM_PROFILE_AVATAR";

    /** 封面文件名前缀。 */
    private static final String COVER_FILE_PREFIX = "cover";

    /** 二维码联系文件名前缀。 */
    private static final String QR_CONTACT_FILE_PREFIX = "qr-contact";

    /** 团队资料头像文件名前缀。 */
    private static final String TEAM_PROFILE_AVATAR_FILE_PREFIX = "team-profile-avatar";

    /** 素材文件名时间格式。 */
    private static final DateTimeFormatter ASSET_FILE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 素材文件名随机后缀长度。 */
    private static final int ASSET_RANDOM_LENGTH = 8;

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

    /** 团队存储信息异常提示。 */
    private static final String TEAM_STORAGE_INVALID_MESSAGE = "团队素材存储信息不可用";

    /** 已上传图片不可用统一提示，不透传 COS 底层异常。 */
    private static final String UPLOADED_IMAGE_INVALID_MESSAGE = "团队作品集图片未完成上传或不可用";

    /** 素材文件名表达式。 */
    private static final String ASSET_FILE_PATTERN =
            "(?:cover|qr-contact|team-profile-avatar)-%d-[0-9]{14}-[0-9a-f]{8}\\.(?:jpg|png)";

    /** 团队作品集访问控制服务。 */
    private final TeamPortfolioAccessService accessService;

    /** 团队数据访问器。 */
    private final TeamEntityMapper teamEntityMapper;

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
        if (team == null || team.getUniqueCode() == null || team.getUniqueCode().isBlank()) {
            throw new BusinessException(TEAM_STORAGE_INVALID_MESSAGE);
        }
        String objectKey = buildObjectKey(
                team.getUniqueCode(), portfolioId, assetType, contentType, LocalDateTime.now());
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
        String teamUniqueCode = requireTeamUniqueCode(teamId);
        String objectKey = resolveExactOwnedObjectKey(teamUniqueCode, portfolioId, url, false);
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
        String teamUniqueCode = resolveTeamUniqueCodeForCleanup(teamId);
        if (teamUniqueCode == null) {
            return;
        }
        Set<String> objectKeys = new LinkedHashSet<>();
        collectOwnedObjectKeys(draftConfigJson, teamUniqueCode, teamId, portfolioId, objectKeys);
        collectOwnedObjectKeys(publishedConfigJson, teamUniqueCode, teamId, portfolioId, objectKeys);
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
     * 在事务提交后清理更新前存在、更新后不再被当前草稿态或发布态引用的自有素材。
     *
     * @param teamId 团队 ID
     * @param portfolioId 作品集 ID
     * @param beforeStateJson 更新前草稿态与发布态配置包
     * @param afterStateJson 更新后草稿态与发布态配置包
     */
    public void deleteUnreferencedAssetsAfterCommit(
            long teamId,
            long portfolioId,
            String beforeStateJson,
            String afterStateJson
    ) {
        String teamUniqueCode = resolveTeamUniqueCodeForCleanup(teamId);
        if (teamUniqueCode == null) {
            return;
        }
        Set<String> beforeObjectKeys = new LinkedHashSet<>();
        Set<String> afterObjectKeys = new LinkedHashSet<>();
        collectOwnedObjectKeys(beforeStateJson, teamUniqueCode, teamId, portfolioId, beforeObjectKeys);
        collectOwnedObjectKeys(afterStateJson, teamUniqueCode, teamId, portfolioId, afterObjectKeys);
        beforeObjectKeys.removeAll(afterObjectKeys);
        if (beforeObjectKeys.isEmpty()) {
            return;
        }
        Runnable deleteTask = () -> beforeObjectKeys.forEach(objectKey -> deleteQuietly(portfolioId, objectKey));
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
                || ASSET_TYPE_QR_CONTACT.equals(assetType)
                || ASSET_TYPE_TEAM_PROFILE_AVATAR.equals(assetType)) {
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
     * 按团队唯一码和个人作品集命名规则构建对象键。
     */
    private String buildObjectKey(
            String teamUniqueCode,
            long portfolioId,
            String assetType,
            String contentType,
            LocalDateTime now
    ) {
        String extension = MIME_IMAGE_PNG.equals(contentType) ? EXTENSION_PNG : EXTENSION_JPG;
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, ASSET_RANDOM_LENGTH);
        String filePrefix = switch (assetType) {
            case ASSET_TYPE_QR_CONTACT -> QR_CONTACT_FILE_PREFIX;
            case ASSET_TYPE_TEAM_PROFILE_AVATAR -> TEAM_PROFILE_AVATAR_FILE_PREFIX;
            default -> COVER_FILE_PREFIX;
        };
        return teamUniqueCode + "/" + PORTFOLIO_ASSET_FOLDER
                + "/" + filePrefix
                + "-" + portfolioId
                + "-" + now.format(ASSET_FILE_TIME_FORMATTER)
                + "-" + random
                + "." + extension;
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
            String teamUniqueCode,
            long teamId,
            long portfolioId,
            Set<String> objectKeys
    ) {
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        try {
            collectValue(JSON.parse(configJson), teamUniqueCode, portfolioId, objectKeys);
        } catch (RuntimeException exception) {
            log.warn("团队作品集素材清理跳过无效配置: teamId={}, portfolioId={}", teamId, portfolioId);
        }
    }

    /**
     * 递归遍历 JSON 节点中的字符串值。
     */
    private void collectValue(
            Object value,
            String teamUniqueCode,
            long portfolioId,
            Set<String> objectKeys
    ) {
        if (value instanceof JSONObject object) {
            object.values().forEach(child -> collectValue(
                    child, teamUniqueCode, portfolioId, objectKeys));
            return;
        }
        if (value instanceof JSONArray array) {
            array.forEach(child -> collectValue(
                    child, teamUniqueCode, portfolioId, objectKeys));
            return;
        }
        if (value instanceof String text) {
            String objectKey = resolveExactOwnedObjectKey(
                    teamUniqueCode, portfolioId, text, true);
            if (objectKey != null) {
                objectKeys.add(objectKey);
            }
        }
    }

    /**
     * 从公开地址或对象键中解析锚定当前团队和作品集的精确对象键。
     */
    private String resolveExactOwnedObjectKey(
            String teamUniqueCode,
            long portfolioId,
            String value,
            boolean allowRawObjectKey
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String objectPrefix = teamUniqueCode + "/" + PORTFOLIO_ASSET_FOLDER + "/";
        int prefixIndex = value.indexOf(objectPrefix);
        if (prefixIndex < 0) {
            return null;
        }
        String objectKey = value.substring(prefixIndex);
        Pattern exactPattern = Pattern.compile("^" + Pattern.quote(objectPrefix)
                + ASSET_FILE_PATTERN.formatted(portfolioId) + "$");
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
     * 读取团队唯一码，业务校验失败时使用固定提示。
     */
    private String requireTeamUniqueCode(long teamId) {
        String uniqueCode = resolveTeamUniqueCode(teamId);
        if (uniqueCode == null) {
            throw new BusinessException(TEAM_STORAGE_INVALID_MESSAGE);
        }
        return uniqueCode;
    }

    /**
     * 清理时读取团队唯一码，无法证明归属时安全跳过。
     */
    private String resolveTeamUniqueCodeForCleanup(long teamId) {
        String uniqueCode = resolveTeamUniqueCode(teamId);
        if (uniqueCode == null) {
            log.warn("团队作品集素材清理跳过无团队存储信息: teamId={}", teamId);
        }
        return uniqueCode;
    }

    /**
     * 解析团队唯一码。
     */
    private String resolveTeamUniqueCode(long teamId) {
        TeamEntity team = teamEntityMapper.selectById(teamId);
        return team == null || team.getUniqueCode() == null || team.getUniqueCode().isBlank()
                ? null : team.getUniqueCode();
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
