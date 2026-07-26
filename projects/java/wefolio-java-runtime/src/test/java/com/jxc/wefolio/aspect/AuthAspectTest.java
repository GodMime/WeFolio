package com.jxc.wefolio.aspect;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.annotation.TimelineAnonymousAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制器访问控制切面单元测试 — 覆盖四类访问控制注解的认证行为。
 */
@ExtendWith(MockitoExtension.class)
class AuthAspectTest {

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private VisitorAuthTokenService visitorAuthTokenService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
        VisitorContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void maintainerAccessSetsContextAndClearsItAfterProceeding() throws Throwable {
        setRequest("Bearer wf-dev-user-7");
        setJoinPointMethod("maintainerEndpoint");
        when(authTokenService.resolveAuthenticatedUserId("Bearer wf-dev-user-7")).thenReturn(Optional.of(7L));
        doAnswer(invocation -> {
            assertThat(AuthContextHolder.requireUserId()).isEqualTo(7L);
            return Response.success("ok");
        }).when(joinPoint).proceed();
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        assertThat(AuthContextHolder.getUserId()).isEmpty();
    }

    @Test
    void maintainerAccessThrowsAuthenticationRequiredWhenTokenInvalid() throws Throwable {
        setRequest("Bearer invalid");
        setJoinPointMethod("maintainerEndpoint");
        when(authTokenService.resolveAuthenticatedUserId("Bearer invalid")).thenReturn(Optional.empty());
        AuthAspect aspect = aspect();

        assertThatThrownBy(() -> aspect.authenticate(joinPoint))
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessage("未登录");
        assertThat(AuthContextHolder.getUserId()).isEmpty();
        verify(joinPoint, never()).proceed();
    }

