package com.jxc.wefolio.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 访客类接口标记 — 标注在访客可访问的接口上（作品集展示页等）。
 * 访客无需维护者登录，但后续会通过微信授权获取头像和昵称等基本信息。
 * 当前版本暂不校验访客身份，后续会有独立的访客认证逻辑。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface VisitorAccess {
}
