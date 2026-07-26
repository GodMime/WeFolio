package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.common.upload.AvatarImageFormat;
import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.message.CosMessage;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.ciModel.snapshot.CosSnapshotRequest;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CosService {

    /** Java AWT 无头模式配置键 */
    private static final String JAVA_AWT_HEADLESS_PROPERTY = "java.awt.headless";

    /** 布尔真字符串 */
    private static final String TRUE_VALUE = "true";

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

    /** COS 表单对象访问权限字段 */
    private static final String POST_FIELD_ACL = "x-cos-acl";

    /** COS 表单对象 MIME 类型字段 */
    private static final String POST_FIELD_CONTENT_TYPE = "Content-Type";

    /** COS 表单上传对象访问权限：公有读私有写 */
    private static final String POST_ACL_PUBLIC_READ = CannedAccessControlList.PublicRead.toString();

    /** COS policy 存储桶字段 */
    private static final String POST_POLICY_BUCKET = "bucket";

    /** COS policy 上传大小约束字段 */
    private static final String POST_POLICY_CONTENT_LENGTH_RANGE = "content-length-range";

    /** COS 表单签名起始时间回退秒数，用于容忍服务器与 COS 的轻微时钟偏差 */
    private static final long POST_KEY_TIME_CLOCK_SKEW_SECONDS = 60L;

    /** 小程序合法上传域名默认值 */
    private static final String DEFAULT_POST_UPLOAD_BASE_URL = "https://cos.we-folio.dingchenyong.top";

    /** 腾讯云 COS 源站域名后缀，未加入小程序 uploadFile 合法域名 */
    private static final String TENCENT_COS_SOURCE_DOMAIN_SUFFIX = ".myqcloud.com";

    /** URL 末尾斜杠匹配表达式 */
    private static final String TRAILING_SLASH_REGEX = "/+$";

    /** 数据万象截帧输出格式 */
    private static final String SNAPSHOT_FORMAT = "jpg";

    /** 数据万象截帧输出 MIME */
    private static final String SNAPSHOT_CONTENT_TYPE = "image/jpeg";

    /** 数据万象截帧默认宽度，控制封面体积 */
    private static final int SNAPSHOT_DEFAULT_WIDTH = 640;

    /** 数据万象截帧默认高度，控制封面体积 */
    private static final int SNAPSHOT_DEFAULT_HEIGHT = 640;

    /** 视频封面最大字节数 */
    private static final int SNAPSHOT_MAX_BYTES = 100 * 1024;

    /** 视频封面压缩质量梯度 */
    private static final float[] SNAPSHOT_COMPRESS_QUALITIES = {
            0.85f, 0.75f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f
    };

    /** 视频封面压缩尺寸梯度 */
    private static final double[] SNAPSHOT_COMPRESS_SCALES = {
            1D, 0.85D, 0.7D, 0.55D, 0.4D, 0.3D, 0.25D
    };

    /** SHA-256 摘要算法名称 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 未识别文件类型的默认 MIME */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** 毫秒转秒的除数 */
    private static final double MILLIS_PER_SECOND = 1000D;

    /** UTC 时间格式，用于 COS POST policy expiration */
    private static final DateTimeFormatter POLICY_EXPIRATION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final TransferManager transferManager;
    private final CosProperties cosProperties;

    static {
        if (System.getProperty(JAVA_AWT_HEADLESS_PROPERTY) == null) {
            System.setProperty(JAVA_AWT_HEADLESS_PROPERTY, TRUE_VALUE);
        }
    }

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
        String normalizedContentType = contentType == null ? "" : contentType.trim();
        String policy = buildPostPolicy(objectKey, normalizedContentType, maxBytes, safeExpiresAt, keyTime);
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
        formData.put(POST_FIELD_ACL, POST_ACL_PUBLIC_READ);
        if (!normalizedContentType.isBlank()) {
            formData.put(POST_FIELD_CONTENT_TYPE, normalizedContentType);
        }

        return new PostUploadTicket(
                buildPostUploadUrl(),
                objectKey,
                normalizedContentType,
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
        return uploadToObjectKey(file, key);
    }

    /**
     * 上传文件到指定 COS 对象键。
     *
     * @param file 上传文件
     * @param objectKey 完整 COS 对象键
     * @return 实际上传的 COS 对象键
     */
    public String uploadToObjectKey(MultipartFile file, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException(CosMessage.OBJECT_KEY_REQUIRED_MESSAGE);
        }
        String key = objectKey.strip();
        String originalFilename = file.getOriginalFilename();
        String extension = extractExtension(originalFilename);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("cos-upload-", extension);
            file.transferTo(tempFile.toFile());

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(resolveUploadContentType(file, key));
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

    /**
     * 使用腾讯云数据万象从视频截取一帧并写回 COS。
     *
     * @param sourceKey 视频对象键
     * @param targetKey 封面图对象键
     * @param frameTimeMs 截帧时间点，单位毫秒
     * @return 已写入的封面对象信息
     */
    public SnapshotObject snapshotVideoFrameToObject(String sourceKey, String targetKey, long frameTimeMs) {
        return snapshotVideoFrameToObject(
                sourceKey,
                targetKey,
                frameTimeMs,
                SNAPSHOT_DEFAULT_WIDTH,
                SNAPSHOT_DEFAULT_HEIGHT);
    }

    /**
     * 使用腾讯云数据万象从视频截取一帧并写回 COS。
     *
     * @param sourceKey 视频对象键
     * @param targetKey 封面图对象键
     * @param frameTimeMs 截帧时间点，单位毫秒
     * @param snapshotWidth 截帧输出宽度
     * @param snapshotHeight 截帧输出高度
     * @return 已写入的封面对象信息
     */
    public SnapshotObject snapshotVideoFrameToObject(
            String sourceKey,
            String targetKey,
            long frameTimeMs,
            Integer snapshotWidth,
            Integer snapshotHeight
    ) {
        try {
            String bucketName = cosProperties.getBucketName();
            String snapshotTime = formatSnapshotTime(frameTimeMs);
            int outputWidth = normalizeSnapshotDimension(snapshotWidth, SNAPSHOT_DEFAULT_WIDTH);
            int outputHeight = normalizeSnapshotDimension(snapshotHeight, SNAPSHOT_DEFAULT_HEIGHT);
            CosSnapshotRequest snapshotRequest = new CosSnapshotRequest();
            snapshotRequest.setBucketName(bucketName);
            snapshotRequest.setObjectKey(sourceKey);
            snapshotRequest.setTime(snapshotTime);
            snapshotRequest.setFormat(SNAPSHOT_FORMAT);
            snapshotRequest.setWidth(String.valueOf(outputWidth));
            snapshotRequest.setHeight(String.valueOf(outputHeight));
            log.info("COS video snapshot request: bucketName={}, sourceKey={}, targetKey={}, frameTimeMs={}, snapshotTime={}, format={}, width={}, height={}",
                    bucketName, sourceKey, targetKey, frameTimeMs, snapshotTime, SNAPSHOT_FORMAT, outputWidth, outputHeight);

            byte[] imageBytes;
            try (InputStream snapshotStream = transferManager.getCOSClient().getSnapshot(snapshotRequest)) {
                imageBytes = snapshotStream.readAllBytes();
            }
            if (imageBytes.length == 0) {
                throw new RuntimeException("snapshot body is empty");
            }
            byte[] uploadBytes = compressSnapshotIfNeeded(imageBytes, sourceKey, targetKey);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(SNAPSHOT_CONTENT_TYPE);
            metadata.setContentLength(uploadBytes.length);
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName,
                    targetKey,
                    new ByteArrayInputStream(uploadBytes),
                    metadata);
            putObjectRequest.setCannedAcl(CannedAccessControlList.PublicRead);
            transferManager.getCOSClient().putObject(putObjectRequest);

            String sha256 = sha256Hex(uploadBytes);
            log.info("COS video snapshot success: sourceKey={}, targetKey={}, timeMs={}, originalSize={}, finalSize={}",
                    sourceKey, targetKey, frameTimeMs, imageBytes.length, uploadBytes.length);
            return new SnapshotObject(targetKey, SNAPSHOT_CONTENT_TYPE, uploadBytes.length, sha256);
        } catch (Exception e) {
            log.error("COS video snapshot failed: sourceKey={}, targetKey={}, timeMs={}, width={}, height={}",
                    sourceKey, targetKey, frameTimeMs, snapshotWidth, snapshotHeight, e);
            throw new RuntimeException("Video snapshot failed: " + e.getMessage(), e);
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
        return baseUrl.replaceAll(TRAILING_SLASH_REGEX, "") + "/" + key.replaceAll("^/+", "");
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
    private String buildPostPolicy(
            String objectKey,
            String contentType,
            long maxBytes,
            LocalDateTime expiresAt,
            String keyTime
    ) {
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
        conditions.add(postPolicyCondition(POST_FIELD_ACL, POST_ACL_PUBLIC_READ));
        if (contentType != null && !contentType.isBlank()) {
            conditions.add(postPolicyCondition(POST_FIELD_CONTENT_TYPE, contentType));
        }
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
        String uploadBaseUrl = cosProperties.getUploadBaseUrl();
        String normalizedUploadBaseUrl = uploadBaseUrl == null ? "" : uploadBaseUrl.trim();
        if (!normalizedUploadBaseUrl.isBlank() && !isTencentCosSourceDomain(normalizedUploadBaseUrl)) {
            return normalizedUploadBaseUrl.replaceAll(TRAILING_SLASH_REGEX, "");
        }
        return DEFAULT_POST_UPLOAD_BASE_URL;
    }

    /**
     * 判断配置的上传域名是否为腾讯云 COS 源站域名。
     *
     * @param uploadBaseUrl 上传域名配置
     * @return 是否为 COS 源站域名
     */
    private boolean isTencentCosSourceDomain(String uploadBaseUrl) {
        try {
            String host = URI.create(uploadBaseUrl).getHost();
            return host != null && host.endsWith(TENCENT_COS_SOURCE_DOMAIN_SUFFIX);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 格式化数据万象截帧时间。
     *
     * @param frameTimeMs 毫秒时间点
     * @return 秒格式时间，保留三位小数
     */
    private String formatSnapshotTime(long frameTimeMs) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0L, frameTimeMs) / MILLIS_PER_SECOND);
    }

    /**
     * 归一化数据万象截帧输出尺寸。
     *
     * @param value 请求尺寸
     * @param fallback 默认尺寸
     * @return 可提交给数据万象的尺寸
     */
    private int normalizeSnapshotDimension(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    /**
     * 视频封面超过限制时进行内存压缩。
     *
     * @param imageBytes 数据万象返回的原始图片字节
     * @param sourceKey 视频对象键
     * @param targetKey 封面对象键
     * @return 可上传的图片字节
     */
    private byte[] compressSnapshotIfNeeded(byte[] imageBytes, String sourceKey, String targetKey) throws IOException {
        if (imageBytes.length <= SNAPSHOT_MAX_BYTES) {
            return imageBytes;
        }
        BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (sourceImage == null) {
            throw new IOException("数据万象截帧图片解码失败");
        }
        byte[] bestBytes = imageBytes;
        for (double scale : SNAPSHOT_COMPRESS_SCALES) {
            BufferedImage scaledImage = scaleSnapshotImage(sourceImage, scale);
            for (float quality : SNAPSHOT_COMPRESS_QUALITIES) {
                byte[] compressedBytes = writeJpeg(scaledImage, quality);
                if (compressedBytes.length < bestBytes.length) {
                    bestBytes = compressedBytes;
                }
                if (compressedBytes.length <= SNAPSHOT_MAX_BYTES) {
                    log.info("COS video snapshot compressed: sourceKey={}, targetKey={}, originalSize={}, compressedSize={}, limit={}, quality={}, scale={}",
                            sourceKey,
                            targetKey,
                            imageBytes.length,
                            compressedBytes.length,
                            SNAPSHOT_MAX_BYTES,
                            quality,
                            scale);
                    return compressedBytes;
                }
            }
        }
        log.warn("COS video snapshot compression still exceeds limit: sourceKey={}, targetKey={}, originalSize={}, bestSize={}, limit={}",
                sourceKey, targetKey, imageBytes.length, bestBytes.length, SNAPSHOT_MAX_BYTES);
        throw new IOException("视频封面压缩后仍超过 100KB");
    }

    /**
     * 按比例缩放封面图并转换为 JPEG 可写入的 RGB 图。
     *
     * @param sourceImage 原始图片
     * @param scale 缩放比例
     * @return RGB 图片
     */
    private BufferedImage scaleSnapshotImage(BufferedImage sourceImage, double scale) {
        int width = Math.max(1, (int) Math.round(sourceImage.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(sourceImage.getHeight() * scale));
        BufferedImage targetImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = targetImage.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(sourceImage, 0, 0, width, height, null);
            return targetImage;
        } finally {
            graphics.dispose();
        }
    }

    /**
     * 按指定质量写出 JPEG。
     *
     * @param image 图片
     * @param quality 压缩质量
     * @return JPEG 字节
     */
    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(SNAPSHOT_FORMAT).next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), param);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * 计算字节内容 SHA-256。
     *
     * @param bytes 原始字节
     * @return 小写十六进制 SHA-256
     */
    private String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        return "";
    }

    /** 根据请求元数据或对象键解析上传文件 MIME。 */
    private String resolveUploadContentType(MultipartFile file, String objectKey) {
        String avatarContentType = AvatarImageFormat.fromFileName(objectKey)
                .map(AvatarImageFormat::contentType)
                .orElse(null);
        if (avatarContentType != null) {
            return avatarContentType;
        }
        String suppliedContentType = file.getContentType();
        if (suppliedContentType != null && !suppliedContentType.isBlank()) {
            return suppliedContentType.strip();
        }
        String inferredContentType = URLConnection.guessContentTypeFromName(objectKey);
        return inferredContentType == null ? DEFAULT_CONTENT_TYPE : inferredContentType;
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

    /**
     * 数据万象截帧写入后的对象信息。
     *
     * @param objectKey COS 对象键
     * @param contentType MIME 类型
     * @param contentLength 文件字节数
     * @param sha256 文件 SHA-256
     */
    public record SnapshotObject(String objectKey, String contentType, long contentLength, String sha256) {
    }
}
