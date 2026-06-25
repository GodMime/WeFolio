package com.jxc.wefolio.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 系统类接口标记 — 标注在系统接口上（健康检查、版本号等）。
 * 该类接口不要求 Authorization 请求头，可被负载均衡器或监控系统调用。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemAccess {
}
