package com.jxc.wefolio.service.miniappcode;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMiniappCodeImageMessage;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import com.qcloud.cos.exception.CosServiceException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** 将可信头像 URL 映射为 COS 对象，始终禁止任意 HTTP 地址读取。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MiniappCodeObjectStore {
    /** 项目现有系统默认个人头像。 */
    private static final String PERSONAL_DEFAULT_URL = "https://cdn2.we-folio.dingchenyong.top/system/wefolio-default-avatar-512.jpg";
    /** 客户端打包的默认个人头像版本。 */
    private static final String PERSONAL_DEFAULT_VERSION = "personal-default-v1";
    /** 客户端打包的默认团队图标版本。 */
    private static final String TEAM_DEFAULT_VERSION = "team-default-v1";
    /** 固定首帧缩略 PNG 规则，编码后的大于号避免客户端 URL 解析差异。 */
    private static final String AVATAR_RULE = "?imageMogr2/frame/1/thumbnail/512x512%3E/strip/format/png&v=";
    /** 已有头像上传支持的 MIME 类型。 */
    private static final Set<String> AVATAR_TYPES = Set.of("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp");
    /** 地址未通过服务端归属校验。 */
    private static final String STAGE_URL = "URL_INVALID";
    /** 对象头读取发生远端异常。 */
    private static final String STAGE_HEAD = "HEAD_FAILED";
    /** 对象头明确返回不存在。 */
    private static final String STAGE_MISSING = "HEAD_MISSING";
    /** MIME 不属于已有头像支持类型。 */
    private static final String STAGE_MIME = "MIME_INVALID";
    /** 头像长度不满足限制。 */
    private static final String STAGE_SIZE = "SIZE_INVALID";
    /** 对象缺失用于内容缓存的版本信息。 */
    private static final String STAGE_ETAG = "ETAG_MISSING";
    /** 不记录原始 MIME 和参数，未知值仅记录固定分类。 */
    private static final String MIME_UNKNOWN = "unknown";
    /** 已读取但不支持的 MIME 日志分类。 */
    private static final String MIME_UNSUPPORTED = "unsupported";
    /** 版本摘要算法，不将原始 ETag 拼接进 URL。 */
    private static final String HASH_ALGORITHM = "SHA-256";
    /** PNG MIME。 */
    private static final String PNG_CONTENT_TYPE = "image/png";
    /** JPEG MIME 与扩展名。 */
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    /** PNG 扩展名。 */
    static final String PNG_SUFFIX = ".png";
    /** JPEG 扩展名。 */
    static final String JPEG_SUFFIX = ".jpg";
    /** CDN 内容版本查询参数。 */
    private static final String VERSION_QUERY = "?v=";
    /** 内存上传表单字段。 */
    private static final String FILE_FIELD = "image";
    /** 内存上传文件名。 */
    private static final String FILE_NAME = "miniapp-code";
    /** 头像所在目录。 */
    private static final String AVATAR_FOLDER = "/others/";
    /** 对象存储操作。 */
    private final CosService cos;
    /** 可接受的公开域名来自服务端 COS 配置。 */
    private final CosProperties properties;

    /** 仅读取对象头以识别原址覆盖；默认头像完全由设备端加载。 */
    public AvatarResource avatarResource(PortfolioMiniappCodeSnapshot snapshot) {
        String stage = STAGE_URL;
        String safeMime = MIME_UNKNOWN;
        long contentLength = -1;
        try {
            String avatarUrl = snapshot.avatarUrl();
            if (avatarUrl == null || avatarUrl.isBlank() || PERSONAL_DEFAULT_URL.equals(avatarUrl)) {
                return new AvatarResource("", PortfolioOwnerTypeDict.TEAM.getCode().equals(snapshot.ownerType())
                        ? TEAM_DEFAULT_VERSION : PERSONAL_DEFAULT_VERSION);
            }
            String key = avatarKey(avatarUrl, snapshot.uniqueCode());
            stage = STAGE_HEAD;
            CosService.VersionedObjectHead head = cos.headObjectVersion(key);
            if (head == null) {
                stage = STAGE_MISSING;
                throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
            }
            contentLength = head.contentLength();
            stage = STAGE_MIME;
            safeMime = MIME_UNSUPPORTED;
            // 历史上传可能保留 MIME 参数或 JPEG 别名，与既有资料接口的支持范围一致。
            MediaType mediaType = MediaType.parseMediaType(head.contentType() == null ? "" : head.contentType().strip());
            String normalizedMime = mediaType.getType() + "/" + mediaType.getSubtype();
            if (!AVATAR_TYPES.contains(normalizedMime)) {
                throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
            }
            safeMime = normalizedMime;
            stage = STAGE_SIZE;
            if (contentLength <= 0 || contentLength > MiniappCodeImages.MAX_BYTES) {
                throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
            }
            stage = STAGE_ETAG;
            if (head.eTag() == null || head.eTag().isBlank()) {
                throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
            }
            String version = HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(head.eTag().getBytes(StandardCharsets.UTF_8)));
            return new AvatarResource(avatarUrl + AVATAR_RULE + version, version);
        } catch (Exception exception) {
            int status = exception instanceof CosServiceException cosException ? cosException.getStatusCode() : 0;
            // 不记录地址、对象键、用户标识、ETag、异常消息及堆栈，避免泄露身份与凭证。
            log.warn("小程序码头像资源失败 stage={} mime={} bytes={} status={} exception={}",
                    stage, safeMime, contentLength, status, exception.getClass().getSimpleName());
            throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
        }
    }

    /** 头像元数据，版本参与整张名片缓存，不携带任何图片二进制。 */
    public record AvatarResource(String url, String version) { }

    /** 精确校验域名、端口和路径，拒绝编码分隔符、跳转参数与跨所属方对象。 */
    private String avatarKey(String url, String uniqueCode) {
        URI uri = URI.create(url);
        URI base = URI.create(properties.getPublicBaseUrl());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().equalsIgnoreCase(base.getHost())
                || uri.getPort() != base.getPort() || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getRawPath().contains("%") || uri.getPath().contains("..")
                || !uri.normalize().equals(uri)) throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
        String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        String prefix = basePath + "/" + uniqueCode + AVATAR_FOLDER;
        if (!uri.getPath().startsWith(prefix)) throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
        String tail = uri.getPath().substring(prefix.length());
        if (!tail.matches("[a-zA-Z0-9._/-]+") || tail.isBlank() || tail.contains("//")) throw new BusinessException(PortfolioMiniappCodeImageMessage.AVATAR_FAILED);
        return uri.getPath().substring(basePath.length() + 1);
    }

    /** 仅 HEAD 检查原码；只将确认不存在或格式不匹配视为可重建。 */
    public StoredCode inspect(String key) {
        try {
            var head = cos.headObjectVersion(key);
            if (head == null) return null;
            String expectedType = key.endsWith(PNG_SUFFIX) ? PNG_CONTENT_TYPE : JPEG_CONTENT_TYPE;
            if (!expectedType.equalsIgnoreCase(head.contentType()) || head.contentLength() <= 0
                    || head.contentLength() > MiniappCodeImages.MAX_BYTES || head.eTag() == null || head.eTag().isBlank()) return null;
            return new StoredCode(key, head.eTag(), head.contentType(), head.lastModified());
        } catch (RuntimeException exception) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
        }
    }

    /** 原始二进制直接保存，避免额外解码重编码，扩展名必须与微信真实格式一致。 */
    public StoredCode put(String baseKey, byte[] bytes) {
        String suffix = bytes.length >= 3 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xd8 && bytes[2] == (byte)0xff
                ? JPEG_SUFFIX : PNG_SUFFIX;
        String type = JPEG_SUFFIX.equals(suffix) ? JPEG_CONTENT_TYPE : PNG_CONTENT_TYPE;
        String key = baseKey + suffix;
        try {
            cos.uploadToObjectKey(new CodeFile(bytes, type, suffix), key);
            StoredCode code = inspect(key);
            if (code == null) throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
            return code;
        } catch (RuntimeException exception) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
        }
    }

    /** 以当前对象版本避开确定性路径重建后的旧 CDN 内容。 */
    public String url(StoredCode code) {
        try {
            String version = HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(code.eTag().getBytes(StandardCharsets.UTF_8)));
            return cos.publicUrl(code.key()) + VERSION_QUERY + version;
        } catch (Exception exception) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED);
        }
    }

    /** Redis 仅保存已校验原码元数据，不存放二进制。 */
    public record StoredCode(String key, String eTag, String contentType, long lastModified) { }

    /** 原码二进制适配现有 COS 上传入口，不引入测试库。 */
    private record CodeFile(byte[] data, String contentType, String suffix) implements MultipartFile {
        /** 表单名称。 */
        @Override public String getName() { return FILE_FIELD; }
        /** 依据实际图片格式构造文件名。 */
        @Override public String getOriginalFilename() { return FILE_NAME + suffix; }
        /** 文件类型。 */
        @Override public String getContentType() { return contentType; }
        /** 是否空文件。 */
        @Override public boolean isEmpty() { return data.length == 0; }
        /** 文件字节数。 */
        @Override public long getSize() { return data.length; }
        /** 获取数据。 */
        @Override public byte[] getBytes() { return data.clone(); }
        /** 打开独立输入流。 */
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        /** 按现有上传服务要求落临时文件。 */
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), data); }
    }
}
