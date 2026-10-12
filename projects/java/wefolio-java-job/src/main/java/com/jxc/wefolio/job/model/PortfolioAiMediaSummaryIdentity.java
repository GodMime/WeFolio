package com.jxc.wefolio.job.model;

/** 当前作品媒体摘要的缓存身份及写入时源守卫，全部来自已授权的冻结计划。 */
public record PortfolioAiMediaSummaryIdentity(String cacheKey, String sourceVersion, String model,
        String promptVersion, String route, String manifestHash, String sourceObjectKey, String sourceEtag,
        long sourceBytes) { }
