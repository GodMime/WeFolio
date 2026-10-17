package com.jxc.wefolio.job.model;

import com.jxc.wefolio.service.portfolioai.PortfolioAiVideoAudioContext;

/** 当前作品媒体摘要的缓存身份及写入时源守卫，全部来自已授权的冻结计划。 */
public record PortfolioAiMediaSummaryIdentity(String cacheKey, String sourceVersion, String model,
        String promptVersion, String route, String manifestHash, String sourceObjectKey, String sourceEtag,
        long sourceBytes, String videoAudioFullHash) {
    /** 旧无音频任务只可在仍未生成文字时写缓存。 */
    public PortfolioAiMediaSummaryIdentity(String cacheKey, String sourceVersion, String model,
            String promptVersion, String route, String manifestHash, String sourceObjectKey, String sourceEtag, long sourceBytes) {
        this(cacheKey, sourceVersion, model, promptVersion, route, manifestHash, sourceObjectKey, sourceEtag, sourceBytes,
                PortfolioAiVideoAudioContext.fullHash(null));
    }
}
