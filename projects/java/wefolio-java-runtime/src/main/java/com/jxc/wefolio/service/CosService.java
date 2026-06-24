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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CosService {

    private final TransferManager transferManager;
    private final CosProperties cosProperties;

    public String upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String extension = extractExtension(originalFilename);
        String key = UUID.randomUUID().toString().replace("-", "") + extension;

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
}
