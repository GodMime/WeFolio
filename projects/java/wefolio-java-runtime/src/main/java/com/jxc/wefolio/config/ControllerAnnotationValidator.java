package com.jxc.wefolio.config;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 控制器注解校验器 — 启动时扫描所有 Controller 方法，
 * 确保每个方法（或其所在类）都标记了四类访问控制注解之一：
 * {@link LoginAccess}、{@link SystemAccess}、{@link MaintainerAccess}、{@link VisitorAccess}。
 * <p>
 * 若存在未标记的接口方法，启动时抛出异常阻止应用启动。
 */
@Slf4j
@Component
public class ControllerAnnotationValidator {

    /** 四类访问控制注解 */
    private static final List<Class<? extends Annotation>> ACCESS_ANNOTATIONS = List.of(
            LoginAccess.class,
            SystemAccess.class,
            MaintainerAccess.class,
            VisitorAccess.class
    );

    /** HTTP 方法映射注解 */
    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class,
            GetMapping.class,
            PostMapping.class,
            PutMapping.class,
            DeleteMapping.class,
            PatchMapping.class
    );

    /** Spring 应用上下文 */
    private final ApplicationContext applicationContext;

    /**
     * 构造函数 — 注入 Spring 上下文。
     *
     * @param applicationContext Spring 应用上下文
     */
    public ControllerAnnotationValidator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 应用启动就绪后校验所有 Controller 方法均已标记访问控制注解。
     * <p>
     * 使用 {@link ApplicationReadyEvent} 而非 Bean 后置处理器，
     * 确保 AOP 代理等基础设施已全部就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        Map<String, Object> controllers = applicationContext.getBeansWithAnnotation(RestController.class);
        List<String> unannotated = new ArrayList<>();

        for (Map.Entry<String, Object> entry : controllers.entrySet()) {
            Class<?> controllerClass = getControllerClass(entry.getValue());
            if (controllerClass == null) {
                continue;
            }
            unannotated.addAll(validateController(controllerClass));
        }

        if (!unannotated.isEmpty()) {
            String message = "以下 Controller 方法未标记访问控制注解（"
                    + "需标记 @LoginAccess / @SystemAccess / @MaintainerAccess / @VisitorAccess 之一）：\n"
                    + String.join("\n", unannotated);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Controller 访问控制注解校验通过，共扫描 {} 个 Controller", controllers.size());
    }

    /**
     * 校验单个 Controller 的所有接口方法。
     *
     * @param controllerClass Controller 类
     * @return 未标记注解的方法列表（格式："ClassName.methodName"）
     */
    private List<String> validateController(Class<?> controllerClass) {
        List<String> unannotated = new ArrayList<>();
        boolean classAnnotated = isAccessAnnotated(controllerClass);

        for (Method method : controllerClass.getDeclaredMethods()) {
            if (!isRequestMappingMethod(method)) {
                continue;
            }
            // 方法级注解优先，若无则继承类级注解
            if (isAccessAnnotated(method) || classAnnotated) {
                continue;
            }
            unannotated.add(controllerClass.getSimpleName() + "." + method.getName() + "()");
        }

        return unannotated;
    }

    /**
     * 判断方法是否为 HTTP 接口映射方法。
     *
     * @param method 目标方法
     * @return 是否为接口方法
     */
    private boolean isRequestMappingMethod(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(a -> MAPPING_ANNOTATIONS.contains(a.annotationType()));
    }

    /**
     * 判断类或方法是否标记了任一访问控制注解。
     *
     * @param element 目标类或方法
     * @return 是否已标记
     */
    private boolean isAccessAnnotated(Class<?> element) {
        return ACCESS_ANNOTATIONS.stream().anyMatch(element::isAnnotationPresent);
    }

    /**
     * 判断方法是否标记了任一访问控制注解。
     *
     * @param method 目标方法
     * @return 是否已标记
     */
    private boolean isAccessAnnotated(Method method) {
        return ACCESS_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    /**
     * 获取 Controller Bean 的实际类（处理 AOP 代理）。
     *
     * @param bean Controller Bean
     * @return 实际类，无法获取时返回空
     */
    private Class<?> getControllerClass(Object bean) {
        Class<?> clazz = bean.getClass();
        // 处理 CGLIB 代理（如 @Transactional 等）
        while (clazz.getSimpleName().contains("$$")) {
            Class<?> superclass = clazz.getSuperclass();
            if (superclass == null || superclass == Object.class) {
                break;
            }
            clazz = superclass;
        }
        return clazz;
    }

}
