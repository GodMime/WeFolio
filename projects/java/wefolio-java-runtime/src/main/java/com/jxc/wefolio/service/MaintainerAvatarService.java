package com.jxc.wefolio.service;

import com.jxc.wefolio.common.upload.AvatarFileValidator;
import com.jxc.wefolio.common.upload.AvatarImageFormat;
import com.jxc.wefolio.common.upload.AvatarUploadResult;
import com.jxc.wefolio.dto.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 维护者头像上传服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintainerAvatarService {

    /** 头像文件提示名称 */
    private static final String AVATAR_FILE_LABEL = "头像文件";

    /** 个人杂项目录 */
    private static final String AVATAR_FOLDER = "others";

    /** 头像文件名前缀 */
    private static final String AVATAR_FILE_PREFIX = "avatar";

    /** 头像文件名时间格式 */
    private static final DateTimeFormatter AVATAR_FILE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 头像文件名随机后缀长度 */
    private static final int AVATAR_RANDOM_LENGTH = 8;

    /** 小程序登录服务 */
    private final MiniappAuthService miniappAuthService;

    /** COS 文件服务 */
    private final CosService cosService;

    /**
     * 校验并上传维护者头像。
     *
     * @param userId 当前用户 ID
     * @param file 头像文件
     * @return 头像上传结果
     */
    public AvatarUploadResult uploadAvatar(Long userId, MultipartFile file) {
        String validationMessage = AvatarFileValidator.validate(file, AVATAR_FILE_LABEL);
        if (validationMessage != null) {
            return AvatarUploadResult.failure(validationMessage);
        }
        String uniqueCode = miniappAuthService.getUniqueCodeByUserId(userId);
        log.info("头像上传开始: userId={}, uniqueCode={}, originalFilename={}, size={}",
                userId, uniqueCode, file.getOriginalFilename(), file.getSize());
        String objectKey = buildAvatarObjectKey(uniqueCode, file, LocalDateTime.now());
        String key = cosService.uploadToObjectKey(file, objectKey);
        String url = cosService.publicUrl(key);
        log.info("头像上传成功: userId={}, uniqueCode={}, key={}, url={}", userId, uniqueCode, key, url);
        FileUploadResponse response = new FileUploadResponse();
        response.setKey(key);
        response.setUrl(url);
        return AvatarUploadResult.succeeded(response);
    }

    /** 生成与个人资料头像归属校验一致的 COS 对象键。 */
    private String buildAvatarObjectKey(String uniqueCode, MultipartFile file, LocalDateTime now) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, AVATAR_RANDOM_LENGTH);
        return uniqueCode
                + "/" + AVATAR_FOLDER
                + "/" + AVATAR_FILE_PREFIX
                + "-" + now.format(AVATAR_FILE_TIME_FORMATTER)
                + "-" + random
                + "." + AvatarImageFormat.detect(file).orElseThrow().extension();
    }
}
