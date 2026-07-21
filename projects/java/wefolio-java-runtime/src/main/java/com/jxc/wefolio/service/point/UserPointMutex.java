package com.jxc.wefolio.service.point;

import java.util.function.Supplier;

/**
 * 用户积分互斥抽象 — 调用方只能依赖该接口，不得依赖当前本地实现。
 *
 * <p>该互斥只缩小同一 runtime 实例内的方法体并发窗口。数据库原子 SQL、唯一键和
 * 任务租约仍是数据一致性与任务唯一领取的最终边界。</p>
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
