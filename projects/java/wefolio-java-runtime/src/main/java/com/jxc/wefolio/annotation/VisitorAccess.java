package com.jxc.wefolio.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 访客类接口标记 — 标注在访客可访问的接口上（作品集展示页等）。
 * 访客无需维护者登录，但必须携带有效的访客登录令牌。
 * 切面会校验访客 Authorization 令牌，并将访客身份写入访客上下文。
 * 访客打开入口等无需令牌的接口应使用 {@link LoginAccess} 放行。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface VisitorAccess {
}
