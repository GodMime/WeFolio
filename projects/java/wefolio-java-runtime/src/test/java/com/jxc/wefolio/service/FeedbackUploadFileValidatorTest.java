package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.MineFeedbackUploadTicketRequest;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineFeedbackMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 反馈附件文件元数据校验器测试。
 */
class FeedbackUploadFileValidatorTest {

    /** 待测试的文件元数据校验器。 */
    private final FeedbackUploadFileValidator validator = new FeedbackUploadFileValidator();

    /** 合法图片和视频应归一化扩展名、MIME 与图片空时长。 */
    @Test
    void validFilesShouldNormalizeMetadata() {
        List<FeedbackUploadFileValidator.PreparedUploadFile> files = validator.prepareFiles(List.of(
                file("image-1", "现场.JPG", "IMAGE", " IMAGE/JPEG ; charset=binary", 1024L, null),
                file("video-1", "现场.MOV", "VIDEO", "VIDEO/QUICKTIME", 2048L, 32_000L)));

        assertThat(files).hasSize(2);
        assertThat(files.get(0)).satisfies(file -> {
            assertThat(file.extension()).isEqualTo("jpg");
            assertThat(file.mimeType()).isEqualTo("image/jpeg");
            assertThat(file.durationMs()).isZero();
        });
        assertThat(files.get(1)).satisfies(file -> {
            assertThat(file.extension()).isEqualTo("mov");
            assertThat(file.mimeType()).isEqualTo("video/quicktime");
            assertThat(file.durationMs()).isEqualTo(32_000L);
        });
    }

    /** M4V 不在反馈视频上传白名单内。 */
    @Test
    void m4vShouldBeRejected() {
        assertThatThrownBy(() -> validator.prepareFiles(List.of(
                file("video-m4v", "现场.m4v", "VIDEO", "video/x-m4v", 2048L, 32_000L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
    }

    /** 批次必须有 1 至 3 个文件且客户端标识不能重复。 */
    @Test
    void batchShouldRequireValidCountAndUniqueClientIds() {
        assertThatThrownBy(() -> validator.prepareFiles(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_COUNT_INVALID_MESSAGE);
        assertThatThrownBy(() -> validator.prepareFiles(List.of(
                image("same"), image("same"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_CLIENT_ID_INVALID_MESSAGE);
    }

    /** MIME 错配、越界大小和非法视频时长应使用对应业务文案拒绝。 */
    @Test
    void invalidTypeSizeOrDurationShouldBeRejected() {
        assertThatThrownBy(() -> validator.prepareFiles(List.of(
                file("mime", "photo.jpg", "IMAGE", "image/png", 1L, 0L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_TYPE_INVALID_MESSAGE);
        assertThatThrownBy(() -> validator.prepareFiles(List.of(
                file("size", "photo.jpg", "IMAGE", "image/jpeg", 10L * 1024 * 1024 + 1L, 0L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_SIZE_INVALID_MESSAGE);
        assertThatThrownBy(() -> validator.prepareFiles(List.of(
                file("duration", "clip.mp4", "VIDEO", "video/mp4", 1L, 600_001L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_FILE_DURATION_INVALID_MESSAGE);
    }

    /** 客户端文件标识必须是可打印非空 ASCII，拒绝 Unicode、emoji 和控制字符。 */
    @ParameterizedTest
    @ValueSource(strings = {"中文-client", "emoji-\uD83D\uDE00", "line\nbreak"})
    void clientIdShouldRejectNonPrintableAscii(String clientId) {
        assertThatThrownBy(() -> validator.prepareFiles(List.of(image(clientId))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MineFeedbackMessage.UPLOAD_CLIENT_ID_INVALID_MESSAGE);
    }

    /** 构造默认合法 JPG 文件声明。 */
    private MineFeedbackUploadTicketRequest.UploadFileItem image(String clientId) {
        return file(clientId, "photo.jpg", "IMAGE", "image/jpeg", 1L, 0L);
    }

    /** 构造指定值的文件声明。 */
    private MineFeedbackUploadTicketRequest.UploadFileItem file(
            String clientId,
            String fileName,
            String mediaType,
            String mimeType,
            Long fileSize,
            Long durationMs
    ) {
        MineFeedbackUploadTicketRequest.UploadFileItem file = new MineFeedbackUploadTicketRequest.UploadFileItem();
        file.setClientId(clientId);
        file.setFileName(fileName);
        file.setMediaType(mediaType);
        file.setMimeType(mimeType);
        file.setFileSize(fileSize);
        file.setDurationMs(durationMs);
        return file;
    }
}
