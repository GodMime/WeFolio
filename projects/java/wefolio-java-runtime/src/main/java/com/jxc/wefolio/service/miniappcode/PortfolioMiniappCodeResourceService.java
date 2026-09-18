package com.jxc.wefolio.service.miniappcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.common.cache.CacheService;
import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import com.jxc.wefolio.config.PortfolioMiniappCodeProperties;
import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dto.PortfolioMiniappCodeResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMiniappCodeImageMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import com.jxc.wefolio.service.miniappcode.MiniappCodeObjectStore.StoredCode;

/** 为授权后的发布快照提供绘制资源，只有原码未命中时访问微信并上传图片。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioMiniappCodeResourceService {
    /** 个人访客页，不含查询参数。 */
    private static final String USER_PAGE = "pages/portfolios/visitor-portfolio/visitor-portfolio";
    /** 团队访客页。 */
    private static final String TEAM_PAGE = "pages/team-portfolios/visitor-portfolio/team-visitor-portfolio";
    /** 元数据保留七天，过期不改变分享码有效性。 */
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    /** 限流窗口。 */
    private static final Duration RATE_TTL = Duration.ofMinutes(1);
    /** 单个操作用户每窗口未命中生成上限。 */
    private static final int RATE_LIMIT = 5;
    /** 元数据缓存命名空间。 */
    private static final String CACHE_PREFIX = "portfolio:miniapp-code:";
    /** 普通分布式锁前缀。 */
    private static final String LOCK_PREFIX = "lock:portfolio-miniapp-code:";
    /** 限流缓存前缀。 */
    private static final String RATE_PREFIX = "portfolio:miniapp-code:rate:";
    /** 衍生图对象目录。 */
    private static final String OBJECT_FOLDER = "/protfolio/miniapp-code/";
    /** 允许的微信环境，防止环境名进入任意对象路径。 */
    private static final Set<String> ENVIRONMENTS = Set.of("release", "trial", "develop");
    /** 原码类型标记。 */
    private static final String RAW_KIND = "raw-";
    /** 手机端固定布局版本；修改字体排版或几何尺寸时同步升级。 */
    private static final String TEMPLATE_VERSION = "client-card-v1";
    /** 完整名片宽度。 */
    private static final int WIDTH = 1080;
    /** 完整名片高度。 */
    private static final int HEIGHT = 1440;
    /** 中心头像直径。 */
    private static final int AVATAR_SIZE = 120;
    /** 摘要算法。 */
    private static final String HASH_ALGORITHM = "SHA-256";
    /** 元数据缓存。 */
    private final CacheService cache;
    /** 跨实例同键生成合并与限流锁。 */
    private final DistributedLockExecutor locks;
    /** 受控图片对象存储。 */
    private final MiniappCodeObjectStore store;
    /** 官方微信码客户端。 */
    private final WechatMiniappCodeClient client;
    /** AppID 用于缓存隔离。 */
    private final WechatMiniappProperties wechat;
    /** 环境与路径校验配置。 */
    private final PortfolioMiniappCodeProperties properties;
    /** 快照确定性序列化。 */
    private final ObjectMapper mapper;

    /** 返回绘制资源；上层在调用前后校验当前权限和发布状态。 */
    public PortfolioMiniappCodeResponse generate(PortfolioMiniappCodeSnapshot snapshot, Long actorUserId) {
        validate(snapshot, actorUserId);
        MiniappCodeObjectStore.AvatarResource avatar = store.avatarResource(snapshot);
        String page = PortfolioOwnerTypeDict.TEAM.getCode().equals(snapshot.ownerType()) ? TEAM_PAGE : USER_PAGE;
        String rawHash = hash(json(List.of(wechat.getAppId(), properties.getEnvVersion(), properties.isCheckPath(),
                page, snapshot.shareCode(), WechatMiniappCodeClient.CODE_SIZE, false, false, 0, 0, 0,
                snapshot.ownerType(), snapshot.ownerId(), snapshot.uniqueCode(), snapshot.portfolioId())));
        StoredCode key = rawCode(snapshot, page, rawHash, actorUserId);
        String codeUrl = store.url(key);
        String version = hash(json(List.of(snapshot, rawHash, codeUrl, avatar.version(), TEMPLATE_VERSION)));
        return new PortfolioMiniappCodeResponse(codeUrl, avatar.url(), version, snapshot.ownerType(),
                snapshot.displayName(), snapshot.subtitle(), snapshot.shareTitle(),
                WIDTH, HEIGHT, WechatMiniappCodeClient.CODE_SIZE, AVATAR_SIZE);
    }

    /** 原码命中只读对象头；Redis 缺失时检查确定性 PNG/JPEG 路径恢复。 */
    private StoredCode rawCode(PortfolioMiniappCodeSnapshot snapshot, String page, String rawHash, Long actorUserId) {
        String baseKey = snapshot.uniqueCode() + OBJECT_FOLDER + snapshot.portfolioId() + "/"
                + properties.getEnvVersion() + "/" + rawHash;
        String cacheKey = CACHE_PREFIX + RAW_KIND + rawHash;
        StoredCode cached = cachedMetadata(cacheKey, baseKey);
        StoredCode current = cached == null ? null : store.inspect(cached.key());
        if (sameVersion(cached, current)) return current;
        return locks.execute(LOCK_PREFIX + RAW_KIND + rawHash, () -> {
            StoredCode inside = cachedMetadata(cacheKey, baseKey);
            if (inside != null) {
                StoredCode checked = store.inspect(inside.key());
                if (sameVersion(inside, checked)) return checked;
                // 保留旧可信版本直到官方重建成功，避免失败重试把已知异常对象按持久缓存恢复。
            } else {
                StoredCode png = store.inspect(baseKey + MiniappCodeObjectStore.PNG_SUFFIX);
                StoredCode jpg = store.inspect(baseKey + MiniappCodeObjectStore.JPEG_SUFFIX);
                StoredCode recovered = png == null ? jpg : jpg == null || png.lastModified() >= jpg.lastModified() ? png : jpg;
                if (recovered != null) {
                    cache.put(cacheKey, recovered, CACHE_TTL);
                    return recovered;
                }
            }
            consumeQuota(actorUserId);
            String requestId = UUID.randomUUID().toString();
            long started = System.nanoTime();
            byte[] bytes = client.generate(page, snapshot.shareCode());
            StoredCode generated = store.put(baseKey, bytes);
            cache.put(cacheKey, generated, CACHE_TTL);
            log.info("作品集小程序码原码生成 requestId={} portfolioId={} bytes={} elapsedMs={}", requestId,
                    snapshot.portfolioId(), bytes.length, (System.nanoTime() - started) / 1_000_000);
            return generated;
        });
    }

    /** 元数据只能引用本请求确定性对象，坏 Redis 元数据可从持久存储恢复。 */
    private StoredCode cachedMetadata(String key, String baseKey) {
        StoredCode code;
        try {
            code = cache.get(key, StoredCode.class).orElse(null);
        } catch (IllegalStateException exception) {
            cache.evict(key);
            return null;
        }
        if (code != null && !(Objects.equals(code.key(), baseKey + MiniappCodeObjectStore.PNG_SUFFIX)
                || Objects.equals(code.key(), baseKey + MiniappCodeObjectStore.JPEG_SUFFIX))) {
            cache.evict(key);
            return null;
        }
        return code;
    }

    /** 热缓存必须与当前 COS 版本和格式完全一致。 */
    private boolean sameVersion(StoredCode cached, StoredCode current) {
        return cached != null && current != null && Objects.equals(cached.eTag(), current.eTag())
                && Objects.equals(cached.contentType(), current.contentType());
    }
    /** 在用户锁内记录固定一分钟窗口，缓存命中不执行。 */
    private void consumeQuota(Long actorUserId) {
        String key = RATE_PREFIX + actorUserId;
        locks.execute(LOCK_PREFIX + key, () -> {
            RateWindow window = cache.get(key, RateWindow.class).orElse(null);
            long now = System.currentTimeMillis();
            if (window == null || now >= window.expiresAt()) window = new RateWindow(0, now + RATE_TTL.toMillis());
            if (window.count() >= RATE_LIMIT) throw new BusinessException(PortfolioMiniappCodeImageMessage.RATE_LIMITED);
            cache.put(key, new RateWindow(window.count() + 1, window.expiresAt()), Duration.ofMillis(Math.max(1, window.expiresAt() - now)));
            return null;
        });
    }
    /** 独立可序列化限流元数据，保留固定窗口截止时间。 */
    public record RateWindow(int count, long expiresAt) { }
    /** 不允许缺失 AppID/身份或不受控对象目录参与缓存和上传。 */
    private void validate(PortfolioMiniappCodeSnapshot snapshot, Long actorUserId) {
        if (snapshot == null || actorUserId == null || actorUserId <= 0 || snapshot.uniqueCode() == null || !snapshot.uniqueCode().matches("[A-Za-z0-9]+")
                || snapshot.ownerId() == null || snapshot.shareCode() == null || snapshot.portfolioId() == null
                || snapshot.portfolioId() <= 0 || snapshot.ownerId() <= 0 || !ENVIRONMENTS.contains(properties.getEnvVersion())
                || wechat.getAppId() == null || wechat.getAppId().isBlank()
                || PortfolioOwnerTypeDict.fromCode(snapshot.ownerType()) == null) {
            throw new BusinessException(PortfolioMiniappCodeImageMessage.INVALID_PARAMETERS);
        }
    }
    /** 将包含全部字段的快照序列化，避免字符串拼接碰撞。 */
    private byte[] json(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (Exception exception) { throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED); }
    }
    /** 获取稳定内容摘要，不存储原头像字节到 Redis。 */
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes)); }
        catch (Exception exception) { throw new BusinessException(PortfolioMiniappCodeImageMessage.GENERATION_FAILED); }
    }
}
