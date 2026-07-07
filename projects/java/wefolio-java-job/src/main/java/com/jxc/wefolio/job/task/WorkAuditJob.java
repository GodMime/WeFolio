package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.WorkAuditProperties;
import com.jxc.wefolio.job.service.WorkAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 作品内容审核定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkAuditJob {

    /** 当前实例是否已有一轮任务在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final WorkAuditService workAuditService;

    private final WorkAuditProperties properties;

    /**
     * 每分钟触发作品审核任务。
     */
    @Scheduled(cron = "${work-audit.cron:0 * * * * ?}")
    public void run() {
        if (!properties.isEnabled()) {
            log.debug("作品审核任务未启用，跳过本轮执行");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("上一轮作品审核任务尚未结束，跳过本轮执行");
            return;
        }
        try {
            workAuditService.runOneRound();
        } finally {
            running.set(false);
        }
    }
}
