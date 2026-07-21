package com.jxc.wefolio.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 标准作品集发布事务服务 — 为发布写入和积分扣减提供统一事务边界。
 */
@Service
public class PortfolioPublishTransactionService {

    /**
     * 在同一事务内执行标准作品集发布动作。
     *
     * @param publishAction 发布动作
     * @param <T> 发布结果类型
     * @return 发布结果
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(Supplier<T> publishAction) {
        Objects.requireNonNull(publishAction, "发布事务动作不能为空");
        return publishAction.get();
    }
}
