package com.jxc.wefolio.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 朋友圈单页匿名访客接口标记。
 * 仅与 {@link VisitorAccess} 配合使用，允许作用域匹配的朋友圈匿名访客令牌访问当前方法。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TimelineAnonymousAccess {
}
