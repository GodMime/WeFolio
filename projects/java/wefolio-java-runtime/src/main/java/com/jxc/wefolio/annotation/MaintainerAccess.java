package com.jxc.wefolio.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 维护者类接口标记 — 标注在需要维护者登录的接口上。
 * 该类接口要求有效的 Authorization 请求头，认证通过后注入维护者身份上下文。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaintainerAccess {
}
