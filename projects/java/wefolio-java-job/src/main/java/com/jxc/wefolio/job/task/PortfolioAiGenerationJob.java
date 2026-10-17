package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.PortfolioAiSchedulingConfig;
import com.jxc.wefolio.job.service.PortfolioAiExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 高级作品集 AI 调度入口；业务规则全部位于执行服务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioAiGenerationJob {
    /** 有界任务执行服务。 */
    private final PortfolioAiExecutionService service;

    /** 每轮维护过期任务再领取尚有并发额度的等待任务。 */
    @Scheduled(fixedDelayString = "${portfolio-ai.scan-interval:2s}", initialDelayString = "${portfolio-ai.scan-initial-delay:2s}", scheduler = PortfolioAiSchedulingConfig.SCHEDULER)
    public void scan() { service.maintain(); service.dispatch(); }

    /** 每小时物理清理到期隐私任务，开关关闭不影响清理。 */
    @Scheduled(cron = "${portfolio-ai.cleanup-cron:0 15 * * * ?}", zone = "Asia/Shanghai", scheduler = PortfolioAiSchedulingConfig.SCHEDULER)
    public void cleanup() { int count = service.cleanup(); if (count > 0) { log.info("高级 AI 到期任务清理 count={}", count); } }
}
