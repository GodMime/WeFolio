package com.jxc.wefolio.job.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.jxc.wefolio.job.WefolioJavaJobApplication;
import com.jxc.wefolio.job.service.VideoFrameBackfillExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全部 Spring 定时任务均受安全停用机制保护的完整性测试。
 */
@SpringBootTest(classes = WefolioJavaJobApplication.class)
@ActiveProfiles("test")
class JobSchedulingCoverageIntegrationTest {

    private static final Set<String> PROTECTED_SCHEDULERS = Set.of(
            "",
            VirtualPaymentSchedulingConfig.DEFAULT_TASK_SCHEDULER_BEAN_NAME,
            VirtualPaymentSchedulingConfig.TASK_SCHEDULER_BEAN_NAME,
            FeedbackUploadCleanupSchedulingConfig.TASK_SCHEDULER_BEAN_NAME,
            PortfolioAiSchedulingConfig.SCHEDULER
    );

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JobScheduledTaskRegistry scheduledTaskRegistry;

    @Test
    void shouldRegisterAllNineScheduledMethodsIncludingSnapshotCleanupInProtectedSchedulers() {
        long scheduledMethodCount = Arrays.stream(applicationContext.getBeanDefinitionNames())
                .map(applicationContext::getBean)
                .map(AopUtils::getTargetClass)
                .flatMap(beanClass -> Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(beanClass)))
                .flatMap(method -> Arrays.stream(method.getAnnotationsByType(Scheduled.class))
                        .map(annotation -> new ScheduledMethod(method, annotation)))
                .peek(this::assertProtectedScheduler)
                .count();

        applicationContext.getBeansOfType(ThreadPoolTaskScheduler.class)
                .values().forEach(scheduler -> assertThat(scheduler).isInstanceOf(JobLifecycleTaskScheduler.class));
        // 历史截图扫尾也必须进入同一注册器，随全部后台任务接受停用和排空保护。
        assertThat(scheduledMethodCount).isEqualTo(9);
        assertThat(scheduledTaskRegistry.scheduledTasks()).hasSize(9)
                .extracting(task -> task.getTask().getRunnable().toString())
                .contains(VideoFrameBackfillExecutionService.class.getName() + ".cleanupFinishedSnapshots");
    }

    private void assertProtectedScheduler(ScheduledMethod scheduledMethod) {
        Method method = scheduledMethod.method();
        assertThat(scheduledMethod.annotation().scheduler())
                .as("%s#%s 的 scheduler 必须接入统一生命周期装饰器",
                        method.getDeclaringClass().getName(), method.getName())
                .isIn(PROTECTED_SCHEDULERS);
    }

    /**
     * 定时方法与单条可重复调度注解的对应关系。
     *
     * @param method 定时方法
     * @param annotation 调度注解
     */
    private record ScheduledMethod(Method method, Scheduled annotation) {
    }
}
