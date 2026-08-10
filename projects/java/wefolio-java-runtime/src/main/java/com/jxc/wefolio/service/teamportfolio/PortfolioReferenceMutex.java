package com.jxc.wefolio.service.teamportfolio;

import java.util.function.Supplier;

/**
 * 作品集跨聚合引用互斥抽象。
 *
 * <p>个人作品集删除与团队作品集引用写入必须共用该跨实例分布式互斥边界。</p>
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
