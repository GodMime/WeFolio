package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传封面任务公共校验测试 — 覆盖自引用和媒体类型两类跨服务共享规则。
 */
class WorkUploadCoverTaskValidatorTest {

    /**
     * 封面任务不能引用主任务自身。
     */
    @Test
    void ensureNotSelfReferenceShouldRejectSameTaskId() {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(99L);

        assertThatThrownBy(() -> WorkUploadCoverTaskValidator.ensureNotSelfReference(task, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缩略图或封面图必须是图片");
    }

    /**
     * 封面任务必须是图片媒体类型。
     */
    @Test
    void ensureImageCoverTaskShouldRejectNonImageTask() {
        WorkUploadTaskEntity coverTask = new WorkUploadTaskEntity();
        coverTask.setMediaType(MediaTypeDict.VIDEO.getCode());

        assertThatThrownBy(() -> WorkUploadCoverTaskValidator.ensureImageCoverTask(coverTask))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缩略图或封面图必须是图片");
    }

    /**
     * 缩略图任务必须是图片媒体类型。
     */
    @Test
    void ensureImageThumbnailTaskShouldRejectNonImageTask() {
        WorkUploadTaskEntity thumbnailTask = new WorkUploadTaskEntity();
        thumbnailTask.setMediaType(MediaTypeDict.VIDEO.getCode());

        assertThatThrownBy(() -> WorkUploadCoverTaskValidator.ensureImageThumbnailTask(thumbnailTask))
                .isInstanceOf(BusinessException.class)
                .hasMessage("缩略图或封面图必须是图片");
    }

    /**
     * 图片封面任务通过共享媒体类型校验。
     */
    @Test
    void ensureImageCoverTaskShouldAllowImageTask() {
        WorkUploadTaskEntity coverTask = new WorkUploadTaskEntity();
        coverTask.setMediaType(MediaTypeDict.IMAGE.getCode());

        assertThatCode(() -> WorkUploadCoverTaskValidator.ensureImageCoverTask(coverTask))
                .doesNotThrowAnyException();
    }

    /**
     * 两个上传服务使用同一个封面任务关系校验器，避免未来规则发散。
     */
    @Test
    void uploadServicesShouldUseSharedCoverTaskValidator() throws IOException {
        String mineWorkService = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/MineWorkService.java"));
        String transactionService = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/WorkUploadTransactionService.java"));

        assertThat(mineWorkService).contains("WorkUploadCoverTaskValidator.ensureNotSelfReference");
        assertThat(mineWorkService).contains("WorkUploadCoverTaskValidator.ensureImageCoverTask");
        assertThat(mineWorkService).contains("WorkUploadCoverTaskValidator.ensureImageThumbnailTask");
        assertThat(transactionService).contains("WorkUploadCoverTaskValidator.ensureNotSelfReference");
        assertThat(transactionService).contains("WorkUploadCoverTaskValidator.ensureImageCoverTask");
    }
}
