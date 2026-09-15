package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job 弃用接口日志覆盖守卫。
 */
class JobDeprecatedEndpointCoverageTest {

    /** Job Controller 包名。 */
    private static final String JOB_CONTROLLER_PACKAGE = "com.jxc.wefolio.job.controller";

    /**
     * Job 尚未提供弃用接口日志切面，因此不得静默新增弃用接口。
     */
    @Test
    void jobShouldNotAddDeprecatedEndpointsWithoutLoggingAspect() {
        List<Class<?>> controllerTypes = scanControllerTypes();

        assertThat(findDeprecatedEndpoints(controllerTypes))
                .as("Job 新增弃用接口前必须先补齐独立部署可用的弃用接口错误日志切面")
                .isEmpty();
    }

    /**
     * 守卫必须识别方法级和类级弃用标记，避免扫描逻辑失效后产生假通过。
     */
    @Test
    void guardShouldDetectMethodAndClassDeprecatedEndpoints() {
        assertThat(findDeprecatedEndpoints(List.of(
                MethodDeprecatedFixtureController.class,
                ClassDeprecatedFixtureController.class)))
                .containsExactly(
                        "ClassDeprecatedFixtureController#endpoint",
                        "MethodDeprecatedFixtureController#endpoint");
    }

    /** 扫描 Job 工程中的真实 Controller 类型。 */
    private List<Class<?>> scanControllerTypes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        return scanner.findCandidateComponents(JOB_CONTROLLER_PACKAGE).stream()
                .<Class<?>>map(beanDefinition -> loadClass(beanDefinition.getBeanClassName()))
                .toList();
    }

    /** 查找类级或方法级标记弃用的 HTTP 接口。 */
    private List<String> findDeprecatedEndpoints(Collection<Class<?>> controllerTypes) {
        List<String> endpoints = new ArrayList<>();
        for (Class<?> controllerType : controllerTypes) {
            boolean classDeprecated = controllerType.isAnnotationPresent(Deprecated.class);
            for (Method method : controllerType.getDeclaredMethods()) {
                if (isRequestMapping(method)
                        && (classDeprecated || method.isAnnotationPresent(Deprecated.class))) {
                    endpoints.add(controllerType.getSimpleName() + "#" + method.getName());
                }
            }
        }
        endpoints.sort(Comparator.naturalOrder());
        return endpoints;
    }

    /** 判断方法是否声明了 Spring MVC 请求映射。 */
    private boolean isRequestMapping(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    /** 按全限定名加载 Controller 类型。 */
    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("加载 Job Controller 失败: " + className, exception);
        }
    }

    /** 方法级弃用接口测试夹具。 */
    @RestController
    static class MethodDeprecatedFixtureController {

        /** 弃用测试接口。 */
        @Deprecated
        @GetMapping("/fixture/method-deprecated")
        public String endpoint() {
            return "ok";
        }
    }

    /** 类级弃用接口测试夹具。 */
    @Deprecated
    @RestController
    static class ClassDeprecatedFixtureController {

        /** 类级弃用覆盖的测试接口。 */
        @GetMapping("/fixture/class-deprecated")
        public String endpoint() {
            return "ok";
        }
    }
}
