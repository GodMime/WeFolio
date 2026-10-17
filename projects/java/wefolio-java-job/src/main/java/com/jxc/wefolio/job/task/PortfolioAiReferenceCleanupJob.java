package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.PortfolioAiSchedulingConfig;
import com.jxc.wefolio.job.service.PortfolioAiReferenceCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 轻量原件清理调度入口，执行规则集中在 Service。 */
@Component
@RequiredArgsConstructor
public class PortfolioAiReferenceCleanupJob {
    /** 有界扫描与当前引用复核。 */
    private final PortfolioAiReferenceCleanupService service;
    /** 默认每小时清理一次，关闭附件接单时仍继续排空历史原件。 */
    @Scheduled(cron = "${portfolio-ai.reference-cleanup-cron:0 35 * * * ?}", zone = "Asia/Shanghai", scheduler = PortfolioAiSchedulingConfig.SCHEDULER)
    public void cleanup() { service.cleanup(); }
}
