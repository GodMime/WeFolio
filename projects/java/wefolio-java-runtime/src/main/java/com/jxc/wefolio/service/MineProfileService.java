package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.upload.AvatarFileValidator;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketRequest;
import com.jxc.wefolio.dto.MineProfileAssetUploadTicketResponse;
import com.jxc.wefolio.dto.MineProfileResponse;
import com.jxc.wefolio.dto.MineProfileTagDTO;
import com.jxc.wefolio.dto.MineProfileUpdateRequest;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.MineProfileMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基础信息服务 — 负责维护者个人资料读取与保存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineProfileService {

    /** 姓名或艺名最大长度 */
    private static final int NICKNAME_MAX_LENGTH = 50;

    /** 头像地址最大长度 */
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    /** 微信二维码最大字节数：必须小于 300KB */
    private static final long WECHAT_QR_MAX_SIZE_BYTES = 300L * 1024L;

    /** 微信二维码直传策略最大字节数 */
    private static final long WECHAT_QR_UPLOAD_MAX_BYTES = WECHAT_QR_MAX_SIZE_BYTES - 1L;

    /** 基础信息头像素材类型 */
    private static final String ASSET_TYPE_AVATAR = "AVATAR";

    /** 基础信息微信二维码素材类型 */
    private static final String ASSET_TYPE_WECHAT_QR = "WECHAT_QR";

    /** 个人杂项目录 */
    private static final String PROFILE_ASSET_FOLDER = "others";

    /** 头像文件名前缀 */
    private static final String AVATAR_FILE_PREFIX = "avatar";

    /** 微信二维码文件名前缀 */
    private static final String WECHAT_QR_FILE_PREFIX = "wechat-qr";

    /** 资料图片文件名时间格式 */
    private static final DateTimeFormatter ASSET_FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 文件名随机后缀长度 */
    private static final int ASSET_RANDOM_LENGTH = 8;

    /** JPEG MIME 类型 */
    private static final String MIME_IMAGE_JPEG = "image/jpeg";

    /** JPG MIME 类型兼容值 */
    private static final String MIME_IMAGE_JPG = "image/jpg";

    /** PNG MIME 类型 */
    private static final String MIME_IMAGE_PNG = "image/png";

    /** GIF MIME 类型 */
    private static final String MIME_IMAGE_GIF = "image/gif";

    /** WebP MIME 类型 */
    private static final String MIME_IMAGE_WEBP = "image/webp";

    /** JPG 文件扩展名 */
    private static final String EXTENSION_JPG = "jpg";

    /** PNG 文件扩展名 */
    private static final String EXTENSION_PNG = "png";

    /** GIF 文件扩展名 */
    private static final String EXTENSION_GIF = "gif";

    /** WebP 文件扩展名 */
    private static final String EXTENSION_WEBP = "webp";

    /** 职业身份最大长度 */
    private static final int PROFESSION_MAX_LENGTH = 50;

    /** 服务城市最大长度 */
    private static final int CITY_MAX_LENGTH = 50;

    /** 个人简介最大长度 */
    private static final int INTRO_MAX_LENGTH = 500;

    /** 标签最大数量 */
    private static final int TAG_MAX_COUNT = 10;

    /** 单个标签最大长度 */
    private static final int TAG_MAX_LENGTH = 10;

    /** 默认标签颜色 */
    private static final String DEFAULT_TAG_COLOR = "#0f766e";

    /** 可选标签色板 */
    private static final Set<String> TAG_COLORS = Set.of(
            "#0f766e",
            "#2d5f9a",
            "#8a4b09",
            "#a9354f",
            "#6d5bd0",
            "#3f6f45",
            "#36516e",
            "#9a4a35",
            "#4b5563"
    );

    /** 用户表主键列 */
    private static final String COL_ID = "id";

    /** 昵称列 */
    private static final String COL_NICKNAME = "nickname";

    /** 头像列 */
    private static final String COL_AVATAR_URL = "avatar_url";

    /** 上次头像更新时间列 */
    private static final String COL_LAST_AVATAR_UPDATED_AT = "last_avatar_updated_at";

    /** 当月头像变更次数列 */
    private static final String COL_AVATAR_UPDATE_COUNT = "avatar_update_count";

    /** 微信二维码列 */
    private static final String COL_WECHAT_QR_URL = "wechat_qr_url";

    /** 职业身份列 */
    private static final String COL_PROFESSION = "profession";

    /** 服务城市列 */
    private static final String COL_CITY = "city";

    /** 个人简介列 */
    private static final String COL_INTRO = "intro";

    /** 标签列 */
    private static final String COL_PROFILE_TAGS = "profile_tags";

    /** 上次微信二维码更新时间列 */
    private static final String COL_LAST_WECHAT_QR_UPDATED_AT = "last_wechat_qr_updated_at";

    /** 当月微信二维码变更次数列 */
    private static final String COL_WECHAT_QR_UPDATE_COUNT = "wechat_qr_update_count";

    /** 用户资料 Mapper */
    private final UserEntityMapper userEntityMapper;

    /** COS 服务 */
    private final CosService cosService;

    /**
     * 获取基础信息页资料
     *
     * @return 基础信息响应
     */
    public MineProfileResponse getProfile() {
        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = requireActiveUser(userId);
        return buildResponse(user);
    }

    /**
     * 创建基础信息资料图片直传 COS 票据。
     *
     * @param request 票据创建请求
     * @return 直传票据响应
     */
    public MineProfileAssetUploadTicketResponse createProfileAssetUploadTicket(MineProfileAssetUploadTicketRequest request) {
        if (request == null) {
            throw new BusinessException(MineProfileMessage.PROFILE_ASSET_REQUIRED_MESSAGE);
        }

        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = requireActiveUser(userId);
        String assetType = normalizeAssetType(request.getAssetType());
        String contentType = normalizeContentType(assetType, request.getMimeType());
        long maxBytes = resolveAssetMaxBytes(assetType);
        validateUploadSize(assetType, request.getFileSize(), maxBytes);

        String objectKey = buildProfileAssetObjectKey(
                user.getUniqueCode(),
                assetType,
                contentType,
                LocalDateTime.now()
        );
        CosService.PostUploadTicket ticket = cosService.createPostUploadTicket(
                objectKey,
                contentType,
                maxBytes,
                LocalDateTime.now().plusMinutes(15)
        );
        return buildAssetUploadTicketResponse(assetType, ticket);
    }

    /**
     * 保存基础信息页资料
     *
     * @param request 保存请求
     * @return 保存后的基础信息响应
     */
    public MineProfileResponse updateProfile(MineProfileUpdateRequest request) {
        if (request == null) {
            throw new BusinessException(MineProfileMessage.PROFILE_UPDATE_REQUIRED_MESSAGE);
        }

        Long userId = AuthContextHolder.requireUserId();
        UserEntity user = requireActiveUser(userId);
        UpdateWrapper<UserEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(COL_ID, userId);

        applyStringField(request.getNickname(), NICKNAME_MAX_LENGTH, "姓名 / 艺名",
                user::setNickname, value -> updateWrapper.set(COL_NICKNAME, value));
        applyAvatarField(request.getAvatarUrl(), user, updateWrapper);
        applyWechatQrField(request.getWechatQrUrl(), user, updateWrapper);
        applyStringField(request.getProfession(), PROFESSION_MAX_LENGTH, "职业身份",
                user::setProfession, value -> updateWrapper.set(COL_PROFESSION, value));
        applyStringField(request.getCity(), CITY_MAX_LENGTH, "服务城市",
                user::setCity, value -> updateWrapper.set(COL_CITY, value));
        applyStringField(request.getIntro(), INTRO_MAX_LENGTH, "个人简介",
                user::setIntro, value -> updateWrapper.set(COL_INTRO, value));
        if (request.getTags() != null) {
            String profileTags = JSON.toJSONString(normalizeTags(request.getTags()));
            user.setProfileTags(profileTags);
            updateWrapper.set(COL_PROFILE_TAGS, profileTags);
        }

        int updated = userEntityMapper.update(new UserEntity(), updateWrapper);
        if (updated <= 0) {
            throw new BusinessException(MineProfileMessage.PROFILE_SAVE_FAILED_MESSAGE);
        }
        return buildResponse(user);
    }

    /**
     * 规范化资料图片类型。
     *
     * @param assetType 原始资料图片类型
     * @return 规范化类型
     */
    private String normalizeAssetType(String assetType) {
        String normalized = defaultString(assetType).strip().toUpperCase(Locale.ROOT);
        if (ASSET_TYPE_AVATAR.equals(normalized) || ASSET_TYPE_WECHAT_QR.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(MineProfileMessage.PROFILE_ASSET_TYPE_UNSUPPORTED_MESSAGE);
    }

    /**
     * 规范化并校验资料图片 MIME 类型。
     *
     * @param assetType 资料图片类型
     * @param mimeType 原始 MIME 类型
     * @return 规范化 MIME 类型
     */
    private String normalizeContentType(String assetType, String mimeType) {
        String normalized = defaultString(mimeType).strip().toLowerCase(Locale.ROOT);
        if (ASSET_TYPE_WECHAT_QR.equals(assetType)) {
            if (MIME_IMAGE_JPEG.equals(normalized) || MIME_IMAGE_JPG.equals(normalized)) {
                return MIME_IMAGE_JPEG;
            }
            if (MIME_IMAGE_PNG.equals(normalized)) {
                return MIME_IMAGE_PNG;
            }
            throw new BusinessException(MineProfileMessage.WECHAT_QR_FORMAT_UNSUPPORTED_MESSAGE);
        }
        if (MIME_IMAGE_JPEG.equals(normalized) || MIME_IMAGE_JPG.equals(normalized)) {
            return MIME_IMAGE_JPEG;
        }
        if (MIME_IMAGE_PNG.equals(normalized)
                || MIME_IMAGE_GIF.equals(normalized)
                || MIME_IMAGE_WEBP.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(MineProfileMessage.PROFILE_ASSET_TYPE_UNSUPPORTED_MESSAGE);
    }

    /**
     * 解析资料图片允许的最大字节数。
     *
     * @param assetType 资料图片类型
     * @return 最大字节数
     */
    private long resolveAssetMaxBytes(String assetType) {
        if (ASSET_TYPE_WECHAT_QR.equals(assetType)) {
            return WECHAT_QR_UPLOAD_MAX_BYTES;
        }
        return AvatarFileValidator.MAX_AVATAR_SIZE_BYTES;
    }

    /**
     * 校验前端声明的上传文件大小。
     *
     * @param assetType 资料图片类型
     * @param fileSize 文件字节数
     * @param maxBytes 最大允许字节数
     */
    private void validateUploadSize(String assetType, Long fileSize, long maxBytes) {
        long normalizedSize = fileSize == null ? 0L : fileSize;
        if (normalizedSize <= 0L) {
            throw new BusinessException(MineProfileMessage.PROFILE_ASSET_SIZE_INVALID_MESSAGE);
        }
        if (normalizedSize > maxBytes) {
            if (ASSET_TYPE_WECHAT_QR.equals(assetType)) {
                throw new BusinessException(MineProfileMessage.WECHAT_QR_SIZE_LIMIT_MESSAGE);
            }
            throw new BusinessException(MineProfileMessage.AVATAR_SIZE_LIMIT_MESSAGE);
        }
    }

    /**
     * 构建基础信息资料图片对象键。
     *
     * @param uniqueCode 用户唯一码
     * @param assetType 资料图片类型
     * @param contentType MIME 类型
     * @param now 当前时间
     * @return COS 对象键
     */
    private String buildProfileAssetObjectKey(
            String uniqueCode,
            String assetType,
            String contentType,
            LocalDateTime now
    ) {
        String fileName = resolveAssetFilePrefix(assetType)
                + "-" + now.format(ASSET_FILE_TIME_FORMATTER)
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, ASSET_RANDOM_LENGTH)
                + "." + resolveFileExtension(contentType);
        return uniqueCode + "/" + PROFILE_ASSET_FOLDER + "/" + fileName;
    }

    /**
     * 解析资料图片文件名前缀。
     *
     * @param assetType 资料图片类型
     * @return 文件名前缀
     */
    private String resolveAssetFilePrefix(String assetType) {
        return ASSET_TYPE_WECHAT_QR.equals(assetType) ? WECHAT_QR_FILE_PREFIX : AVATAR_FILE_PREFIX;
    }

    /**
     * 解析文件扩展名。
     *
     * @param contentType MIME 类型
     * @return 文件扩展名
     */
    private String resolveFileExtension(String contentType) {
        if (MIME_IMAGE_PNG.equals(contentType)) {
            return EXTENSION_PNG;
        }
        if (MIME_IMAGE_GIF.equals(contentType)) {
            return EXTENSION_GIF;
        }
        if (MIME_IMAGE_WEBP.equals(contentType)) {
            return EXTENSION_WEBP;
        }
        return EXTENSION_JPG;
    }

    /**
     * 构建资料图片直传票据响应。
     *
     * @param assetType 资料图片类型
     * @param ticket COS 直传票据
     * @return 前端响应
     */
    private MineProfileAssetUploadTicketResponse buildAssetUploadTicketResponse(
            String assetType,
            CosService.PostUploadTicket ticket
    ) {
        MineProfileAssetUploadTicketResponse response = new MineProfileAssetUploadTicketResponse();
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
     * 请求字段存在时才覆盖实体字段；字段缺失时保留数据库原值
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @param setter 实体字段赋值方法
     * @param updateSetter 局部更新字段赋值方法
     */
    private void applyStringField(
            String value,
            int maxLength,
            String fieldName,
            Consumer<String> setter,
            Consumer<String> updateSetter
    ) {
        if (value == null) {
            return;
        }
        String normalized = trimAndCheckLength(value, maxLength, fieldName);
        setter.accept(normalized);
        updateSetter.accept(normalized);
    }

    /**
     * 处理头像字段更新，包含每月 10 次限制校验。
     * 跨月时自动重置计数。
     *
     * @param avatarUrl     新头像地址，为空时不更新
     * @param user          当前用户实体
     * @param updateWrapper 局部更新包装器
     */
    private void applyAvatarField(String avatarUrl, UserEntity user, UpdateWrapper<UserEntity> updateWrapper) {
        if (avatarUrl == null) {
            return;
        }
        String normalized = trimAndCheckLength(avatarUrl, AVATAR_URL_MAX_LENGTH, "头像地址");

        // 头像地址未变化，跳过计数
        if (normalized.equals(defaultString(user.getAvatarUrl()))) {
            return;
        }

        String objectKey = extractProfileAssetObjectKey(
                normalized,
                user.getUniqueCode(),
                AVATAR_FILE_PREFIX,
                MineProfileMessage.AVATAR_OWNERSHIP_INVALID_MESSAGE
        );
        CosService.ObjectHead objectHead = requireProfileAssetObjectHead(objectKey);
        validateAvatarObjectHead(objectHead);

        LocalDateTime now = LocalDateTime.now();
        int newCount = computeMonthlyAvatarUpdateCount(user, now);
        if (newCount > UserEntity.AVATAR_MONTHLY_MAX_COUNT) {
            throw new BusinessException(String.format(
                    MineProfileMessage.AVATAR_UPDATE_LIMIT_TEMPLATE,
                    UserEntity.AVATAR_MONTHLY_MAX_COUNT));
        }

        user.setAvatarUrl(normalized);
        updateWrapper.set(COL_AVATAR_URL, normalized);
        user.setLastAvatarUpdatedAt(now);
        updateWrapper.set(COL_LAST_AVATAR_UPDATED_AT, now);
        user.setAvatarUpdateCount(newCount);
        updateWrapper.set(COL_AVATAR_UPDATE_COUNT, newCount);
    }

    /**
     * 处理微信二维码字段更新，包含归属校验和每月 3 次限制。
     *
     * @param wechatQrUrl 新微信二维码地址，字段缺失时不更新
     * @param user 当前用户实体
     * @param updateWrapper 局部更新包装器
     */
    private void applyWechatQrField(String wechatQrUrl, UserEntity user, UpdateWrapper<UserEntity> updateWrapper) {
        if (wechatQrUrl == null) {
            return;
        }
        String normalized = trimAndCheckLength(wechatQrUrl, AVATAR_URL_MAX_LENGTH, "微信二维码地址");

        // 二维码地址未变化，跳过计数和 COS 读取。
        if (normalized.equals(defaultString(user.getWechatQrUrl()))) {
            return;
        }

        if (normalized.isBlank()) {
            user.setWechatQrUrl(normalized);
            updateWrapper.set(COL_WECHAT_QR_URL, normalized);
            return;
        }

        String objectKey = extractProfileAssetObjectKey(
                normalized,
                user.getUniqueCode(),
                WECHAT_QR_FILE_PREFIX,
                MineProfileMessage.WECHAT_QR_OWNERSHIP_INVALID_MESSAGE
        );
        CosService.ObjectHead objectHead = requireProfileAssetObjectHead(objectKey);
        validateWechatQrObjectHead(objectHead);

        LocalDateTime now = LocalDateTime.now();
        int newCount = computeMonthlyWechatQrUpdateCount(user, now);
        if (newCount > UserEntity.WECHAT_QR_MONTHLY_MAX_COUNT) {
            throw new BusinessException(String.format(
                    MineProfileMessage.WECHAT_QR_UPDATE_LIMIT_TEMPLATE,
                    UserEntity.WECHAT_QR_MONTHLY_MAX_COUNT));
        }

        user.setWechatQrUrl(normalized);
        updateWrapper.set(COL_WECHAT_QR_URL, normalized);
        user.setLastWechatQrUpdatedAt(now);
        updateWrapper.set(COL_LAST_WECHAT_QR_UPDATED_AT, now);
        user.setWechatQrUpdateCount(newCount);
        updateWrapper.set(COL_WECHAT_QR_UPDATE_COUNT, newCount);
    }

    /**
     * 从公开 URL 中提取并校验当前用户资料图片对象键。
     *
     * @param url 公开访问 URL
     * @param uniqueCode 当前用户唯一码
     * @param filePrefix 文件名前缀
     * @param ownershipMessage 归属错误文案
     * @return COS 对象键
     */
    private String extractProfileAssetObjectKey(
            String url,
            String uniqueCode,
            String filePrefix,
            String ownershipMessage
    ) {
        String normalizedUrl = stripUrlSuffix(defaultString(url));
        String keyPrefix = uniqueCode + "/" + PROFILE_ASSET_FOLDER + "/" + filePrefix + "-";
        int index = normalizedUrl.indexOf(keyPrefix);
        if (index < 0) {
            throw new BusinessException(ownershipMessage);
        }
        String objectKey = normalizedUrl.substring(index);
        String extensionPattern = WECHAT_QR_FILE_PREFIX.equals(filePrefix)
                ? "\\.(jpg|png)"
                : "\\.(jpg|png|gif|webp)";
        String objectKeyPattern = uniqueCode
                + "/" + PROFILE_ASSET_FOLDER
                + "/" + filePrefix
                + "-\\d{14}-[a-f0-9]{8}"
                + extensionPattern;
        if (!objectKey.matches(objectKeyPattern)) {
            throw new BusinessException(ownershipMessage);
        }
        return objectKey;
    }

    /**
     * 去除 URL 查询参数和锚点。
     *
     * @param url 原始 URL
     * @return 去除后缀后的 URL
     */
    private String stripUrlSuffix(String url) {
        int queryIndex = url.indexOf('?');
        int fragmentIndex = url.indexOf('#');
        int endIndex = url.length();
        if (queryIndex >= 0) {
            endIndex = Math.min(endIndex, queryIndex);
        }
        if (fragmentIndex >= 0) {
            endIndex = Math.min(endIndex, fragmentIndex);
        }
        return url.substring(0, endIndex);
    }

    /**
     * 读取资料图片对象头，失败时转换为业务异常。
     *
     * @param objectKey COS 对象键
     * @return 对象头
     */
    private CosService.ObjectHead requireProfileAssetObjectHead(String objectKey) {
        try {
            return cosService.headObject(objectKey);
        } catch (RuntimeException e) {
            throw new BusinessException(MineProfileMessage.PROFILE_ASSET_EXPIRED_MESSAGE);
        }
    }

    /**
     * 校验头像对象头。
     *
     * @param objectHead COS 对象头
     */
    private void validateAvatarObjectHead(CosService.ObjectHead objectHead) {
        normalizeContentType(ASSET_TYPE_AVATAR, objectHead.contentType());
        if (objectHead.contentLength() <= 0L || objectHead.contentLength() > AvatarFileValidator.MAX_AVATAR_SIZE_BYTES) {
            throw new BusinessException(MineProfileMessage.AVATAR_SIZE_LIMIT_MESSAGE);
        }
    }

    /**
     * 校验微信二维码对象头。
     *
     * @param objectHead COS 对象头
     */
    private void validateWechatQrObjectHead(CosService.ObjectHead objectHead) {
        normalizeContentType(ASSET_TYPE_WECHAT_QR, objectHead.contentType());
        if (objectHead.contentLength() <= 0L || objectHead.contentLength() >= WECHAT_QR_MAX_SIZE_BYTES) {
            throw new BusinessException(MineProfileMessage.WECHAT_QR_SIZE_LIMIT_MESSAGE);
        }
    }

    /**
     * 计算当月头像更新次数，跨月自动重置。
     *
     * @param user 当前用户实体
     * @param now  当前时间
     * @return 本次更新后的计数值
     */
    private int computeMonthlyAvatarUpdateCount(UserEntity user, LocalDateTime now) {
        LocalDateTime lastUpdate = user.getLastAvatarUpdatedAt();
        int currentCount = user.getAvatarUpdateCount() != null ? user.getAvatarUpdateCount() : 0;

        boolean newMonth = lastUpdate == null
                || lastUpdate.getYear() != now.getYear()
                || lastUpdate.getMonthValue() != now.getMonthValue();
        return newMonth ? 1 : currentCount + 1;
    }

    /**
     * 计算当月微信二维码更新次数，跨月自动重置。
     *
     * @param user 当前用户实体
     * @param now 当前时间
     * @return 本次更新后的计数值
     */
    private int computeMonthlyWechatQrUpdateCount(UserEntity user, LocalDateTime now) {
        LocalDateTime lastUpdate = user.getLastWechatQrUpdatedAt();
        int currentCount = user.getWechatQrUpdateCount() != null ? user.getWechatQrUpdateCount() : 0;

        boolean newMonth = lastUpdate == null
                || lastUpdate.getYear() != now.getYear()
                || lastUpdate.getMonthValue() != now.getMonthValue();
        return newMonth ? 1 : currentCount + 1;
    }

    /**
     * 查询并校验当前用户状态
     *
     * @param userId 当前登录用户 ID
     * @return 活跃用户实体
     */
    private UserEntity requireActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(MineProfileMessage.USER_LOGIN_REQUIRED_MESSAGE);
        }
        UserEntity user = userEntityMapper.selectById(userId);
        if (user == null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus())) {
            throw new BusinessException(MineProfileMessage.USER_UNAVAILABLE_MESSAGE);
        }
        return user;
    }

    /**
     * 构建基础信息响应
     *
     * @param user 用户实体
     * @return 基础信息响应
     */
    private MineProfileResponse buildResponse(UserEntity user) {
        MineProfileResponse response = new MineProfileResponse();
        response.setUserId(user.getId());
        response.setUniqueCode(defaultString(user.getUniqueCode()));
        response.setDisplayName(buildDisplayName(user));
        response.setNickname(defaultString(user.getNickname()));
        response.setAvatarUrl(defaultString(user.getAvatarUrl()));
        response.setWechatQrUrl(defaultString(user.getWechatQrUrl()));
        response.setProfession(defaultString(user.getProfession()));
        response.setCity(defaultString(user.getCity()));
        response.setIntro(defaultString(user.getIntro()));
        response.setTags(parseTags(user.getProfileTags()));
        return response;
    }

    /**
     * 解析资料标签
     *
     * @param profileTags 标签 JSON
     * @return 标签列表
     */
    private List<MineProfileTagDTO> parseTags(String profileTags) {
        if (profileTags == null || profileTags.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JSONArray tags = JSON.parseArray(profileTags);
            return normalizeTags(tags);
        } catch (BusinessException | JSONException e) {
            log.warn("解析资料标签 JSON 失败 profileTags={}", profileTags, e);
            return Collections.emptyList();
        }
    }

    /**
     * 归一化并校验标签
     *
     * @param tags 原始标签列表，兼容字符串或对象
     * @return 已去空格且保序的标签列表
     */
    private List<MineProfileTagDTO> normalizeTags(List<?> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }

        List<MineProfileTagDTO> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object tag : tags) {
            if (seen.size() >= TAG_MAX_COUNT) {
                throw new BusinessException(MineProfileMessage.TAG_COUNT_LIMIT_MESSAGE);
            }
            String value = extractTagContent(tag).strip();
            if (value.isBlank()) {
                throw new BusinessException(MineProfileMessage.TAG_EMPTY_MESSAGE);
            }
            if (value.length() > TAG_MAX_LENGTH) {
                throw new BusinessException(MineProfileMessage.TAG_LENGTH_LIMIT_MESSAGE);
            }
            if (!seen.add(value)) {
                throw new BusinessException(MineProfileMessage.TAG_DUPLICATE_MESSAGE);
            }
            MineProfileTagDTO normalizedTag = new MineProfileTagDTO();
            normalizedTag.setContent(value);
            normalizedTag.setColor(normalizeTagColor(tag));
            normalized.add(normalizedTag);
        }
        return normalized;
    }

    /**
     * 提取标签内容，兼容旧版字符串数组。
     *
     * @param tag 原始标签项
     * @return 标签内容
     */
    private String extractTagContent(Object tag) {
        if (tag instanceof MineProfileTagDTO profileTag) {
            return defaultString(profileTag.getContent());
        }
        if (tag instanceof JSONObject jsonObject) {
            return firstPresent(jsonObject.getString("content"), jsonObject.getString("text"), jsonObject.getString("name"));
        }
        if (tag instanceof Map<?, ?> map) {
            return firstPresent(mapValue(map, "content"), mapValue(map, "text"), mapValue(map, "name"));
        }
        return defaultString(tag == null ? null : String.valueOf(tag));
    }

    /**
     * 提取并校验标签颜色。
     *
     * @param tag 原始标签项
     * @return 规范化后的标签颜色
     */
    private String normalizeTagColor(Object tag) {
        String color = DEFAULT_TAG_COLOR;
        if (tag instanceof MineProfileTagDTO profileTag) {
            color = defaultString(profileTag.getColor());
        } else if (tag instanceof JSONObject jsonObject) {
            color = defaultString(jsonObject.getString("color"));
        } else if (tag instanceof Map<?, ?> map) {
            color = mapValue(map, "color");
        }

        String normalized = defaultString(color).strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return DEFAULT_TAG_COLOR;
        }
        if (!TAG_COLORS.contains(normalized)) {
            throw new BusinessException(MineProfileMessage.TAG_COLOR_INVALID_MESSAGE);
        }
        return normalized;
    }

    /**
     * 从 Map 中取字符串值。
     *
     * @param map 原始 Map
     * @param key 字段名
     * @return 字符串值
     */
    private String mapValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param values 待选择字符串
     * @return 非空字符串
     */
    private String firstPresent(String... values) {
        for (String value : values) {
            if (!defaultString(value).isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 去除首尾空格并校验长度
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     * @return 归一化后的字符串
     */
    private String trimAndCheckLength(String value, int maxLength, String fieldName) {
        String trimmed = defaultString(value).strip();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(String.format(MineProfileMessage.FIELD_LENGTH_LIMIT_TEMPLATE, fieldName, maxLength));
        }
        return trimmed;
    }

    /**
     * 构建展示名称
     *
     * @param user 用户实体
     * @return 页面展示名称
     */
    private String buildDisplayName(UserEntity user) {
        String nickname = defaultString(user.getNickname());
        String profession = defaultString(user.getProfession());
        if (nickname.isBlank() && profession.isBlank()) {
            return "微信用户";
        }
        if (profession.isBlank()) {
            return nickname;
        }
        if (nickname.isBlank()) {
            return profession;
        }
        return nickname + " · " + profession;
    }

    /**
     * 空值安全字符串
     *
     * @param value 原字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
