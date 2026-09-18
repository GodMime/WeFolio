package com.jxc.wefolio.service.miniappcode;

import com.jxc.wefolio.config.CosProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMiniappCodeImageMessage;
import com.jxc.wefolio.service.CosService;
import lombok.RequiredArgsConstructor;
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
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 管理官方原码对象，并仅根据所属资料地址构造客户端头像元数据。 */
@Service
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
    /** 对头像地址及原码对象版本生成稳定摘要。 */
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
    /** 当前公开域名仅用于识别可附加固定图片处理参数的自有地址。 */
    private final CosProperties properties;

    /** 头像只使用所属资料元数据，不探测远端可用性，也不因历史地址阻断原码。 */
    public AvatarResource avatarResource(PortfolioMiniappCodeSnapshot snapshot) {
        String avatarUrl = snapshot.avatarUrl();
        if (avatarUrl == null || avatarUrl.isBlank() || PERSONAL_DEFAULT_URL.equals(avatarUrl)) {
            return new AvatarResource("", PortfolioOwnerTypeDict.TEAM.getCode().equals(snapshot.ownerType())
                    ? TEAM_DEFAULT_VERSION : PERSONAL_DEFAULT_VERSION);
        }
        String version = avatarVersion(avatarUrl);
        String url = supportsAvatarTransformation(avatarUrl, snapshot.uniqueCode())
                ? avatarUrl + AVATAR_RULE + version : avatarUrl;
        return new AvatarResource(url, version);
    }

    /** 头像地址版本参与名片缓存，正常上传以新对象地址触发更新，不探测同址覆盖。 */
    private String avatarVersion(String avatarUrl) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(avatarUrl.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 Java 必需算法，缺失表示运行环境异常，与用户头像无关。
            throw new IllegalStateException(exception);
        }
    }

    /** 头像元数据仅包含地址和稳定版本，不携带图片二进制。 */
    public record AvatarResource(String url, String version) { }

    /** 只给当前自有无参数地址附加转换；其余历史资料地址保持原样，不作为校验门槛。 */
    private boolean supportsAvatarTransformation(String url, String uniqueCode) {
        String publicBaseUrl = properties.getPublicBaseUrl();
        if (publicBaseUrl == null || publicBaseUrl.isBlank() || uniqueCode == null || uniqueCode.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            URI base = URI.create(publicBaseUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().equalsIgnoreCase(base.getHost())
                    || uri.getPort() != base.getPort() || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getRawPath().contains("%") || uri.getPath().contains("..")
                    || !uri.normalize().equals(uri)) return false;
            String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
            String prefix = basePath + "/" + uniqueCode + AVATAR_FOLDER;
            if (!uri.getPath().startsWith(prefix)) return false;
            String tail = uri.getPath().substring(prefix.length());
            return tail.matches("[a-zA-Z0-9._/-]+") && !tail.isBlank() && !tail.contains("//");
        } catch (IllegalArgumentException exception) {
            // 历史资料可能不符合当前 URI 规范，仍交由客户端按现有图片加载方式处理。
            return false;
        }
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
