package com.jxc.wefolio.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 登录类接口标记 — 标注在登录相关接口上（微信登录、登录态校验等）。
 * 该类接口不要求 Authorization 请求头，因为维护者和访客均未登录。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginAccess {
}