    @Test
    void loginAccessSkipsAuthentication() throws Throwable {
        setRequest(null);
        setJoinPointMethod("loginEndpoint");
        when(joinPoint.proceed()).thenReturn(Response.success("public"));
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    @Test
    void systemAccessSkipsAuthentication() throws Throwable {
        setRequest(null);
        setJoinPointMethod("systemEndpoint");
        when(joinPoint.proceed()).thenReturn(Response.success("healthy"));
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    @Test
    void visitorAccessThrowsAuthenticationRequiredWhenTokenInvalid() throws Throwable {
        setRequest("Bearer invalid");
        setJoinPointMethod("visitorEndpoint");
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer invalid")).thenReturn(Optional.empty());
        AuthAspect aspect = aspect();

        assertThatThrownBy(() -> aspect.authenticate(joinPoint))
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessage("未登录");

        assertThat(VisitorContextHolder.getVisitorId()).isEmpty();
        verify(joinPoint, never()).proceed();
        verify(authTokenService, never()).resolveAuthenticatedUserId("Bearer invalid");
    }

    @Test
    void visitorAccessSetsContextAndClearsItAfterProceeding() throws Throwable {
        setRequest("Bearer wf-visitor-v1.token");
        setJoinPointMethod("visitorEndpoint");
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer wf-visitor-v1.token"))
                .thenReturn(Optional.of(resolvedVisitorToken()));
        doAnswer(invocation -> {
            assertThat(VisitorContextHolder.requireVisitorId()).isEqualTo(1024L);
            assertThat(VisitorContextHolder.requireVisitorKey()).isEqualTo("visitor-key");
            assertThat(VisitorContextHolder.current().orElseThrow().isTimelineAnonymous()).isFalse();
            return Response.success("public portfolio");
        }).when(joinPoint).proceed();
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        assertThat(VisitorContextHolder.getVisitorId()).isEmpty();
        assertThat(VisitorContextHolder.getVisitorKey()).isEmpty();
        verify(authTokenService, never()).resolveAuthenticatedUserId("Bearer wf-visitor-v1.token");
    }

    @Test
    void timelineAnonymousTokenAllowsOnlyAnnotatedEndpointWithinIssuedPortfolio() throws Throwable {
        setPortfolioRequest("Bearer wf-visitor-timeline-v1.token", "PF001");
        setJoinPointMethod(TimelineVisitorFixtureController.class, "scheduleEndpoint");
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer wf-visitor-timeline-v1.token"))
                .thenReturn(Optional.of(timelineAnonymousVisitorToken("PERSONAL:PF001")));
        doAnswer(invocation -> {
            assertThat(VisitorContextHolder.current().orElseThrow().isTimelineAnonymous()).isTrue();
            return Response.success("schedule");
        }).when(joinPoint).proceed();

        Object result = aspect().authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        assertThat(VisitorContextHolder.current()).isEmpty();
    }

    @Test
    void timelineAnonymousTokenRejectsProfileEndpointAndDifferentPortfolio() throws Throwable {
        setPortfolioRequest("Bearer wf-visitor-timeline-v1.token", "PF001");
        setJoinPointMethod(TimelineVisitorFixtureController.class, "profileEndpoint");
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer wf-visitor-timeline-v1.token"))
                .thenReturn(Optional.of(timelineAnonymousVisitorToken("PERSONAL:PF001")));

        assertThatThrownBy(() -> aspect().authenticate(joinPoint))
                .isInstanceOf(AuthenticationRequiredException.class);
        verify(joinPoint, never()).proceed();

        setJoinPointMethod(TimelineVisitorFixtureController.class, "scheduleEndpoint");
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer wf-visitor-timeline-v1.token"))
                .thenReturn(Optional.of(timelineAnonymousVisitorToken("PERSONAL:PF999")));

        assertThatThrownBy(() -> aspect().authenticate(joinPoint))
                .isInstanceOf(AuthenticationRequiredException.class);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void visitorAccessSuccessLogContainsOnlyVisitorIdAndRequestWithoutSensitiveIdentity() throws Throwable {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        setRequest("Bearer wf-visitor-v1.token");
        setJoinPointMethod("visitorEndpoint");
        when(visitorAuthTokenService.resolveAuthenticatedVisitor("Bearer wf-visitor-v1.token"))
                .thenReturn(Optional.of(resolvedVisitorToken()));
        when(joinPoint.proceed()).thenReturn(Response.success("public portfolio"));
        AuthAspect aspect = aspect();

        try {
            aspect.authenticate(joinPoint);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains("访客认证通过: visitorId=1024")
                    .contains("request=GET /api/test/visitor"));
            assertThat(messages)
                    .noneMatch(message -> message.contains("visitor-key"))
                    .noneMatch(message -> message.contains("wf-visitor-v1.token"))
                    .noneMatch(message -> message.toLowerCase().contains("openid"))
                    .noneMatch(message -> message.contains("visitorKey="))
                    .noneMatch(message -> message.contains("开始访客认证"))
                    .noneMatch(message -> message.contains("访客令牌解析结果"))
                    .noneMatch(message -> message.contains("写入访客上下文"))
                    .noneMatch(message -> message.contains("清理访客上下文"));
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    void methodLevelMaintainerAccessOverridesLoginAccessClass() throws Throwable {
        assertMethodLevelMaintainerAccessRequiresAuthentication(LoginAccessFixtureController.class);
    }

    @Test
    void methodLevelMaintainerAccessOverridesSystemAccessClass() throws Throwable {
        assertMethodLevelMaintainerAccessRequiresAuthentication(SystemAccessFixtureController.class);
    }

    @Test
    void methodLevelMaintainerAccessOverridesVisitorAccessClass() throws Throwable {
        assertMethodLevelMaintainerAccessRequiresAuthentication(VisitorAccessFixtureController.class);
    }

    @Test
    void methodLevelLoginAccessOverridesVisitorAccessClass() throws Throwable {
        setRequest(null);
        setJoinPointMethod(VisitorAccessFixtureController.class, "loginEndpoint");
        when(joinPoint.proceed()).thenReturn(Response.success("public"));
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(visitorAuthTokenService, never()).resolveAuthenticatedVisitor(null);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    @Test
    void methodLevelLoginAccessOverridesMaintainerAccessClass() throws Throwable {
        setRequest(null);
        setJoinPointMethod(MaintainerAccessFixtureController.class, "loginEndpoint");
        when(joinPoint.proceed()).thenReturn(Response.success("public"));
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    @Test
    void classLevelMaintainerAccessAppliesWhenMethodHasNoAccessAnnotation() throws Throwable {
        setRequest("Bearer wf-dev-user-9");
        setJoinPointMethod(MaintainerAccessFixtureController.class, "unannotatedEndpoint");
        when(authTokenService.resolveAuthenticatedUserId("Bearer wf-dev-user-9")).thenReturn(Optional.of(9L));
        doAnswer(invocation -> {
            assertThat(AuthContextHolder.requireUserId()).isEqualTo(9L);
            return Response.success("secured");
        }).when(joinPoint).proceed();
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        assertThat(AuthContextHolder.getUserId()).isEmpty();
        verify(authTokenService).resolveAuthenticatedUserId("Bearer wf-dev-user-9");
    }

    private void assertMethodLevelMaintainerAccessRequiresAuthentication(Class<?> controllerClass) throws Throwable {
        setRequest("Bearer wf-dev-user-9");
        setJoinPointMethod(controllerClass, "maintainerEndpoint");
        when(authTokenService.resolveAuthenticatedUserId("Bearer wf-dev-user-9")).thenReturn(Optional.of(9L));
        doAnswer(invocation -> {
            assertThat(AuthContextHolder.requireUserId()).isEqualTo(9L);
            return Response.success("secured");
        }).when(joinPoint).proceed();
        AuthAspect aspect = aspect();

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        assertThat(AuthContextHolder.getUserId()).isEmpty();
        verify(authTokenService).resolveAuthenticatedUserId("Bearer wf-dev-user-9");
    }

    /**
     * 构建待测认证切面。
     *
     * @return 认证切面
     */
    private AuthAspect aspect() {
        return new AuthAspect(authTokenService, visitorAuthTokenService);
    }

    /**
     * 构造已解析访客令牌。
     *
     * @return 已解析访客令牌
     */
    private VisitorAuthTokenService.ResolvedVisitorToken resolvedVisitorToken() {
        return new VisitorAuthTokenService.ResolvedVisitorToken(
                1024L,
                "visitor-key",
                Instant.now().plusSeconds(3600)
        );
    }

    /** 构造朋友圈单页匿名访客令牌。 */
    private VisitorAuthTokenService.ResolvedVisitorToken timelineAnonymousVisitorToken(String scope) {
        return new VisitorAuthTokenService.ResolvedVisitorToken(
                2048L,
                "timeline-visitor-key",
                Instant.now().plusSeconds(3600),
                scope
        );
    }

    /**
     * 挂载日志捕获器。
     *
     * @return 日志捕获器
     */
    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 拆除日志捕获器。
     *
     * @param appender 日志捕获器
     */
    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthAspect.class);
        logger.detachAppender(appender);
    }

    private void setRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/test/visitor");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 设置带分享码路径变量的个人作品集请求。 */
    private void setPortfolioRequest(String authorization, String shareCode) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/visitor/portfolios/" + shareCode + "/schedule");
        request.addHeader("Authorization", authorization);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareCode", shareCode));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void setJoinPointMethod(String methodName) throws NoSuchMethodException {
        setJoinPointMethod(FixtureController.class, methodName);
    }

    private void setJoinPointMethod(Class<?> controllerClass, String methodName) throws NoSuchMethodException {
        Method method = controllerClass.getDeclaredMethod(methodName);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        if (!isAccessAnnotated(method)) {
            when(methodSignature.getDeclaringType()).thenReturn(controllerClass);
        }
    }

    private boolean isAccessAnnotated(Method method) {
        return method.isAnnotationPresent(LoginAccess.class)
                || method.isAnnotationPresent(SystemAccess.class)
                || method.isAnnotationPresent(MaintainerAccess.class)
                || method.isAnnotationPresent(VisitorAccess.class);
    }

    /**
     * 测试夹具 Controller — 包含四类访问控制注解的方法。
     */
    private static class FixtureController {

        @MaintainerAccess
        public Response<String> maintainerEndpoint() {
            return Response.success("ok");
        }

        @LoginAccess
        public Response<String> loginEndpoint() {
            return Response.success("public");
        }

        @SystemAccess
        public Response<String> systemEndpoint() {
            return Response.success("healthy");
        }

        @VisitorAccess
        public Response<String> visitorEndpoint() {
            return Response.success("public portfolio");
        }
    }

    /**
     * 登录类访问测试夹具 — 用于验证方法级注解优先于类级注解。
     */
    @LoginAccess
    private static class LoginAccessFixtureController {

        @MaintainerAccess
        public Response<String> maintainerEndpoint() {
            return Response.success("secured");
        }
    }

    /**
     * 系统类访问测试夹具 — 用于验证方法级注解优先于类级注解。
     */
    @SystemAccess
    private static class SystemAccessFixtureController {

        @MaintainerAccess
        public Response<String> maintainerEndpoint() {
            return Response.success("secured");
        }
    }

    /**
     * 访客类访问测试夹具 — 用于验证方法级注解优先于类级注解。
     */
    @VisitorAccess
    private static class VisitorAccessFixtureController {

        @MaintainerAccess
        public Response<String> maintainerEndpoint() {
            return Response.success("secured");
        }

        @LoginAccess
        public Response<String> loginEndpoint() {
            return Response.success("public");
        }
    }

    /** 朋友圈单页匿名访客访问测试夹具。 */
    @VisitorAccess
    private static class TimelineVisitorFixtureController {

        @TimelineAnonymousAccess
        public Response<String> scheduleEndpoint() {
            return Response.success("schedule");
        }

        public Response<String> profileEndpoint() {
            return Response.success("profile");
        }
    }

    /**
     * 维护者类访问测试夹具 — 用于验证方法级放行注解覆盖类级认证注解。
     */
    @MaintainerAccess
    private static class MaintainerAccessFixtureController {

        public Response<String> unannotatedEndpoint() {
            return Response.success("secured");
        }

        @LoginAccess
        public Response<String> loginEndpoint() {
            return Response.success("public");
        }
    }
}
