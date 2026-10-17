package com.jxc.wefolio.job.service;

/** 最终短事务提交结果，旧清单仅用于提交后的安全清理。 */
public record PersistentVideoCompletion(boolean committed, String previousManifest) { }
