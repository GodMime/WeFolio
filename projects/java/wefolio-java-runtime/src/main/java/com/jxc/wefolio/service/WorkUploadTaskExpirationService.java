package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传任务过期状态服务 — 使用独立事务保证状态不会随调用方业务异常回滚。
 */
@Service
@RequiredArgsConstructor
public class WorkUploadTaskExpirationService {

    /** 上传任务 Mapper。 */
    private final WorkUploadTaskEntityMapper workUploadTaskEntityMapper;

    /**
     * 将上传任务标记为已过期。
     *
     * @param task 上传任务
     * @param errorMessage 过期原因
     * @return 是否更新成功
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markExpired(WorkUploadTaskEntity task, String errorMessage) {
        if (task == null || task.getId() == null) {
            return false;
        }
        task.setStatus(WorkUploadTaskStatusDict.EXPIRED.getCode());
        task.setErrorMessage(errorMessage);
        return workUploadTaskEntityMapper.updateById(task) == 1;
    }
}
