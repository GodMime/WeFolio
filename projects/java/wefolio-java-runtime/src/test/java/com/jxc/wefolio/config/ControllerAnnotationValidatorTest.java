package com.jxc.wefolio.config;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 控制器注解校验器测试 — 固定类级和方法级访问控制注解都可覆盖对外接口。
 */
@ExtendWith(MockitoExtension.class)
class ControllerAnnotationValidatorTest {

    /** Spring 应用上下文，用于提供待校验的 Controller Bean */
    @Mock
    private ApplicationContext applicationContext;

    /**
     * 类级访问控制注解可以覆盖当前 Controller 内的所有公开接口方法。
     */
    @Test
    void classLevelAccessAnnotationCoversPublicEndpoints() {
        when(applicationContext.getBeansWithAnnotation(RestController.class))
                .thenReturn(Map.of("classLevel", new ClassLevelAccessController()));
        ControllerAnnotationValidator validator = new ControllerAnnotationValidator(applicationContext);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    /**
     * 方法级访问控制注解可以覆盖未在类上统一标记的公开接口方法。
     */
    @Test
    void methodLevelAccessAnnotationCoversPublicEndpoint() {
        when(applicationContext.getBeansWithAnnotation(RestController.class))
                .thenReturn(Map.of("methodLevel", new MethodLevelAccessController()));
        ControllerAnnotationValidator validator = new ControllerAnnotationValidator(applicationContext);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    /**
     * 继承接口可以由声明该接口方法的父类级访问控制注解覆盖。
     */
    @Test
    void declaringClassAccessAnnotationCoversInheritedPublicEndpoint() {
        when(applicationContext.getBeansWithAnnotation(RestController.class))
                .thenReturn(Map.of("inheritedClassLevel", new InheritedClassLevelAccessController()));
        ControllerAnnotationValidator validator = new ControllerAnnotationValidator(applicationContext);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    /**
     * 继承得到的公开映射方法也属于对外接口，缺少访问控制注解时必须拦截。
     */
    @Test
    void inheritedPublicEndpointWithoutAccessAnnotationFailsValidation() {
        when(applicationContext.getBeansWithAnnotation(RestController.class))
                .thenReturn(Map.of("inherited", new InheritedUnannotatedController()));
        ControllerAnnotationValidator validator = new ControllerAnnotationValidator(applicationContext);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InheritedUnannotatedController.inheritedEndpoint()");
    }

    /**
     * 类级标记夹具 — 模拟统一维护者访问控制的 Controller。
     */
    @MaintainerAccess
    @RestController
    private static class ClassLevelAccessController {

        /**
         * 类级注解覆盖的公开接口。
         *
         * @return 响应内容
         */
        @GetMapping("/class-level")
        public String endpoint() {
            return "ok";
        }
    }

    /**
     * 方法级标记夹具 — 模拟同一 Controller 内不同访问类型的接口。
     */
    @RestController
    private static class MethodLevelAccessController {

        /**
         * 方法级注解覆盖的公开接口。
         *
         * @return 响应内容
         */
        @LoginAccess
        @GetMapping("/method-level")
        public String endpoint() {
            return "ok";
        }
    }

    /**
     * 父类级标记夹具 — 模拟在父类统一声明访问控制的接口基类。
     */
    @MaintainerAccess
    private static class BaseClassLevelAccessController {

        /**
         * 父类级注解覆盖的继承接口。
         *
         * @return 响应内容
         */
        @GetMapping("/inherited-class-level")
        public String inheritedEndpoint() {
            return "ok";
        }
    }

    /**
     * 继承父类级标记夹具 — 模拟仅负责注册为 Controller 的子类。
     */
    @RestController
    private static class InheritedClassLevelAccessController extends BaseClassLevelAccessController {
    }

    /**
     * 父类映射夹具 — 提供会被子类继承的公开接口方法。
     */
    private static class BaseUnannotatedController {

        /**
         * 未标记访问控制注解的继承接口。
         *
         * @return 响应内容
         */
        @GetMapping("/inherited")
        public String inheritedEndpoint() {
            return "ok";
        }
    }

    /**
     * 未标记子类夹具 — 模拟继承了父类映射方法的 Controller。
     */
    @RestController
    private static class InheritedUnannotatedController extends BaseUnannotatedController {
    }
}
