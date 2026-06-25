package com.jxc.wefolio.config;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.service.AuthTokenService;
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

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 控制器访问控制切面 — 根据接口标记注解执行对应的认证逻辑。
 *
 * <ul>
 *   <li>{@link LoginAccess} — 登录类接口，跳过认证直接放行</li>
 *   <li>{@link SystemAccess} — 系统类接口，跳过认证直接放行</li>
 *   <li>{@link MaintainerAccess} — 维护者类接口，要求有效的 Authorization 令牌</li>
 *   <li>{@link VisitorAccess} — 访客类接口，当前跳过认证，后续实现独立访客认证逻辑</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuthAspect {

    /** 登录令牌认证服务 */
    private final AuthTokenService authTokenService;

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

        if (isLoginAccess(method, joinPoint) || isSystemAccess(method, joinPoint) || isVisitorAccess(method, joinPoint)) {
            return joinPoint.proceed();
        }

        if (isMaintainerAccess(method, joinPoint)) {
            return authenticateMaintainer(joinPoint);
        }

        // 正常情况不会到达此处（启动时已验证所有接口均有标记），
        // 保留此防御性分支以防运行时反射绕过
        throw new AuthenticationRequiredException("接口未配置访问控制注解");
    }

    /**
     * 维护者认证 — 校验 Authorization 令牌并注入登录上下文。
     *
     * @param joinPoint 切点
     * @return 控制器执行结果
     * @throws Throwable 控制器执行异常
     */
    private Object authenticateMaintainer(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            throw new AuthenticationRequiredException();
        }

        String authorization = request.getHeader("Authorization");
        Optional<Long> userId = authTokenService.resolveAuthenticatedUserId(authorization);
        if (userId.isEmpty()) {
            throw new AuthenticationRequiredException();
        }

        AuthContextHolder.set(new AuthContext(userId.get(), authorization));
        try {
            return joinPoint.proceed();
        } finally {
            AuthContextHolder.clear();
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
     * 判断是否标记了 {@link LoginAccess}。
     *
     * @param method    目标方法
     * @param joinPoint 切点
     * @return 是否标记
     */
    private boolean isLoginAccess(Method method, ProceedingJoinPoint joinPoint) {
        Class<?> declaringType = ((MethodSignature) joinPoint.getSignature()).getDeclaringType();
        return method.isAnnotationPresent(LoginAccess.class)
                || declaringType.isAnnotationPresent(LoginAccess.class);
    }

    /**
     * 判断是否标记了 {@link SystemAccess}。
     *
     * @param method    目标方法
     * @param joinPoint 切点
     * @return 是否标记
     */
    private boolean isSystemAccess(Method method, ProceedingJoinPoint joinPoint) {
        Class<?> declaringType = ((MethodSignature) joinPoint.getSignature()).getDeclaringType();
        return method.isAnnotationPresent(SystemAccess.class)
                || declaringType.isAnnotationPresent(SystemAccess.class);
    }

    /**
     * 判断是否标记了 {@link MaintainerAccess}。
     *
     * @param method    目标方法
     * @param joinPoint 切点
     * @return 是否标记
     */
    private boolean isMaintainerAccess(Method method, ProceedingJoinPoint joinPoint) {
        Class<?> declaringType = ((MethodSignature) joinPoint.getSignature()).getDeclaringType();
        return method.isAnnotationPresent(MaintainerAccess.class)
                || declaringType.isAnnotationPresent(MaintainerAccess.class);
    }

    /**
     * 判断是否标记了 {@link VisitorAccess}。
     *
     * @param method    目标方法
     * @param joinPoint 切点
     * @return 是否标记
     */
    private boolean isVisitorAccess(Method method, ProceedingJoinPoint joinPoint) {
        Class<?> declaringType = ((MethodSignature) joinPoint.getSignature()).getDeclaringType();
        return method.isAnnotationPresent(VisitorAccess.class)
                || declaringType.isAnnotationPresent(VisitorAccess.class);
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
