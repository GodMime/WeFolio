package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PointDebitTaskStatusDict;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointDebitTaskEntityMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信待扣任务恢复、租约领取与并行执行扫描器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointDebitTaskScanner {

    private static final int SCAN_LIMIT = 100;
    private static final int WORKER_COUNT = 4;

    /** 当前实例租约标识。 */
    private final String leaseOwner = "runtime-debit-" + UUID.randomUUID();

    /** 单实例扫描运行标记。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 微信结算专用工作线程池。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);

    private final PointDebitTaskEntityMapper pointDebitTaskEntityMapper;
    private final PointAccountEntityMapper pointAccountEntityMapper;
    private final PointDebitTaskService pointDebitTaskService;
    private final PointDebitTaskProcessor pointDebitTaskProcessor;
    private final WechatVirtualPaymentProperties properties;

    /** 应用启动后恢复缺失任务并只领取已到期任务。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        scan();
    }

    /** 上一轮全部工作结束固定延迟后再次扫描。 */
    @Scheduled(fixedDelayString = "${wechat.virtual-payment.settlement.scan-interval:10s}")
    public void scheduledScan() {
        scan();
    }

    /** 执行一次不重叠的扫描，并等待本轮所有工作线程结束。 */
    public void scan() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            recoverMissingTasks();
            List<Long> candidateIds = findCandidateIds();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Long taskId : candidateIds) {
                futures.add(CompletableFuture.runAsync(() -> processOne(taskId), executor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            running.set(false);
        }
    }

    /** 处理一条任务，异常不传播到同批次其他任务。 */
    private void processOne(Long taskId) {
        try {
            pointDebitTaskProcessor.process(taskId, leaseOwner);
        } catch (RuntimeException exception) {
            log.warn("微信待扣任务处理失败 taskId={}", taskId, exception);
        }
    }

    /** 按账户待扣快照补建缺失活动任务。 */
    private void recoverMissingTasks() {
        List<PointAccountEntity> accounts = pointAccountEntityMapper.selectList(
                Wrappers.lambdaQuery(PointAccountEntity.class)
                        .gt(PointAccountEntity::getPendingDebit, 0L));
        for (PointAccountEntity account : accounts) {
            try {
                pointDebitTaskService.ensureActiveTask(account);
            } catch (RuntimeException exception) {
                log.warn("恢复缺失待扣任务失败 userId={}", account.getUserId(), exception);
            }
        }
    }

    /** 查询一批到期任务候选 ID，真正领取仍由独立事务原子更新完成。 */
    private List<Long> findCandidateIds() {
        LocalDateTime now = LocalDateTime.now();
        return pointDebitTaskEntityMapper.selectList(
                        Wrappers.lambdaQuery(PointDebitTaskEntity.class)
                                .select(PointDebitTaskEntity::getId)
                                .eq(PointDebitTaskEntity::getActiveFlag, 1)
                                .and(query -> query
                                        .and(waiting -> waiting
                                                .in(PointDebitTaskEntity::getStatus,
                                                        PointDebitTaskStatusDict.WAITING.getCode(),
                                                        PointDebitTaskStatusDict.RETRY_WAIT.getCode())
                                                .le(PointDebitTaskEntity::getNextExecuteAt, now))
                                        .or(expired -> expired
                                                .eq(PointDebitTaskEntity::getStatus,
                                                        PointDebitTaskStatusDict.RUNNING.getCode())
                                                .lt(PointDebitTaskEntity::getLeaseUntil, now)))
                                .orderByAsc(PointDebitTaskEntity::getNextExecuteAt)
                                .last("LIMIT " + SCAN_LIMIT))
                .stream()
                .map(PointDebitTaskEntity::getId)
                .toList();
    }

    /** 关闭应用时停止专用线程池接收新任务。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
