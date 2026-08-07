package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 上传任务过期状态独立事务测试。 */
class WorkUploadTaskExpirationServiceTest {

    /** 过期状态必须在独立事务提交，不能随调用方异常回滚。 */
    @Test
    void markExpiredShouldUseRequiresNewAndPersistExpiredState() throws Exception {
        Class<?> serviceClass = Class.forName("com.jxc.wefolio.service.WorkUploadTaskExpirationService");
        Method method = serviceClass.getMethod("markExpired", WorkUploadTaskEntity.class, String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        WorkUploadTaskEntityMapper mapper = mock(WorkUploadTaskEntityMapper.class);
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(91L);
        task.setStatus(WorkUploadTaskStatusDict.CREATED.getCode());
        when(mapper.updateById(task)).thenReturn(1);
        Object service = serviceClass.getConstructor(WorkUploadTaskEntityMapper.class).newInstance(mapper);

        Object updated = method.invoke(service, task, "上传任务已过期");

        assertThat(updated).isEqualTo(true);
        assertThat(task.getStatus()).isEqualTo(WorkUploadTaskStatusDict.EXPIRED.getCode());
        assertThat(task.getErrorMessage()).isEqualTo("上传任务已过期");
        verify(mapper).updateById(task);
    }
}
