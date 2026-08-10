package com.jxc.wefolio.service.point;

import java.util.function.Supplier;

/**
 * 用户积分互斥抽象 — 调用方只能依赖该接口，不得依赖具体 Redis 实现。
 *
 * <p>生产实现跨 runtime 实例互斥；数据库原子 SQL、唯一键和任务租约仍是数据一致性与
 * 任务唯一领取的最终边界。</p>
 */
public interface UserPointMutex {

    /**
     * 在指定用户的互斥区间内执行业务动作。
     *
     * @param userId 用户 ID
     * @param action 业务动作
     * @param <T> 返回类型
     * @return 业务动作结果
     */
    <T> T execute(Long userId, Supplier<T> action);
}
