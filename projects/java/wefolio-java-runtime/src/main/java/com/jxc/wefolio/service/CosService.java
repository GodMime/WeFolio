package com.jxc.wefolio.service;

import com.jxc.wefolio.config.CosProperties;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CosService {

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

            Upload upload = transferManager.upload(
                    cosProperties.getBucketName(), key, tempFile.toFile());
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

            Upload upload = transferManager.upload(
                    cosProperties.getBucketName(), key, tempFile.toFile());
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
}
