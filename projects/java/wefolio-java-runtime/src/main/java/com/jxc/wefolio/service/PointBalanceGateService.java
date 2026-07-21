package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 访客打开前的维护者实际可用积分门禁。
 */
@Service
@RequiredArgsConstructor
public class PointBalanceGateService {

    private final PointAccountEntityMapper pointAccountEntityMapper;

    /** @return 账户不存在或实际可用积分小于等于零时返回真。 */
    public boolean isNonPositive(Long userId) {
        PointAccountEntity account = pointAccountEntityMapper.selectOne(
                Wrappers.lambdaQuery(PointAccountEntity.class)
                        .eq(PointAccountEntity::getUserId, userId)
                        .last("LIMIT 1"));
        return account == null || account.getBalance() == null || account.getBalance() <= 0L;
    }
}
