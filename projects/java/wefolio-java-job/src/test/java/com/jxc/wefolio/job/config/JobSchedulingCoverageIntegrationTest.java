package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.WefolioJavaJobApplication;
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
            VirtualPaymentSchedulingConfig.TASK_SCHEDULER_BEAN_NAME
    );

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JobScheduledTaskRegistry scheduledTaskRegistry;

    @Test
    void shouldRegisterAllFiveScheduledMethodsInProtectedSchedulers() {
        long scheduledMethodCount = Arrays.stream(applicationContext.getBeanDefinitionNames())
                .map(applicationContext::getBean)
                .map(AopUtils::getTargetClass)
                .flatMap(beanClass -> Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(beanClass)))
                .flatMap(method -> Arrays.stream(method.getAnnotationsByType(Scheduled.class))
                        .map(annotation -> new ScheduledMethod(method, annotation)))
                .peek(this::assertProtectedScheduler)
                .count();

        assertThat(scheduledMethodCount).isEqualTo(5);
        assertThat(scheduledTaskRegistry.scheduledTasks()).hasSize(5);
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
