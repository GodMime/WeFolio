package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.RechargeOrderCloseProperties;
import com.jxc.wefolio.job.repo.RechargeOrderCloseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 过期充值订单分批关闭服务。
 */
@Slf4j
@Service
public class RechargeOrderCloseService {

    /** 非法批大小提示 */
    private static final String INVALID_BATCH_SIZE_MESSAGE = "过期充值订单关闭批大小必须大于 0";

    /** 非法最大批次数提示 */
    private static final String INVALID_MAX_BATCHES_MESSAGE = "过期充值订单单轮最大批次数必须大于 0";

    /** 关闭仓储 */
    private final RechargeOrderCloseRepository repository;

    /** 任务配置 */
    private final RechargeOrderCloseProperties properties;

    /** 可替换时钟 */
    private final Clock clock;

    /**
     * 创建生产服务并使用配置时区生成时钟。
     *
     * @param repository 关闭仓储
     * @param properties 任务配置
     */
    @Autowired
    public RechargeOrderCloseService(
            RechargeOrderCloseRepository repository,
            RechargeOrderCloseProperties properties
    ) {
        this(repository, properties, Clock.system(ZoneId.of(properties.getZone())));
    }

    /** 测试专用构造器，用固定时钟验证过期边界。 */
    RechargeOrderCloseService(
            RechargeOrderCloseRepository repository,
            RechargeOrderCloseProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 按配置分批关闭过期订单。
     *
     * @return 本轮关闭结果
     */
    public CloseResult closeExpiredOrders() {
        int batchSize = properties.getBatchSize();
        int maxBatches = properties.getMaxBatches();
        if (batchSize <= 0) {
            throw new IllegalArgumentException(INVALID_BATCH_SIZE_MESSAGE);
        }
        if (maxBatches <= 0) {
            throw new IllegalArgumentException(INVALID_MAX_BATCHES_MESSAGE);
        }

        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of(properties.getZone())));
        int closedCount = 0;
        int batchCount = 0;
        int affected = 0;
        while (batchCount < maxBatches) {
            affected = repository.closeExpiredOrders(now, batchSize);
            closedCount = Math.addExact(closedCount, affected);
            batchCount++;
            if (affected < batchSize) {
                break;
            }
        }
        boolean limitReached = batchCount == maxBatches && affected == batchSize;
        CloseResult result = new CloseResult(closedCount, batchCount, limitReached);
        log.info("过期充值订单关闭任务完成: now={}, closedCount={}, batchCount={}, limitReached={}",
                now, result.closedCount(), result.batchCount(), result.limitReached());
        return result;
    }

    /**
     * 单轮关闭结果。
     *
     * @param closedCount 关闭订单总数
     * @param batchCount 执行批次数
     * @param limitReached 是否达到单轮批次上限且最后一批仍满批
     */
    public record CloseResult(int closedCount, int batchCount, boolean limitReached) {
    }
}
