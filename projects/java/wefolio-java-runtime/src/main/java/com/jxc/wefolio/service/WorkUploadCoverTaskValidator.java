package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;

/**
 * 作品上传封面任务校验器 — 收敛上传确认前后共同遵守的封面任务基础规则。
 */
final class WorkUploadCoverTaskValidator {

    /** 缩略图或封面图类型错误提示 */
    static final String COVER_TASK_MEDIA_TYPE_MESSAGE = "缩略图或封面图必须是图片";

    private WorkUploadCoverTaskValidator() {
    }

    /**
     * 校验封面任务不能引用主任务自身。
     *
     * @param task 主上传任务
     * @param coverTaskId 封面任务 ID
     */
    static void ensureNotSelfReference(WorkUploadTaskEntity task, Long coverTaskId) {
        Long taskId = task == null ? null : task.getId();
        if (taskId != null && taskId.equals(coverTaskId)) {
            throw new BusinessException(COVER_TASK_MEDIA_TYPE_MESSAGE);
        }
    }

    /**
     * 校验封面任务必须是图片类型。
     *
     * @param coverTask 封面上传任务
     */
    static void ensureImageCoverTask(WorkUploadTaskEntity coverTask) {
        if (!MediaTypeDict.IMAGE.getCode().equals(coverTask.getMediaType())) {
            throw new BusinessException(COVER_TASK_MEDIA_TYPE_MESSAGE);
        }
    }
}
