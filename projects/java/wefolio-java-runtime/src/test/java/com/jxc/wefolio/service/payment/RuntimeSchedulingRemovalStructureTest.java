package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.WefolioJavaRuntimeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runtime 微信虚拟支付自动触发移除与单字段租约切换结构测试。
 */
class RuntimeSchedulingRemovalStructureTest {

    /** 微信虚拟支付服务包。 */
    private static final String PAYMENT_SERVICE_PACKAGE = "com.jxc.wefolio.service.payment";

    /** Runtime 支付组件不得注册定时扫描或应用启动扫描。 */
    @Test
    void runtimePaymentComponentsShouldNotRegisterScheduledOrStartupTaskTriggers() throws Exception {
        List<Class<?>> components = paymentComponents();

        assertThat(methodNamesWithAnnotation(components, Scheduled.class))
                .as("Runtime 支付组件中的定时任务")
                .isEmpty();
        assertThat(applicationReadyListenerNames(components))
                .as("Runtime 支付组件中的应用启动任务")
                .isEmpty();
    }

    /** 赠送订单创建后不得由 Runtime 事件监听器直接执行。 */
    @Test
    void giftOrderCreationShouldNotRegisterRuntimeDirectExecutionListener() throws Exception {
        List<String> listeners = paymentComponents().stream()
                .flatMap(component -> Arrays.stream(component.getDeclaredMethods()))
                .filter(method -> AnnotatedElementUtils.findMergedAnnotation(
                        method, TransactionalEventListener.class) != null)
                .filter(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> "PointGiftOrderCreatedEvent".equals(type.getSimpleName())))
                .map(this::methodName)
                .toList();

        assertThat(listeners)
                .as("Runtime 赠送订单提交后直调监听器")
                .isEmpty();
    }

    /** Runtime 已无定时任务后不得继续启用调度基础设施。 */
    @Test
    void runtimeApplicationShouldNotEnableScheduling() {
        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                WefolioJavaRuntimeApplication.class, EnableScheduling.class))
                .isNull();
    }

    /** Runtime 不得重新引入两个历史扫描器。 */
    @Test
    void runtimeShouldNotContainVirtualPaymentScannerClasses() {
        assertThatThrownBy(() -> Class.forName(
                "com.jxc.wefolio.service.payment.PointGiftOrderScanner"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.jxc.wefolio.service.payment.PointDebitTaskScanner"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    /** Release B 生产逻辑只能读写新执行租约令牌，实体兼容字段暂时保留。 */
    @Test
    void runtimeProductionLogicShouldOnlyUseExecutionLeaseToken() throws Exception {
        Path mainJava = Path.of("src/main/java");
        List<Path> productionFiles;
        try (var paths = Files.walk(mainJava)) {
            productionFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of(
                            "com/jxc/wefolio/entity/PointGiftOrderEntity.java")))
                    .filter(path -> !path.endsWith(Path.of(
                            "com/jxc/wefolio/entity/PointDebitTaskEntity.java")))
                    .toList();
        }

        String productionSource = readSources(productionFiles);
        assertThat(productionSource)
                .doesNotContain("lease_owner")
                .doesNotContain("getLeaseOwner")
                .doesNotContain("setLeaseOwner");
    }

    /** 读取一组生产源码，供结构门禁统一检查。 */
    private String readSources(List<Path> paths) {
        StringBuilder source = new StringBuilder();
        for (Path path : paths) {
            try {
                source.append(Files.readString(path)).append('\n');
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("无法读取 Runtime 生产源码：" + path, exception);
            }
        }
        return source.toString();
    }

    /** 扫描支付包中的全部 Spring 组件类型。 */
    private List<Class<?>> paymentComponents() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        return scanner.findCandidateComponents(PAYMENT_SERVICE_PACKAGE).stream()
                .<Class<?>>map(definition -> loadClass(definition.getBeanClassName()))
                .toList();
    }

    /** 加载扫描到的组件类型。 */
    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("无法加载 Runtime 支付组件：" + className, exception);
        }
    }

    /** 查询标记指定注解的方法名称。 */
    private List<String> methodNamesWithAnnotation(
            List<Class<?>> components,
            Class<? extends java.lang.annotation.Annotation> annotationType
    ) {
        return components.stream()
                .flatMap(component -> Arrays.stream(component.getDeclaredMethods()))
                .filter(method -> AnnotatedElementUtils.findMergedAnnotation(method, annotationType) != null)
                .map(this::methodName)
                .toList();
    }

    /** 查询监听应用启动完成事件的方法名称。 */
    private List<String> applicationReadyListenerNames(List<Class<?>> components) {
        return components.stream()
                .flatMap(component -> Arrays.stream(component.getDeclaredMethods()))
                .filter(method -> {
                    EventListener listener = AnnotatedElementUtils.findMergedAnnotation(
                            method, EventListener.class);
                    return listener != null
                            && Arrays.asList(listener.value()).contains(ApplicationReadyEvent.class);
                })
                .map(this::methodName)
                .toList();
    }

    /** 生成便于失败定位的方法名称。 */
    private String methodName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
