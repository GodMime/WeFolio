package com.jxc.wefolio.aspect;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.service.AuthTokenService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
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
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
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
        AuthAspect aspect = new AuthAspect(authTokenService);

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        assertThat(AuthContextHolder.getUserId()).isEmpty();
    }

    @Test
    void maintainerAccessThrowsAuthenticationRequiredWhenTokenInvalid() throws Throwable {
        setRequest("Bearer invalid");
        setJoinPointMethod("maintainerEndpoint");
        when(authTokenService.resolveAuthenticatedUserId("Bearer invalid")).thenReturn(Optional.empty());
        AuthAspect aspect = new AuthAspect(authTokenService);

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
        AuthAspect aspect = new AuthAspect(authTokenService);

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    @Test
    void systemAccessSkipsAuthentication() throws Throwable {
        setRequest(null);
        setJoinPointMethod("systemEndpoint");
        when(joinPoint.proceed()).thenReturn(Response.success("healthy"));
        AuthAspect aspect = new AuthAspect(authTokenService);

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    @Test
    void visitorAccessSkipsAuthentication() throws Throwable {
        setRequest(null);
        setJoinPointMethod("visitorEndpoint");
        when(joinPoint.proceed()).thenReturn(Response.success("public portfolio"));
        AuthAspect aspect = new AuthAspect(authTokenService);

        Object result = aspect.authenticate(joinPoint);

        assertThat(result).isInstanceOf(Response.class);
        verify(authTokenService, never()).resolveAuthenticatedUserId(null);
    }

    private void setRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void setJoinPointMethod(String methodName) throws NoSuchMethodException {
        Method method = FixtureController.class.getDeclaredMethod(methodName);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(methodSignature.getDeclaringType()).thenReturn(FixtureController.class);
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
}
