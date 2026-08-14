package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.service.JobExecutionLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * 为每次 Spring 调度回调统一接入后台任务生命周期门闩。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobSchedulingTaskDecorator implements TaskDecorator {

    /** 为每次调度回调提供准入和活动计数。 */
    private final JobExecutionLifecycle executionLifecycle;

    /**
     * 包装实际调度回调，使当前及未来注册的任务自动受停用状态约束。
     *
     * @param callback Spring 调度器提交的原始回调
     * @return 带有准入和凭证释放逻辑的回调
     */
    @Override
    public Runnable decorate(Runnable callback) {
        return () -> {
            boolean executed = executionLifecycle.runScheduled(callback);
            if (!executed) {
                log.info("后台调度已停用，跳过调度回调，callback={}", callback);
            }
        };
    }
}
