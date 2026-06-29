package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.config.CosProperties;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.qcloud.cos.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CosService {

    /** COS 表单上传签名算法 */
    private static final String POST_SIGN_ALGORITHM = "sha1";

    /** COS 表单上传成功状态码 */
    private static final String POST_SUCCESS_STATUS = "200";

    /** COS 表单对象键字段 */
    private static final String POST_FIELD_KEY = "key";

    /** COS 表单签名算法字段 */
    private static final String POST_FIELD_SIGN_ALGORITHM = "q-sign-algorithm";

    /** COS 表单访问密钥字段 */
    private static final String POST_FIELD_ACCESS_KEY = "q-ak";

    /** COS 表单签名时间字段 */
    private static final String POST_FIELD_KEY_TIME = "q-key-time";

    /** COS policy 中的签名时间字段 */
    private static final String POST_FIELD_SIGN_TIME = "q-sign-time";

    /** COS 表单 policy 字段 */
    private static final String POST_FIELD_POLICY = "policy";

    /** COS 表单签名字段 */
    private static final String POST_FIELD_SIGNATURE = "q-signature";

    /** COS 表单成功状态字段 */
    private static final String POST_FIELD_SUCCESS_STATUS = "success_action_status";

    /** COS policy 存储桶字段 */
    private static final String POST_POLICY_BUCKET = "bucket";

    /** COS policy 上传大小约束字段 */
    private static final String POST_POLICY_CONTENT_LENGTH_RANGE = "content-length-range";

    /** COS 表单签名起始时间回退秒数，用于容忍服务器与 COS 的轻微时钟偏差 */
    private static final long POST_KEY_TIME_CLOCK_SKEW_SECONDS = 60L;

    /** COS 表单上传地址模板 */
    private static final String POST_UPLOAD_URL_TEMPLATE = "https://%s.cos.%s.myqcloud.com";

    /** UTC 时间格式，用于 COS POST policy expiration */
    private static final DateTimeFormatter POLICY_EXPIRATION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final TransferManager transferManager;
    private final CosProperties cosProperties;

    /**
     * 上传文件到 COS 根目录。生成的 key 格式为 {@code {UUID}.ext}。
     *
     * @param file 上传的文件
     * @return COS 对象键
     */
    public String upload(MultipartFile file) {
        return upload(file, "");
    }

    /**
     * 创建 COS 表单直传票据。
     *
     * <p>票据只允许上传到一个精确对象键，并限制最大 Content-Length。小程序端只需要将
     * {@link PostUploadTicket#formData()} 原样传给 {@code wx.uploadFile} 的 formData。</p>
     *
     * @param objectKey 后端生成的 COS 对象键
     * @param contentType 文件 MIME 类型
     * @param maxBytes 最大允许字节数
     * @param expiresAt 票据过期时间
     * @return COS 表单直传票据
     */
    public PostUploadTicket createPostUploadTicket(
            String objectKey,
            String contentType,
            long maxBytes,
            LocalDateTime expiresAt
    ) {
        LocalDateTime now = LocalDateTime.now();
        if (expiresAt != null && expiresAt.isBefore(now)) {
            throw new IllegalArgumentException("上传票据过期时间不能早于当前时间");
        }
        LocalDateTime safeExpiresAt = expiresAt == null ? now.plusMinutes(15) : expiresAt;
        long nowEpochSecond = System.currentTimeMillis() / 1000;
        long expiresEpochSecond = safeExpiresAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        long keyTimeStart = Math.max(0L, nowEpochSecond - POST_KEY_TIME_CLOCK_SKEW_SECONDS);
        String keyTime = keyTimeStart + ";" + expiresEpochSecond;
        String policy = buildPostPolicy(objectKey, maxBytes, safeExpiresAt, keyTime);
        String encodedPolicy = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
        String signature = new COSSigner().buildPostObjectSignature(
                cosProperties.getSecretKey(),
                keyTime,
                policy
        );

        Map<String, String> formData = new LinkedHashMap<>();
        formData.put(POST_FIELD_KEY, objectKey);
        formData.put(POST_FIELD_SIGN_ALGORITHM, POST_SIGN_ALGORITHM);
        formData.put(POST_FIELD_ACCESS_KEY, cosProperties.getSecretId());
        formData.put(POST_FIELD_KEY_TIME, keyTime);
        formData.put(POST_FIELD_POLICY, encodedPolicy);
        formData.put(POST_FIELD_SIGNATURE, signature);
        formData.put(POST_FIELD_SUCCESS_STATUS, POST_SUCCESS_STATUS);

        return new PostUploadTicket(
                buildPostUploadUrl(),
                objectKey,
                contentType,
                maxBytes,
                safeExpiresAt,
                formData
        );
    }

    /**
     * 上传文件到 COS 指定文件夹。生成的 key 格式为 {@code {folderPrefix}/{UUID}.ext}。
     * folderPrefix 为空时上传到根目录。
     *
     * @param file         上传的文件
     * @param folderPrefix 文件夹路径前缀，如 "WFA3B1E7A2/work/image"
     * @return COS 对象键（含前缀路径）
     */
    public String upload(MultipartFile file, String folderPrefix) {
        String originalFilename = file.getOriginalFilename();
        String extension = extractExtension(originalFilename);
        String fileName = UUID.randomUUID().toString().replace("-", "") + extension;
        String key = buildKey(folderPrefix, fileName);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("cos-upload-", extension);
            file.transferTo(tempFile.toFile());

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(file.getContentType());
            metadata.setContentLength(file.getSize());

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    cosProperties.getBucketName(), key, tempFile.toFile());
            putObjectRequest.setMetadata(metadata);
            putObjectRequest.setCannedAcl(CannedAccessControlList.PublicRead);
            Upload upload = transferManager.upload(putObjectRequest);
            upload.waitForUploadResult();

            log.info("COS upload success: key={}, size={}, original={}", key, file.getSize(), originalFilename);
            return key;
        } catch (Exception e) {
            log.error("COS upload failed: original={}", originalFilename, e);
            throw new RuntimeException("File upload failed: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 从远程 URL 下载图片并上传到 COS 指定文件夹。
     * 生成的 key 格式为 {@code {folderPrefix}/{UUID}.ext}。
     *
     * @param imageUrl     远程图片 URL
     * @param folderPrefix 文件夹路径前缀，如 "WFA3B1E7A2/others"
     * @return COS 对象键（含前缀路径）
     */
    public String uploadFromUrl(String imageUrl, String folderPrefix) {
        Path tempFile = null;
        try {
            URL url = new URL(imageUrl);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);

            String contentType = connection.getContentType();
            String extension = extractExtensionFromContentType(contentType);

            // 将远程图片内容写入临时文件
            tempFile = Files.createTempFile("cos-url-upload-", extension);
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            long fileSize = Files.size(tempFile);
            String fileName = UUID.randomUUID().toString().replace("-", "") + extension;
            String key = buildKey(folderPrefix, fileName);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType != null ? contentType : "image/jpeg");
            metadata.setContentLength(fileSize);

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    cosProperties.getBucketName(), key, tempFile.toFile());
            putObjectRequest.setMetadata(metadata);
            putObjectRequest.setCannedAcl(CannedAccessControlList.PublicRead);
            Upload upload = transferManager.upload(putObjectRequest);
            upload.waitForUploadResult();

            log.info("COS upload from URL success: key={}, source={}, size={}", key, imageUrl, fileSize);
            return key;
        } catch (Exception e) {
            log.error("COS upload from URL failed: url={}", imageUrl, e);
            throw new RuntimeException("Image upload from URL failed: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 注册时初始化用户的 COS 文件夹结构。
     * <pre>
     * {uniqueCode}/
     *   work/
     *     image/     ← 图片类作品
     *     video/     ← 视频类作品
     *   protfolio/   ← 作品集额外素材
     *   others/      ← 头像等其它素材
     * </pre>
     * 通过创建键名为路径的空对象来模拟文件夹。
     *
     * @param uniqueCode 用户唯一码，如 "WFA3B1E7A2"
     */
    public void initUserStorage(String uniqueCode) {
        List<String> folders = List.of(
                uniqueCode + "/",
                uniqueCode + "/work/",
                uniqueCode + "/work/image/",
                uniqueCode + "/work/video/",
                uniqueCode + "/protfolio/",
                uniqueCode + "/others/"
        );

        for (String folder : folders) {
            try {
                byte[] emptyContent = new byte[0];
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(0);
                metadata.setContentType("application/x-directory");

                PutObjectRequest request = new PutObjectRequest(
                        cosProperties.getBucketName(),
                        folder,
                        new ByteArrayInputStream(emptyContent),
                        metadata
                );
                transferManager.getCOSClient().putObject(request);
                log.info("COS folder created: key={}", folder);
            } catch (Exception e) {
                log.error("COS folder creation failed: key={}", folder, e);
                throw new RuntimeException("COS folder creation failed: " + folder, e);
            }
        }

        log.info("COS user storage initialized: uniqueCode={}", uniqueCode);
    }

    /**
     * 创建团队的 COS 文件夹结构。
     * <pre>
     * {uniqueCode}/
     *   protfolio/   ← 团队作品集额外素材
     *   others/      ← 团队图标、二维码等其它素材
     * </pre>
     * 团队目录不包含 work 目录，团队作品引用成员个人作品或团队作品集素材。
     *
     * @param uniqueCode 团队唯一码，如 "TM2048"
     */
    public void initTeamStorage(String uniqueCode) {
        // COS 没有真实目录概念，仍按注册流程使用 0 字节对象模拟目录，方便控制台和后续上传定位。
        List<String> folders = List.of(
                uniqueCode + "/",
                uniqueCode + "/others/",
                uniqueCode + "/protfolio/"
        );

        for (String folder : folders) {
            try {
                byte[] emptyContent = new byte[0];
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(0);
                metadata.setContentType("application/x-directory");

                PutObjectRequest request = new PutObjectRequest(
                        cosProperties.getBucketName(),
                        folder,
                        new ByteArrayInputStream(emptyContent),
                        metadata
                );
                transferManager.getCOSClient().putObject(request);
                log.info("COS team folder created: key={}", folder);
            } catch (Exception e) {
                log.error("COS team folder creation failed: key={}", folder, e);
                throw new RuntimeException("COS team folder creation failed: " + folder, e);
            }
        }

        log.info("COS team storage initialized: uniqueCode={}", uniqueCode);
    }

    /**
     * 检查用户的 COS 文件夹结构是否已初始化。
     * 通过探测根文件夹（{@code {uniqueCode}/}）对象是否存在来判断。
     *
     * @param uniqueCode 用户唯一码
     * @return 文件夹结构是否存在
     */
    public boolean isUserStorageInitialized(String uniqueCode) {
        return transferManager.getCOSClient().doesObjectExist(
                cosProperties.getBucketName(), uniqueCode + "/");
    }

    /**
     * 拼接文件夹路径与文件名
     *
     * @param folderPrefix 文件夹前缀，可为空
     * @param fileName     文件名
     * @return 完整 COS 键
     */
    private String buildKey(String folderPrefix, String fileName) {
        if (folderPrefix == null || folderPrefix.isBlank()) {
            return fileName;
        }
        String normalized = folderPrefix.replaceAll("/+$", "");
        return normalized + "/" + fileName;
    }

    public InputStream download(String key) {
        try {
            GetObjectRequest request = new GetObjectRequest(cosProperties.getBucketName(), key);
            COSObject cosObject = transferManager.getCOSClient().getObject(request);
            log.info("COS download success: key={}", key);
            return cosObject.getObjectContent();
        } catch (Exception e) {
            log.error("COS download failed: key={}", key, e);
            throw new RuntimeException("File download failed: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 COS 对象头信息。
     *
     * @param key COS 对象键
     * @return 对象头信息
     */
    public ObjectHead headObject(String key) {
        try {
            ObjectMetadata metadata = transferManager.getCOSClient()
                    .getObjectMetadata(cosProperties.getBucketName(), key);
            log.info("COS head success: key={}, contentType={}, contentLength={}",
                    key, metadata.getContentType(), metadata.getContentLength());
            return new ObjectHead(metadata.getContentType(), metadata.getContentLength());
        } catch (Exception e) {
            log.error("COS head failed: key={}", key, e);
            throw new RuntimeException("File metadata read failed: " + e.getMessage(), e);
        }
    }

    public void delete(String key) {
        try {
            transferManager.getCOSClient().deleteObject(cosProperties.getBucketName(), key);
            log.info("COS delete success: key={}", key);
        } catch (Exception e) {
            log.error("COS delete failed: key={}", key, e);
            throw new RuntimeException("File delete failed: " + e.getMessage(), e);
        }
    }

    public String publicUrl(String key) {
        String baseUrl = cosProperties.getPublicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return key;
        }
        return baseUrl.replaceAll("/+$", "") + "/" + key.replaceAll("^/+", "");
    }

    /**
     * 构造 COS 表单上传策略。
     *
     * @param objectKey COS 对象键
     * @param maxBytes 最大允许字节数
     * @param expiresAt 过期时间
     * @param keyTime 签名时间范围
     * @return policy JSON
     */
    private String buildPostPolicy(String objectKey, long maxBytes, LocalDateTime expiresAt, String keyTime) {
        String expiration = expiresAt.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(POLICY_EXPIRATION_FORMATTER);
        Map<String, Object> policy = new LinkedHashMap<>();
        List<Object> conditions = new ArrayList<>();
        conditions.add(postPolicyCondition(POST_POLICY_BUCKET, cosProperties.getBucketName()));
        conditions.add(postPolicyCondition(POST_FIELD_KEY, objectKey));
        conditions.add(postPolicyCondition(POST_FIELD_SIGN_ALGORITHM, POST_SIGN_ALGORITHM));
        conditions.add(postPolicyCondition(POST_FIELD_ACCESS_KEY, cosProperties.getSecretId()));
        conditions.add(postPolicyCondition(POST_FIELD_SIGN_TIME, keyTime));
        conditions.add(postPolicyCondition(POST_FIELD_SUCCESS_STATUS, POST_SUCCESS_STATUS));
        conditions.add(postPolicyContentLengthRange(maxBytes));
        policy.put("expiration", expiration);
        policy.put("conditions", conditions);
        return JSON.toJSONString(policy);
    }

    /**
     * 构造 COS POST policy 的等值条件。
     *
     * @param name 条件字段名
     * @param value 条件字段值
     * @return policy 条件对象
     */
    private Map<String, String> postPolicyCondition(String name, String value) {
        Map<String, String> condition = new LinkedHashMap<>();
        condition.put(name, value);
        return condition;
    }

    /**
     * 构造 COS POST policy 的上传大小范围条件。
     *
     * @param maxBytes 最大允许字节数
     * @return policy 条件数组
     */
    private List<Object> postPolicyContentLengthRange(long maxBytes) {
        List<Object> condition = new ArrayList<>();
        condition.add(POST_POLICY_CONTENT_LENGTH_RANGE);
        condition.add(0L);
        condition.add(maxBytes);
        return condition;
    }

    /**
     * 构造 COS 表单上传地址。
     *
     * @return 上传地址
     */
    private String buildPostUploadUrl() {
        return String.format(POST_UPLOAD_URL_TEMPLATE, cosProperties.getBucketName(), cosProperties.getRegion());
    }

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        return "";
    }

    /**
     * 根据 Content-Type 提取文件扩展名。
     *
     * @param contentType HTTP 响应的 Content-Type，可为空
     * @return 文件扩展名（含点号），默认 ".jpg"
     */
    private String extractExtensionFromContentType(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        String lower = contentType.toLowerCase();
        if (lower.contains("png")) {
            return ".png";
        }
        if (lower.contains("gif")) {
            return ".gif";
        }
        if (lower.contains("webp")) {
            return ".webp";
        }
        if (lower.contains("bmp")) {
            return ".bmp";
        }
        return ".jpg";
    }

    /**
     * COS 表单直传票据。
     *
     * @param uploadUrl 表单上传地址
     * @param objectKey COS 对象键
     * @param contentType MIME 类型
     * @param maxBytes 最大允许字节数
     * @param expiresAt 过期时间
     * @param formData wx.uploadFile 表单字段
     */
    public record PostUploadTicket(
            String uploadUrl,
            String objectKey,
            String contentType,
            long maxBytes,
            LocalDateTime expiresAt,
            Map<String, String> formData
    ) {
    }

    /**
     * COS 对象头信息。
     *
     * @param contentType MIME 类型
     * @param contentLength 文件字节数
     */
    public record ObjectHead(String contentType, long contentLength) {
    }
}
