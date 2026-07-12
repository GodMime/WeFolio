package com.jxc.wefolio.aspect;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 控制器访问控制切面 — 根据接口标记注解执行对应的认证逻辑。
 *
 * <ul>
 *   <li>{@link LoginAccess} — 登录类接口，跳过认证直接放行</li>
 *   <li>{@link SystemAccess} — 系统类接口，跳过认证直接放行</li>
 *   <li>{@link MaintainerAccess} — 维护者类接口，要求有效的 Authorization 令牌</li>
 *   <li>{@link VisitorAccess} — 访客类接口，要求有效的访客 Authorization 令牌</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuthAspect {

    /** 登录令牌认证服务 */
    private final AuthTokenService authTokenService;

    /** 访客登录令牌认证服务 */
    private final VisitorAuthTokenService visitorAuthTokenService;

    /**
     * 接口访问类型 — 方法级注解优先，方法未标记时继承类级注解。
     */
    private enum AccessType {
        /** 登录类接口，直接放行 */
        LOGIN,
        /** 系统类接口，直接放行 */
        SYSTEM,
        /** 维护者类接口，需要登录令牌 */
        MAINTAINER,
        /** 访客类接口，需要访客登录令牌 */
        VISITOR
    }

    /**
     * 拦截控制器接口并根据访问控制注解执行认证。
     *
     * @param joinPoint 切点
     * @return 控制器执行结果
     * @throws Throwable 控制器执行异常
     */
    @Around("within(com.jxc.wefolio.controller..*)")
    public Object authenticate(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = resolveMethod(joinPoint);
        HttpServletRequest request = currentRequest();
        String requestInfo = request != null
                ? request.getMethod() + " " + request.getRequestURI()
                : "未知请求";
        AccessType accessType = resolveAccessType(method, joinPoint)
                .orElseThrow(() -> new AuthenticationRequiredException("接口未配置访问控制注解"));

        if (accessType == AccessType.MAINTAINER) {
            return authenticateMaintainer(joinPoint, request, requestInfo);
        }
        if (accessType == AccessType.VISITOR) {
            return authenticateVisitor(joinPoint, request, requestInfo);
        }

        log.info("放行请求: {}", requestInfo);
        return joinPoint.proceed();
    }

    /**
     * 维护者认证 — 校验 Authorization 令牌并注入登录上下文。
     *
     * @param joinPoint   切点
     * @param request     HTTP 请求
     * @param requestInfo 请求信息（方法 + URI），用于日志
     * @return 控制器执行结果
     * @throws Throwable 控制器执行异常
     */
    private Object authenticateMaintainer(ProceedingJoinPoint joinPoint, HttpServletRequest request, String requestInfo) throws Throwable {
        if (request == null) {
            log.warn("维护者认证失败：无法获取 HTTP 请求");
            throw new AuthenticationRequiredException();
        }

        String authorization = request.getHeader("Authorization");
        Optional<Long> userId = authTokenService.resolveAuthenticatedUserId(authorization);
        if (userId.isEmpty()) {
            log.warn("维护者认证失败：令牌无效或已过期, request={}", requestInfo);
            throw new AuthenticationRequiredException();
        }

        log.info("维护者认证通过: userId={}, request={}", userId.get(), requestInfo);
        AuthContextHolder.set(new AuthContext(userId.get(), authorization));
        try {
            return joinPoint.proceed();
        } finally {
            AuthContextHolder.clear();
        }
    }

    /**
     * 访客认证 — 校验 Authorization 令牌并注入访客上下文。
     *
     * @param joinPoint   切点
     * @param request     HTTP 请求
     * @param requestInfo 请求信息（方法 + URI），用于日志
     * @return 控制器执行结果
     * @throws Throwable 控制器执行异常
     */
    private Object authenticateVisitor(ProceedingJoinPoint joinPoint, HttpServletRequest request, String requestInfo) throws Throwable {
        if (request == null) {
            log.warn("访客认证失败：无法获取 HTTP 请求");
            throw new AuthenticationRequiredException();
        }

        String authorization = request.getHeader("Authorization");
        Optional<VisitorAuthTokenService.ResolvedVisitorToken> visitorToken =
                visitorAuthTokenService.resolveAuthenticatedVisitor(authorization);
        if (visitorToken.isEmpty()) {
            log.warn("访客认证失败：令牌无效或已过期, request={}", requestInfo);
            throw new AuthenticationRequiredException();
        }

        VisitorAuthTokenService.ResolvedVisitorToken resolvedToken = visitorToken.get();
        log.info("访客认证通过: visitorId={}, request={}",
                resolvedToken.visitorId(), requestInfo);
        VisitorContextHolder.set(new VisitorContext(
                resolvedToken.visitorId(),
                resolvedToken.visitorKey(),
                authorization
        ));
        try {
            return joinPoint.proceed();
        } finally {
            VisitorContextHolder.clear();
        }
    }

    /**
     * 从切点解析目标方法。
     *
     * @param joinPoint 切点
     * @return 目标方法
     */
    private Method resolveMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getMethod();
    }

    /**
     * 解析接口访问类型：方法级注解优先，方法未标记时再读取类级注解。
     *
     * @param method    目标方法
     * @param joinPoint 切点
     * @return 接口访问类型
     */
    private Optional<AccessType> resolveAccessType(Method method, ProceedingJoinPoint joinPoint) {
        Optional<AccessType> methodAccessType = resolveAccessType(method);
        if (methodAccessType.isPresent()) {
            return methodAccessType;
        }
        Class<?> declaringType = ((MethodSignature) joinPoint.getSignature()).getDeclaringType();
        return resolveAccessType(declaringType);
    }

    /**
     * 从类或方法上的访问控制注解解析接口访问类型。
     *
     * @param element 类或方法
     * @return 接口访问类型
     */
    private Optional<AccessType> resolveAccessType(AnnotatedElement element) {
        if (element.isAnnotationPresent(LoginAccess.class)) {
            return Optional.of(AccessType.LOGIN);
        }
        if (element.isAnnotationPresent(SystemAccess.class)) {
            return Optional.of(AccessType.SYSTEM);
        }
        if (element.isAnnotationPresent(MaintainerAccess.class)) {
            return Optional.of(AccessType.MAINTAINER);
        }
        if (element.isAnnotationPresent(VisitorAccess.class)) {
            return Optional.of(AccessType.VISITOR);
        }
        return Optional.empty();
    }

    /**
     * 获取当前 HTTP 请求。
     *
     * @return 当前请求，不存在时返回空
     */
    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return null;
        }
        return servletRequestAttributes.getRequest();
    }

}
