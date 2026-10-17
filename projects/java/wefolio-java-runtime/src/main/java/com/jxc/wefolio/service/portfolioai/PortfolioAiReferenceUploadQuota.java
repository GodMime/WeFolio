package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Duration;

/** 复用现有缓存限流模式，约束签票请求而非持久附件名额。 */
@Service
@RequiredArgsConstructor
public class PortfolioAiReferenceUploadQuota {
    /** 独立业务键与一分钟最多二十次的宽限额度。 */
    private static final String PREFIX="portfolio-ai:reference-upload:quota:", LOCK_PREFIX="lock:portfolio-ai:reference-upload:quota:";
    private static final int LIMIT=20;
    /** 统一缓存及跨实例锁，不新增业务表。 */
    private final CacheService cache;
    private final DistributedLockExecutor locks;

    /** 固定分钟窗口不会因每次请求而延长。 */
    public void consume(long actor) {
        long minute=System.currentTimeMillis()/60_000;
        String key=PREFIX+actor+":"+minute;
        locks.execute(LOCK_PREFIX+actor,()->{
            int count=cache.get(key,Integer.class).orElse(0);
            if(count>=LIMIT) throw new BusinessException(PortfolioAiReferenceMessage.BUSY);
            cache.put(key,count+1,Duration.ofMinutes(2)); return null;
        });
    }
}
