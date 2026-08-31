package com.jxc.wefolio.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.controller.MineController;
import com.jxc.wefolio.controller.VisitorPortfolioController;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.ContactLeadService;
import com.jxc.wefolio.service.MineDashboardService;
import com.jxc.wefolio.service.MineProfileService;
import com.jxc.wefolio.service.MineVisitService;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import com.jxc.wefolio.service.VisitorPortfolioService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 弃用接口日志切面测试。 */
class DeprecatedEndpointLoggingAspectTest {

    /** 每个测试后清理请求上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
        VisitorContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /** 方法级弃用接口应记录路由模板和脱敏入参。 */
    @Test
    void methodDeprecatedEndpointShouldLogRouteAndSanitizedParameters() {
        setRequest("POST", "/api/fixtures/7", "/api/fixtures/{fixtureId}");
        FixtureController controller = proxy(new FixtureController());
        ContactLeadSubmitRequest request = new ContactLeadSubmitRequest();
        request.setContactName("测试客户");
        request.setPhone("13812348000");
        request.setWechat("wechat-secret");
        request.setVisitorKey("visitor-secret");
        ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);

        try {
            String result = controller.deprecatedEndpoint(7L, request);

            assertThat(result).isEqualTo("fixture-7");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("弃用接口被调用: interface=POST /api/fixtures/{fixtureId}")
                        .contains("\"fixtureId\":7")
                        .contains("\"contactName\":\"***\"")
                        .contains("\"phone\":\"***\"")
                        .contains("\"wechat\":\"***\"")
                        .contains("\"visitorKey\":\"***\"")
                        .doesNotContain("测试客户")
                        .doesNotContain("13812348000")
                        .doesNotContain("wechat-secret")
                        .doesNotContain("visitor-secret");
            });
        } finally {
            detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
        }
    }

    /** 日志应默认隐藏字符串，并限制安全字符串、集合和最终消息长度。 */
    @Test
    void deprecatedEndpointLogShouldBeSanitizedAndBounded() {
        setRequest("POST", "/api/fixtures/bounded", "/api/fixtures/bounded");
        FixtureController controller = proxy(new FixtureController());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("authorizationHeader", "Bearer raw-authorization");
        request.put("userPhone", "13812348000");
        request.put("password", "raw-password");
        request.put("clientSecret", "raw-client-secret");
        request.put("privateKey", "raw-private-key");
        request.put("avatarUrl", "https://example.com/avatar?token=raw-url-token");
        request.put("componentKey", "x".repeat(1000));
        request.put("items", IntStream.range(0, 100).boxed().toList());
        ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);

        try {
            assertThat(controller.boundedEndpoint(request)).isSameAs(request);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("\"authorizationHeader\":\"***\"")
                        .contains("\"userPhone\":\"***\"")
                        .contains("\"password\":\"***\"")
                        .contains("\"clientSecret\":\"***\"")
                        .contains("\"privateKey\":\"***\"")
                        .contains("\"avatarUrl\":\"***\"")
                        .contains("[字符串已截断]")
                        .contains("[剩余元素已截断]")
                        .doesNotContain("raw-authorization")
                        .doesNotContain("13812348000")
                        .doesNotContain("raw-password")
                        .doesNotContain("raw-client-secret")
                        .doesNotContain("raw-private-key")
                        .doesNotContain("raw-url-token");
                assertThat(event.getFormattedMessage()).hasSizeLessThanOrEqualTo(9000);
            });
        } finally {
            detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
        }
    }

    /** 脱敏后仍超出总长度时应只记录参数名摘要。 */
    @Test
    void oversizedSanitizedParametersShouldUseBoundedSummary() {
        setRequest("POST", "/api/fixtures/bounded", "/api/fixtures/bounded");
        FixtureController controller = proxy(new FixtureController());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("items", IntStream.range(0, 20)
                .mapToObj(index -> Map.of(
                        "componentKey", "c".repeat(1000),
                        "scope", "s".repeat(1000)))
                .toList());
        ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);

        try {
            assertThat(controller.boundedEndpoint(request)).isSameAs(request);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("\"parameterNames\":[\"request\"]")
                        .contains("\"summary\":\"[入参日志超过限制，已省略]\"")
                        .hasSizeLessThan(1000);
            });
        } finally {
            detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
        }
    }

    /** 日志切面不得消费一次性迭代入参。 */
    @Test
    void deprecatedEndpointLogShouldNotConsumeOneShotIterable() {
        setRequest("POST", "/api/fixtures/iterable", "/api/fixtures/iterable");
        FixtureController controller = proxy(new FixtureController());
        OneShotIterable request = new OneShotIterable();

        assertThat(controller.iterableEndpoint(request)).isTrue();
    }

    /** 类级弃用标记应覆盖未单独标记的方法。 */
    @Test
    void classDeprecatedEndpointShouldLogError() {
        setRequest("GET", "/api/deprecated-class/9", "/api/deprecated-class/{fixtureId}");
        DeprecatedClassController controller = proxy(new DeprecatedClassController());
        ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);

        try {
            assertThat(controller.endpoint(9L)).isEqualTo(9L);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("interface=GET /api/deprecated-class/{fixtureId}")
                        .contains("\"fixtureId\":9");
            });
        } finally {
            detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
        }
    }

    /** 未弃用接口不应产生弃用错误日志。 */
    @Test
    void activeEndpointShouldNotLogDeprecatedError() {
        setRequest("GET", "/api/fixtures/active", "/api/fixtures/active");
        FixtureController controller = proxy(new FixtureController());
        ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);

        try {
            assertThat(controller.activeEndpoint()).isEqualTo("active");
            assertThat(appender.list).isEmpty();
        } finally {
            detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
        }
    }

    /** 统一切面接管后，旧访问记录接口不应再由 Controller 重复打印弃用警告。 */
    @Test
    void deprecatedControllerShouldNotWriteDuplicateWarning() {
        setRequest("GET", "/api/mine/visits", "/api/mine/visits");
        MineVisitService visitService = mock(MineVisitService.class);
        MineVisitRecordsResponse serviceResponse = new MineVisitRecordsResponse();
        when(visitService.getVisitRecords()).thenReturn(serviceResponse);
        MineController controller = proxy(new MineController(
                mock(MineDashboardService.class), mock(MineProfileService.class), visitService));
        ListAppender<ILoggingEvent> aspectAppender = attachAppender(DeprecatedEndpointLoggingAspect.class);
        ListAppender<ILoggingEvent> controllerAppender = attachAppender(MineController.class);

        try {
            assertThat(controller.visits().getData()).isSameAs(serviceResponse);
            assertThat(aspectAppender.list).singleElement().satisfies(event ->
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR));
            assertThat(controllerAppender.list)
                    .noneMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("调用已弃用访问记录接口"));
        } finally {
            detachAppender(DeprecatedEndpointLoggingAspect.class, aspectAppender);
            detachAppender(MineController.class, controllerAppender);
        }
    }

    /** 维护端弃用接口应在认证完成后记录用户 ID。 */
    @Test
    void maintainerDeprecatedEndpointShouldLogAuthenticatedUserId() {
        MockHttpServletRequest request = setRequest(
                "GET", "/api/mine/visits", "/api/mine/visits");
        request.addHeader("Authorization", "Bearer maintainer-token");
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        when(authTokenService.resolveAuthenticatedUserId("Bearer maintainer-token"))
                .thenReturn(Optional.of(101L));
        MineVisitService visitService = mock(MineVisitService.class);
        MineVisitRecordsResponse serviceResponse = new MineVisitRecordsResponse();
        when(visitService.getVisitRecords()).thenReturn(serviceResponse);
        MineController target = new MineController(
                mock(MineDashboardService.class), mock(MineProfileService.class), visitService);

        try (AnnotationConfigApplicationContext context = authenticatedContext(
                MineController.class,
                target,
                new AuthAspect(authTokenService, mock(VisitorAuthTokenService.class)))) {
            MineController controller = context.getBean(MineController.class);
            ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);
            try {
                assertThat(controller.visits().getData()).isSameAs(serviceResponse);
                assertThat(appender.list).singleElement().satisfies(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("userId=101")
                                .contains("visitorId=null")
                                .contains("interface=GET /api/mine/visits"));
            } finally {
                detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
            }
        }
    }

    /** 访客端弃用接口应在认证完成后记录访客 ID。 */
    @Test
    void visitorDeprecatedEndpointShouldLogAuthenticatedVisitorId() {
        MockHttpServletRequest request = setRequest(
                "POST",
                "/api/visitor/portfolios/PF001/contact-leads",
                "/api/visitor/portfolios/{shareCode}/contact-leads");
        request.addHeader("Authorization", "Bearer visitor-token");
        VisitorAuthTokenService visitorAuthTokenService = mock(VisitorAuthTokenService.class);
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer visitor-token"))
                .thenReturn(Optional.of(new VisitorAuthTokenService.ResolvedVisitorToken(
                        202L, "visitor-key", Instant.parse("2099-01-01T00:00:00Z"))));
        ContactLeadService contactLeadService = mock(ContactLeadService.class);
        ContactLeadSubmitRequest submitRequest = new ContactLeadSubmitRequest();
        ContactLeadSubmitResponse serviceResponse = new ContactLeadSubmitResponse();
        when(contactLeadService.submit("PF001", submitRequest)).thenReturn(serviceResponse);
        VisitorPortfolioController target = new VisitorPortfolioController(
                mock(VisitorPortfolioService.class), contactLeadService);

        try (AnnotationConfigApplicationContext context = authenticatedContext(
                VisitorPortfolioController.class,
                target,
                new AuthAspect(mock(AuthTokenService.class), visitorAuthTokenService))) {
            VisitorPortfolioController controller = context.getBean(VisitorPortfolioController.class);
            ListAppender<ILoggingEvent> appender = attachAppender(DeprecatedEndpointLoggingAspect.class);
            try {
                assertThat(controller.contactLead("PF001", submitRequest).getData()).isSameAs(serviceResponse);
                assertThat(appender.list).singleElement().satisfies(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("userId=null")
                                .contains("visitorId=202")
                                .contains("interface=POST /api/visitor/portfolios/{shareCode}/contact-leads"));
            } finally {
                detachAppender(DeprecatedEndpointLoggingAspect.class, appender);
            }
        }
    }

    /** 认证失败前应允许更高优先级的请求观察切面记录请求。 */
    @Test
    void requestObserverShouldRunBeforeAuthenticationFailure() {
        MockHttpServletRequest request = setRequest(
                "GET", "/api/mine/visits", "/api/mine/visits");
        request.addHeader("Authorization", "Bearer invalid-token");
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        when(authTokenService.resolveAuthenticatedUserId("Bearer invalid-token"))
                .thenReturn(Optional.empty());
        MineController target = new MineController(
                mock(MineDashboardService.class),
                mock(MineProfileService.class),
                mock(MineVisitService.class));
        RequestObservationAspect observationAspect = new RequestObservationAspect();

        try (AnnotationConfigApplicationContext context = authenticatedContext(
                MineController.class,
                target,
                new AuthAspect(authTokenService, mock(VisitorAuthTokenService.class)),
                observationAspect)) {
            MineController controller = context.getBean(MineController.class);

            assertThatThrownBy(controller::visits)
                    .isInstanceOf(AuthenticationRequiredException.class);
            assertThat(observationAspect.wasObserved()).isTrue();
        }
    }

    /** 日志内部异常不得向接口调用链外抛。 */
    @Test
    void loggingFailureShouldNotInterruptRequest() {
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenThrow(new IllegalStateException("logging failure"));

        assertThatCode(() -> new DeprecatedEndpointLoggingAspect().logDeprecatedEndpoint(joinPoint))
                .doesNotThrowAnyException();
    }

    /** 入参快照生成抛出 Error 时不得中断接口调用。 */
    @Test
    void parameterSnapshotErrorShouldNotInterruptRequest() {
        setRequest("POST", "/api/fixtures/failure", "/api/fixtures/failure");
        FixtureController controller = proxy(new FixtureController());

        assertThat(controller.failureEndpoint(new ErrorThrowingCharSequence()))
                .isEqualTo("executed");
    }

    /** 日志 appender 抛出 Error 时不得中断接口调用。 */
    @Test
    void appenderErrorShouldNotInterruptRequest() {
        setRequest("POST", "/api/fixtures/failure", "/api/fixtures/failure");
        FixtureController controller = proxy(new FixtureController());
        ErrorThrowingAppender appender = new ErrorThrowingAppender();
        Logger logger = (Logger) LoggerFactory.getLogger(DeprecatedEndpointLoggingAspect.class);
        appender.start();
        logger.addAppender(appender);

        try {
            assertThat(controller.failureEndpoint("safe-request"))
                    .isEqualTo("executed");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 创建仅织入弃用接口日志切面的代理。 */
    private <T> T proxy(T target) {
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(new DeprecatedEndpointLoggingAspect());
        return proxyFactory.getProxy();
    }

    /** 创建按 Spring 排序规则织入认证和弃用日志切面的应用上下文。 */
    private <T> AnnotationConfigApplicationContext authenticatedContext(
            Class<T> controllerType,
            T target,
            AuthAspect authAspect
    ) {
        return authenticatedContext(controllerType, target, authAspect, null);
    }

    /** 创建可附加请求观察切面的应用上下文。 */
    private <T> AnnotationConfigApplicationContext authenticatedContext(
            Class<T> controllerType,
            T target,
            AuthAspect authAspect,
            RequestObservationAspect observationAspect
    ) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(AspectOrderingTestConfiguration.class);
        context.registerBean(AuthAspect.class, () -> authAspect);
        if (observationAspect != null) {
            context.registerBean(RequestObservationAspect.class, () -> observationAspect);
        }
        context.registerBean(DeprecatedEndpointLoggingAspect.class, DeprecatedEndpointLoggingAspect::new);
        context.registerBean(controllerType, () -> target);
        context.refresh();
        return context;
    }

    /** 设置当前请求及 Spring MVC 匹配后的路由模板。 */
    private MockHttpServletRequest setRequest(String method, String requestUri, String routePattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, routePattern);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    /** 为指定日志器挂载内存 appender。 */
    private ListAppender<ILoggingEvent> attachAppender(Class<?> loggerType) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 从指定日志器移除内存 appender。 */
    private void detachAppender(Class<?> loggerType, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
        logger.detachAppender(appender);
        appender.stop();
    }

    /** 方法级弃用测试控制器。 */
    @RestController
    static class FixtureController {

        /** 弃用测试接口。 */
        @Deprecated
        @PostMapping("/api/fixtures/{fixtureId}")
        public String deprecatedEndpoint(
                @PathVariable("fixtureId") Long fixtureId,
                @RequestBody ContactLeadSubmitRequest request
        ) {
            return "fixture-" + fixtureId;
        }

        /** 正常测试接口。 */
        @GetMapping("/api/fixtures/active")
        public String activeEndpoint() {
            return "active";
        }

        /** 有界日志测试接口。 */
        @Deprecated
        @PostMapping("/api/fixtures/bounded")
        public Map<String, Object> boundedEndpoint(@RequestBody Map<String, Object> request) {
            return request;
        }

        /** 一次性迭代入参测试接口。 */
        @Deprecated
        @PostMapping("/api/fixtures/iterable")
        public boolean iterableEndpoint(@RequestBody Iterable<String> request) {
            return request.iterator().hasNext();
        }

        /** 日志异常隔离测试接口。 */
        @Deprecated
        @PostMapping("/api/fixtures/failure")
        public String failureEndpoint(@RequestBody CharSequence request) {
            return "executed";
        }
    }

    /** 转换为字符串时抛出 Error 的测试参数。 */
    static class ErrorThrowingCharSequence implements CharSequence {

        /** 返回测试参数长度。 */
        @Override
        public int length() {
            return 1;
        }

        /** 返回测试字符。 */
        @Override
        public char charAt(int index) {
            return 'x';
        }

        /** 返回测试子序列。 */
        @Override
        public CharSequence subSequence(int start, int end) {
            return "x";
        }

        /** 模拟入参快照处理的严重错误。 */
        @Override
        public String toString() {
            throw new AssertionError("parameter snapshot failure");
        }
    }

    /** 每次写日志均抛出 Error 的测试 appender。 */
    static class ErrorThrowingAppender extends AppenderBase<ILoggingEvent> {

        /** 模拟日志输出失败。 */
        @Override
        protected void append(ILoggingEvent eventObject) {
            throw new AssertionError("appender failure");
        }
    }

    /** 仅允许读取一次的迭代入参。 */
    static class OneShotIterable implements Iterable<String> {

        /** 唯一迭代器。 */
        private final Iterator<String> iterator = List.of("first").iterator();

        /** 返回唯一迭代器。 */
        @Override
        public Iterator<String> iterator() {
            return iterator;
        }
    }

    /** 类级弃用测试控制器。 */
    @Deprecated
    @RestController
    static class DeprecatedClassController {

        /** 未单独标记弃用的方法。 */
        @GetMapping("/api/deprecated-class/{fixtureId}")
        public Long endpoint(@PathVariable("fixtureId") Long fixtureId) {
            return fixtureId;
        }
    }

    /** 模拟未来需要记录认证失败请求的观察切面。 */
    @Aspect
    @Order(RequestObservationAspect.ORDER)
    static class RequestObservationAspect {

        /** 请求观察切面顺序，独立验证认证前的预留空间。 */
        private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

        /** 请求是否已进入观察切面。 */
        private final AtomicBoolean observed = new AtomicBoolean();

        /** 在后续切面处理前记录请求已被观察。 */
        @Around("within(com.jxc.wefolio.controller..*)")
        public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
            observed.set(true);
            return joinPoint.proceed();
        }

        /** 返回请求是否已被观察。 */
        boolean wasObserved() {
            return observed.get();
        }
    }

    /** 启用真实 Spring AOP 自动代理和顺序排序。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AspectOrderingTestConfiguration {
    }
}
