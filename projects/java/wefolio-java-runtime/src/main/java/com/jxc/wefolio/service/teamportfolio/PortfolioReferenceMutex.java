package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.service.point.UserPointMutex;

import java.util.function.Supplier;

/**
 * 作品集跨聚合引用互斥抽象。
 *
 * <p>个人作品集删除、成员移除或授权变更与团队作品集引用写入共用该跨实例分布式互斥边界。
 * 必须在首次相关读取前进入，并持有到事务提交或回滚。</p>
 *
 * <p>团队作品集发布在本锁内扣费，锁序为本引用锁先于 {@link UserPointMutex}；
 * 已持有用户积分锁的调用链不得反向进入引用变更入口。成员移除和授权确认本身不调用积分扣费。</p>
 */
public interface PortfolioReferenceMutex {

    /**
     * 在作品集引用全局互斥区间内执行业务动作。
     *
     * @param action 业务动作
     * @param <T> 返回类型
     * @return 业务动作结果
     */
    <T> T execute(Supplier<T> action);
}
